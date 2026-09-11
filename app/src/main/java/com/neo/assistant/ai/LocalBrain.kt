package com.neo.assistant.ai

import android.content.Context
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.knowledge.KnowledgeHub
import com.neo.assistant.memory.MemoryEntity
import com.neo.assistant.memory.NeoDatabase
import com.neo.assistant.web.SearchResult
import com.neo.assistant.web.WebSearchClient
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LocalBrain(private val context: Context) {
    private val modelMutex = Mutex()
    private val generationMutex = Mutex()
    private val modelManager = ModelManager(context)
    private val appDataDao = NeoDatabase.get(context).appDataDao()
    private val webSearch = WebSearchClient()
    private val knowledgeHub = KnowledgeHub(appDataDao)

    @Volatile private var inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var model: LlamaModel? = null
    @Volatile private var loadedProfile: ModelProfile? = null
    @Volatile private var lastInferenceFailure: String? = null

    fun manager(): ModelManager = modelManager
    fun activeProfile(): ModelProfile = modelManager.selected()
    fun loadedModelProfile(): ModelProfile? = loadedProfile

    suspend fun prepare(onStatus: (String) -> Unit = {}): Boolean = modelMutex.withLock {
        val profile = modelManager.selected()
        if (model != null && loadedProfile == profile) return@withLock true

        if (!modelManager.isInstalled(profile)) {
            onStatus("MODEL • ${profile.modelName} ยังไม่ได้ดาวน์โหลด")
            return@withLock false
        }

        releaseLoadedModel()
        return@withLock try {
            onStatus("MODEL • กำลังโหลด ${profile.modelName}…")
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
            model = Llama.loadModel(
                modelManager.file(profile).absolutePath,
                LlamaConfig(
                    contextSize = profile.contextSize,
                    threads = threads,
                    gpuLayers = 0,
                    temperature = 0.35f,
                    topP = 0.9f,
                    topK = 40
                )
            )
            loadedProfile = profile
            lastInferenceFailure = null
            onStatus("LOCAL • ${profile.label} พร้อมใช้งาน")
            true
        } catch (e: Throwable) {
            model = null
            loadedProfile = null
            lastInferenceFailure = "LOAD_${e.javaClass.simpleName}"
            onStatus("MODEL • โหลด ${profile.modelName} ไม่สำเร็จ")
            false
        }
    }

    suspend fun switchModel(profile: ModelProfile, onStatus: (String) -> Unit = {}): Boolean {
        modelMutex.withLock {
            if (loadedProfile != profile) releaseLoadedModel()
            modelManager.select(profile)
        }
        return prepare(onStatus)
    }

    suspend fun generate(
        message: String,
        memories: List<MemoryEntity>,
        cfg: NeoLiveConfig,
        extraContext: List<String> = emptyList(),
        onStatus: (String) -> Unit = {}
    ): String = generationMutex.withLock {
        withContext(Dispatchers.IO) {
            fastPath(message, memories)?.let { return@withContext it }

            val profile = modelManager.selected()
            if (!modelManager.isInstalled(profile)) {
                return@withContext "ยังไม่ได้ติดตั้ง ${profile.modelName} ครับ ไปที่หน้า Models แล้วกด Download ก่อน"
            }
            if (!prepare(onStatus)) {
                return@withContext "โหลด ${profile.modelName} ไม่สำเร็จครับ ลองปิดแอปอื่นหรือเลือกโมเดลที่เล็กลง"
            }

            val current = model ?: return@withContext "NEO ยังโหลดโมเดลไม่สำเร็จครับ"
            val web = extraContext.filter { it.startsWith("[WEB ") }
            val chats = extraContext.filter { it.startsWith("[CHAT") }
            val knowledge = extraContext.filterNot { it.startsWith("[WEB ") || it.startsWith("[CHAT") }

            val system = buildString {
                append("คุณคือ NEO ผู้ช่วย AI ส่วนตัวที่รันอยู่บน Android ของผู้ใช้\n")
                append("ตอบภาษาเดียวกับผู้ใช้โดยอัตโนมัติ ถ้าผู้ใช้ใช้ภาษาไทยให้ตอบไทยเป็นหลัก\n")
                append("ตอบตรงคำถาม เป็นธรรมชาติ ไม่แต่งข้อมูล และไม่อ้างว่าทำสิ่งที่ยังไม่ได้ทำจริง\n")
                append("ถ้ามี MEMORY ให้ใช้เฉพาะเมื่อเกี่ยวข้องกับคำถามปัจจุบัน\n")
                append("ถ้ามี WEB_CONTEXT ให้ยึดข้อมูลนั้นสำหรับข้อมูลสด\n")
                if (chats.isNotEmpty()) append("CHAT_HISTORY:\n${chats.takeLast(8).joinToString("\n") { it.take(260) }}\n")
                if (memories.isNotEmpty()) append("MEMORY:\n${memories.take(5).joinToString("\n") { it.text.take(220) }}\n")
                if (knowledge.isNotEmpty()) append("KNOWLEDGE:\n${knowledge.take(4).joinToString("\n") { it.take(420) }}\n")
                if (web.isNotEmpty()) append("WEB_CONTEXT:\n${web.take(4).joinToString("\n") { it.take(520) }}\n")
                if (webSearch.shouldSearch(message) && web.isEmpty()) append("ถ้าคำถามต้องการข้อมูลล่าสุดแต่ไม่มี WEB_CONTEXT ให้ตอบ [[NEED_WEB]] เท่านั้น\n")
                if (cfg.systemPrompt.isNotBlank()) append(cfg.systemPrompt.take(500))
            }

            onStatus("LOCAL • ${profile.label} กำลังคิด…")
            val maxTokens = cfg.maxTokens.coerceIn(64, profile.maxTokens)
            val answer = runInference(current, message.take(1200), system.take(7000), maxTokens)
            if (answer.isNullOrBlank()) {
                onStatus("LOCAL • โมเดลไม่ตอบ กำลังกู้ระบบ…")
                recoverAfterFailure()
                return@withContext safeFallback(message, memories, extraContext, onStatus)
            }

            lastInferenceFailure = null
            val clean = answer.trim()
            if (clean.contains("[[NEED_WEB]]")) {
                return@withContext searchAndSynthesize(message, memories, onStatus)
            }
            clean
        }
    }

    private fun runInference(current: LlamaModel, prompt: String, system: String, maxTokens: Int): String? {
        val timeoutSeconds = when (modelManager.selected()) {
            ModelProfile.FAST -> 35L
            ModelProfile.BALANCED -> 50L
            ModelProfile.SMART -> 80L
            ModelProfile.ULTRA -> 120L
        }
        val executor = inferenceExecutor
        val future = executor.submit<String> {
            runBlocking {
                Llama.complete(
                    model = current,
                    prompt = prompt,
                    systemPrompt = system,
                    maxTokens = maxTokens
                ).text.trim()
            }
        }
        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            lastInferenceFailure = "TIMEOUT"
            future.cancel(true)
            null
        } catch (e: Throwable) {
            lastInferenceFailure = e.javaClass.simpleName
            future.cancel(true)
            null
        }
    }

    private suspend fun searchAndSynthesize(
        message: String,
        memories: List<MemoryEntity>,
        onStatus: (String) -> Unit
    ): String {
        if (!webSearch.canFallbackSearch(message)) return safeFallback(message, memories, emptyList(), onStatus)
        onStatus("WEB • กำลังค้นข้อมูลล่าสุด…")
        val page = runCatching { webSearch.search(message) }.getOrNull()
        if (page == null || page.results.isEmpty()) return "ยังไม่พบข้อมูลล่าสุดที่ตรงกับคำถามครับ"
        page.results.take(3).forEach { result ->
            runCatching { knowledgeHub.learnWeb(result.title, result.snippet, result.url) }
        }
        return conciseWebFallback(page.results)
    }

    private suspend fun safeFallback(
        message: String,
        memories: List<MemoryEntity>,
        extra: List<String>,
        onStatus: (String) -> Unit
    ): String {
        val webBlocks = extra.filter { it.startsWith("[WEB ") }.mapNotNull(::parseBlock)
        if (webBlocks.isNotEmpty()) return conciseWebFallback(webBlocks)
        if (webSearch.canFallbackSearch(message)) {
            onStatus("WEB • Local ตอบไม่ได้ กำลังค้นข้อมูล…")
            val page = runCatching { webSearch.search(message) }.getOrNull()
            if (page != null && page.results.isNotEmpty()) return conciseWebFallback(page.results)
        }
        if (memories.isNotEmpty() && message.contains("จำ")) {
            return memories.take(5).joinToString("\n") { "• ${it.text}" }
        }
        return "NEO ประมวลผลรอบนี้ไม่สำเร็จครับ ลองเลือกโมเดลที่เล็กลงหรือส่งคำถามอีกครั้ง"
    }

    private fun conciseWebFallback(results: List<SearchResult>): String =
        results.take(3).joinToString("\n") { "${it.title}: ${it.snippet.take(300)}" }

    private fun parseBlock(block: String): SearchResult? {
        val lines = block.lines()
        if (lines.size < 2) return null
        val title = lines.first().substringAfter(":", "ข้อมูลเว็บ").substringBeforeLast("]").trim()
        val url = lines.firstOrNull { it.startsWith("SOURCE:") }?.substringAfter("SOURCE:")?.trim().orEmpty()
        val snippet = lines.drop(1).firstOrNull { it.isNotBlank() && !it.startsWith("SOURCE:") } ?: return null
        return SearchResult(title, snippet, url)
    }

    private fun fastPath(message: String, memories: List<MemoryEntity>): String? {
        val q = message.lowercase().trim()
        if (q == "neo" || q == "นีโอ" || q == "นาย") return "ครับ ผม NEO อยู่นี่ ถามมาได้เลย"
        if (q.contains("ชื่ออะไร") || q.contains("นายคือใคร")) return "ผมคือ NEO ผู้ช่วย AI ส่วนตัวบนมือถือของคุณครับ"
        if ((q.contains("จำอะไร") || q.contains("จำได้")) && memories.isNotEmpty()) {
            return memories.take(5).joinToString("\n") { "• ${it.text}" }
        }
        return null
    }

    @Synchronized
    private fun recoverAfterFailure() {
        runCatching { inferenceExecutor.shutdownNow() }
        inferenceExecutor = Executors.newSingleThreadExecutor()
        releaseLoadedModel()
    }

    private fun releaseLoadedModel() {
        val current = model
        model = null
        loadedProfile = null
        if (current != null) runCatching { Llama.releaseModel(current) }
    }

    fun release() {
        runCatching { inferenceExecutor.shutdownNow() }
        releaseLoadedModel()
    }
}
