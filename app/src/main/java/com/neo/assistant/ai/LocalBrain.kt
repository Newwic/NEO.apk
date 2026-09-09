package com.neo.assistant.ai

import android.content.Context
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.knowledge.KnowledgeHub
import com.neo.assistant.memory.MemoryEntity
import com.neo.assistant.memory.NeoDatabase
import com.neo.assistant.web.WebSearchClient
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LocalBrain(private val context: Context) {
    companion object {
        private const val MODEL_FILE = "Qwen2.5-7B-Instruct-Q4_K_M.gguf"
        private const val MODEL_URL = "https://huggingface.co/lmstudio-community/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf?download=true"
        private const val MIN_MODEL_BYTES = 4_000_000_000L
        private const val INFERENCE_TIMEOUT_SECONDS = 60L
    }

    private val mutex = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val webSearch = WebSearchClient()
    private val knowledgeHub = KnowledgeHub(NeoDatabase.get(context).appDataDao())
    @Volatile private var model: LlamaModel? = null
    @Volatile private var inferenceTimedOut = false

    fun modelFile(): File {
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_FILE)
    }

    suspend fun prepare(onStatus: (String) -> Unit = {}): Boolean = mutex.withLock {
        if (model != null) return@withLock true
        val file = modelFile()
        if (!file.exists() || file.length() < MIN_MODEL_BYTES) {
            if (!downloadModel(file, onStatus)) return@withLock false
        }
        return@withLock try {
            onStatus("LOCAL • กำลังโหลด Qwen2.5 7B…")
            model = Llama.loadModel(
                file.absolutePath,
                LlamaConfig(
                    contextSize = 1536,
                    threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 6),
                    gpuLayers = 0,
                    temperature = 0.35f,
                    topP = 0.88f,
                    topK = 40
                )
            )
            inferenceTimedOut = false
            onStatus("LOCAL • Qwen2.5 7B พร้อมใช้งาน")
            true
        } catch (e: Throwable) {
            onStatus("LOCAL • โหลด 7B ไม่สำเร็จ • ${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun downloadModel(target: File, onStatus: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, "$MODEL_FILE.part")
        try {
            target.parentFile?.mkdirs()
            val req = Request.Builder().url(MODEL_URL).get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext false
                val body = res.body ?: return@withContext false
                val total = body.contentLength(); var done = 0L; var last = -1
                body.byteStream().use { input -> FileOutputStream(tmp, false).use { out ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buffer); if (n <= 0) break
                        out.write(buffer, 0, n); done += n
                        if (total > 0) {
                            val p = ((done * 100) / total).toInt()
                            if (p != last && p % 2 == 0) { last = p; onStatus("LOCAL • ดาวน์โหลดสมอง 7B $p%") }
                        }
                    }
                }}
            }
            if (tmp.length() < MIN_MODEL_BYTES) { tmp.delete(); return@withContext false }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, true); tmp.delete() }
            true
        } catch (_: Throwable) { false }
    }

    suspend fun generate(
        message: String,
        memories: List<MemoryEntity>,
        cfg: NeoLiveConfig,
        extraContext: List<String> = emptyList(),
        onStatus: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        fastPath(message, memories)?.let { return@withContext it }

        if (inferenceTimedOut || !prepare(onStatus)) {
            return@withContext safeFallback(message, memories, extraContext, onStatus)
        }

        val current = model ?: return@withContext safeFallback(message, memories, extraContext, onStatus)
        val webBlocks = extraContext.filter { it.startsWith("[WEB ") }
        val knowledgeBlocks = extraContext.filterNot { it.startsWith("[WEB ") }
        val memoryText = memories.take(4).joinToString("\n") { "[${it.category}] ${it.text.take(180)}" }
        val knowledgeText = knowledgeBlocks.take(3).joinToString("\n\n") { it.take(420) }
        val webText = webBlocks.take(4).joinToString("\n\n") { it.take(500) }
        val isDynamic = webBlocks.isNotEmpty() || webSearch.shouldSearch(message)

        val system = buildString {
            append("คุณคือ NEO ผู้ช่วย AI ส่วนตัวบนมือถือ ใช้ Qwen2.5 7B เป็นสมองหลัก\n")
            append("ตอบภาษาเดียวกับผู้ใช้ และตอบตรงคำถามก่อนเสมอ\n")
            append("กฎสำคัญ: คำถามความรู้ทั่วไปให้ใช้ความรู้ในโมเดลก่อน ห้ามค้นเว็บเพียงเพราะไม่แน่ใจเล็กน้อย\n")
            append("Memory คือข้อมูลส่วนตัวของผู้ใช้เท่านั้น และ Knowledge/Web เป็นข้อมูลประกอบ ให้ละทิ้งข้อความที่ไม่เกี่ยวข้อง\n")
            append("ห้ามคัดลอกข้อความค้นหาดิบ ห้ามแต่งประวัติคน/สถานที่ที่ผู้ใช้ไม่ได้ถาม\n")
            append("ปกติตอบ 1-4 ประโยค หรือ bullet สั้น ๆ ถ้าผู้ใช้ขอรายละเอียดจึงค่อยตอบยาว\n")
            append("ถ้าเป็นข้อมูลสด เช่น ข่าว ราคา ค่าเงิน อากาศ ให้ใช้ WEB_CONTEXT เท่านั้นและสรุปให้เข้าใจง่าย\n")
            append("ถ้าข้อมูลเว็บไม่ตรงคำถาม ให้บอกว่าไม่พบข้อมูลที่ตรง แทนการเดา\n")
            if (cfg.systemPrompt.isNotBlank()) append("USER_CONFIG: ${cfg.systemPrompt.take(600)}\n")
            if (memoryText.isNotBlank()) append("MEMORY:\n$memoryText\n")
            if (knowledgeText.isNotBlank()) append("KNOWLEDGE_CONTEXT:\n$knowledgeText\n")
            if (webText.isNotBlank()) append("WEB_CONTEXT:\n$webText\n")
            if (isDynamic && webText.isBlank()) append("ถ้าคำถามนี้ต้องการข้อมูลสดและไม่มี WEB_CONTEXT ให้ตอบ [[NEED_WEB]] เท่านั้น\n")
        }

        onStatus(if (webBlocks.isNotEmpty()) "WEB • NEO กำลังสรุป…" else "LOCAL • NEO 7B กำลังคิด…")
        val answer = runInference(current, message.take(800), system.take(6000), cfg.maxTokens.coerceIn(64, 180))

        if (answer == null) {
            inferenceTimedOut = true
            return@withContext safeFallback(message, memories, extraContext, onStatus)
        }

        val clean = answer.trim()
        if (clean.isBlank()) return@withContext safeFallback(message, memories, extraContext, onStatus)

        if (clean.contains("[[NEED_WEB]]")) {
            return@withContext searchAndSynthesize(message, memories, knowledgeBlocks, onStatus)
        }

        clean
    }

    private fun runInference(model: LlamaModel, prompt: String, system: String, maxTokens: Int): String? {
        val future = inferenceExecutor.submit<String> {
            runBlocking { Llama.complete(model, prompt, system, maxTokens = maxTokens).text.trim() }
        }
        return try {
            future.get(INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true); null
        } catch (_: Throwable) {
            future.cancel(true); null
        }
    }

    private suspend fun searchAndSynthesize(
        message: String,
        memories: List<MemoryEntity>,
        knowledgeBlocks: List<String>,
        onStatus: (String) -> Unit
    ): String {
        if (!webSearch.canFallbackSearch(message)) return safeFallback(message, memories, knowledgeBlocks, onStatus)
        onStatus("WEB • กำลังค้นข้อมูลที่ตรงคำถาม…")
        val packet = runCatching { webSearch.search(message) }.getOrNull()
        if (packet == null || packet.results.isEmpty()) return "ยังไม่พบข้อมูลเว็บที่ตรงกับคำถามครับ"

        packet.results.take(3).forEach { r -> runCatching { knowledgeHub.learnWeb(r.title, r.snippet, r.url) } }
        val current = model
        if (current != null && !inferenceTimedOut) {
            val webText = packet.results.take(4).mapIndexed { i, r -> "[$i] ${r.title}\n${r.snippet}" }.joinToString("\n\n")
            val system = """
                คุณคือ NEO ให้ตอบคำถามจาก WEB_CONTEXT ด้านล่างเท่านั้น
                สรุปให้ตรงคำถาม ไม่คัดลอกข้อความดิบ ไม่พูดเรื่องที่ไม่เกี่ยวข้อง
                ตอบภาษาเดียวกับผู้ใช้ ปกติ 1-4 ประโยค ถ้าเป็นข่าวให้สรุปไม่เกิน 3 หัวข้อ
                ถ้าข้อมูลไม่พอให้บอกตรง ๆ ว่ายังไม่พบข้อมูลที่ตรง
                WEB_CONTEXT:
                ${webText.take(3500)}
            """.trimIndent()
            onStatus("WEB • NEO 7B กำลังสรุป…")
            val synthesized = runInference(current, message.take(700), system, 140)
            if (!synthesized.isNullOrBlank() && !synthesized.contains("[[NEED_WEB]]")) return synthesized.trim()
        }
        return conciseWebFallback(message, packet.results)
    }

    private suspend fun safeFallback(
        message: String,
        memories: List<MemoryEntity>,
        extraContext: List<String>,
        onStatus: (String) -> Unit
    ): String {
        val webBlocks = extraContext.filter { it.startsWith("[WEB ") }
        if (webBlocks.isNotEmpty()) {
            val results = webBlocks.mapNotNull { parseBlock(it) }
            if (results.isNotEmpty()) return conciseWebFallback(message, results)
        }
        if (webSearch.shouldSearch(message) && webSearch.canFallbackSearch(message)) {
            onStatus("WEB • กำลังค้นข้อมูล…")
            val p = runCatching { webSearch.search(message) }.getOrNull()
            if (p != null && p.results.isNotEmpty()) return conciseWebFallback(message, p.results)
        }
        if (memories.isNotEmpty() && (message.contains("จำ") || message.contains("ข้อมูลของผม"))) {
            return "ผมจำได้ว่า ${memories.take(3).joinToString(" / ") { it.text.take(100) }}"
        }
        return "ตอนนี้สมอง Local ยังสร้างคำตอบไม่ได้ครับ ลองถามใหม่อีกครั้งได้เลย"
    }

    private fun fastPath(message: String, memories: List<MemoryEntity>): String? {
        val q = message.lowercase().trim()
        if (listOf("นายชื่ออะไร", "ชื่อของนาย", "นายเป็นใคร", "คุณเป็นใคร", "who are you", "what is your name").any { q.contains(it) }) {
            return "ผมคือ NEO ผู้ช่วย AI ส่วนตัวของคุณครับ รันสมอง Qwen2.5 7B บนมือถือและใช้ Memory, Knowledge และ Web เมื่อจำเป็น"
        }
        if (listOf("นายเก่งอะไร", "ทำอะไรได้บ้าง", "ช่วยอะไรได้บ้าง", "what can you do").any { q.contains(it) }) {
            return "ผมช่วยคุยตอบคำถาม อธิบายความรู้ เขียน/ช่วยคิดโค้ด จำข้อมูลของคุณ ค้น Knowledge และค้นเว็บสำหรับข้อมูลสดได้ครับ"
        }
        if (q.contains("computer") && (q.contains("ส่วนประกอบ") || q.contains("components"))) {
            return "ส่วนประกอบหลักของคอมพิวเตอร์มี CPU, Mainboard, RAM, Storage (SSD/HDD), GPU, Power Supply และอุปกรณ์ Input/Output เช่น คีย์บอร์ด เมาส์ และจอครับ"
        }
        if (memories.isNotEmpty() && (q.contains("จำอะไร") || q.contains("ข้อมูลที่จำ"))) {
            return "ผมจำได้ว่า ${memories.take(3).joinToString(" / ") { it.text.take(100) }}"
        }
        return null
    }

    private fun parseBlock(block: String): WebSearchClient.Result? {
        val lines = block.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val title = lines.first().substringAfter(":", lines.first()).substringBeforeLast("]").trim()
        val url = lines.firstOrNull { it.startsWith("SOURCE:") }?.removePrefix("SOURCE:")?.trim().orEmpty()
        val snippet = lines.filterNot { it.startsWith("[WEB ") || it.startsWith("SOURCE:") }.joinToString(" ").trim()
        if (snippet.isBlank()) return null
        return WebSearchClient.Result(title, snippet, url)
    }

    private fun conciseWebFallback(message: String, results: List<WebSearchClient.Result>): String {
        val q = message.lowercase()
        if (results.isEmpty()) return "ยังไม่พบข้อมูลที่ตรงกับคำถามครับ"
        if (listOf("dollar", "ดอลลาร์", "usd", "ค่าเงิน", "อัตราแลกเปลี่ยน", "บาท", "eur", "jpy", "gbp").any { q.contains(it) }) {
            return results.first().snippet.take(140)
        }
        if (listOf("ข่าว", "news", "ล่าสุด", "อัปเดต").any { q.contains(it) }) {
            return "ข่าวล่าสุดที่พบ:\n" + results.take(3).mapIndexed { i, r -> "${i + 1}. ${r.title.take(110)}" }.joinToString("\n")
        }
        return results.first().snippet.take(260).ifBlank { results.first().title }
    }

    fun release() {
        inferenceExecutor.shutdownNow()
        if (!inferenceTimedOut) model?.let { runCatching { Llama.releaseModel(it) } }
        model = null
    }
}
