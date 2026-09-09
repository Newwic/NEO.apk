package com.neo.assistant.ai

import android.content.Context
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.memory.MemoryEntity
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
            val gb = file.length().toDouble() / 1_073_741_824.0
            onStatus("LOCAL • พบโมเดล 7B %.1f GB • กำลังโหลด…".format(gb))
            model = Llama.loadModel(modelPath = file.absolutePath, config = LlamaConfig(
                contextSize = 1024,
                threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 5),
                gpuLayers = 0, temperature = 0.55f, topP = 0.9f, topK = 40
            ))
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
                val total = body.contentLength(); var done = 0L; var lastPercent = -1
                body.byteStream().use { input -> FileOutputStream(tmp, false).use { out ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buffer); if (n <= 0) break
                        out.write(buffer, 0, n); done += n
                        if (total > 0) {
                            val p = ((done * 100) / total).toInt()
                            if (p != lastPercent && (p % 2 == 0 || p == 100)) { lastPercent = p; onStatus("LOCAL • ติดตั้งสมอง 7B $p%") }
                        }
                    }
                }}
            }
            if (tmp.length() < MIN_MODEL_BYTES) { tmp.delete(); return@withContext false }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            true
        } catch (_: Throwable) { false }
    }

    suspend fun generate(message: String, memories: List<MemoryEntity>, cfg: NeoLiveConfig, extraContext: List<String> = emptyList(), onStatus: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        fastPath(message, memories)?.let { return@withContext it }
        val webBlocks = extraContext.filter { it.startsWith("[WEB ") }
        if (webBlocks.isNotEmpty()) return@withContext buildDirectWebAnswer(message, webBlocks)

        if (inferenceTimedOut || !prepare(onStatus)) return@withContext searchFallback(message, memories, extraContext, onStatus)

        val memoryText = memories.take(6).joinToString("\n") { "[${it.category} | p${it.importance}] ${it.text.take(220)}" }
        val contextText = extraContext.take(3).joinToString("\n\n") { it.take(450) }
        val system = buildString {
            append(cfg.systemPrompt.take(1800))
            append("\nคุณชื่อ NEO เป็นผู้ช่วย AI ส่วนตัว ตอบภาษาไทยเป็นหลัก")
            append("\nให้คิดและใช้ความรู้พื้นฐานที่มีอยู่ในโมเดลก่อน ไม่ต้องพึ่ง Memory สำหรับความรู้ทั่วไป")
            append("\nแยกให้ชัด: Memory คือข้อมูลส่วนตัวของผู้ใช้, Knowledge คือเอกสาร, ความรู้ทั่วไปให้ใช้ความรู้ของโมเดล")
            append("\nตอบคำถามตรง ๆ และให้เหตุผลภายในก่อนตอบ ห้ามแสดง chain-of-thought ยาว ๆ")
            append("\nถ้าคำถามต้องใช้ข้อมูลปัจจุบัน/ราคา/ข่าว หรือคุณไม่มั่นใจจริง ๆ ให้ตอบเพียง [[NEED_WEB]]")
            append("\nห้ามเดาตัวเลขหรือข้อเท็จจริงเมื่อไม่รู้")
            if (memoryText.isNotBlank()) append("\nMEMORY:\n$memoryText")
            if (contextText.isNotBlank()) append("\nRAG:\n$contextText")
        }
        val current = model ?: return@withContext searchFallback(message, memories, extraContext, onStatus)
        onStatus("LOCAL • NEO กำลังคิด…")
        val future = inferenceExecutor.submit<String> { runBlocking {
            Llama.complete(model = current, prompt = message.take(900), systemPrompt = system.take(5000), maxTokens = cfg.maxTokens.coerceIn(64, 160)).text.trim()
        }}
        try {
            val answer = future.get(INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (answer.isBlank() || answer.contains("[[NEED_WEB]]")) {
                onStatus("WEB • Local ไม่มั่นใจ กำลังค้นอัตโนมัติ…")
                searchFallback(message, memories, extraContext, onStatus)
            } else {
                onStatus("LOCAL • Qwen2.5 7B พร้อมใช้งาน")
                answer
            }
        } catch (_: TimeoutException) {
            inferenceTimedOut = true; future.cancel(true)
            searchFallback(message, memories, extraContext, onStatus)
        } catch (_: Throwable) {
            future.cancel(true)
            searchFallback(message, memories, extraContext, onStatus)
        }
    }

    private suspend fun searchFallback(message: String, memories: List<MemoryEntity>, extraContext: List<String>, onStatus: (String) -> Unit): String {
        if (webSearch.canFallbackSearch(message)) {
            onStatus("WEB • NEO ไม่แน่ใจ กำลังค้นหาคำตอบ…")
            val packet = webSearch.search(message)
            if (packet.results.isNotEmpty()) {
                onStatus("WEB • พบข้อมูล ${packet.results.size} แหล่ง")
                return buildDirectWebAnswer(message, packet.results.take(5).mapIndexed { i, r -> "[WEB ${i+1}: ${r.title}]\n${r.snippet}\nSOURCE: ${r.url}" })
            }
        }
        if (extraContext.isNotEmpty()) return "จาก Knowledge ที่มี:\n" + extraContext.take(3).joinToString("\n\n") { it.take(500) }
        if (memories.isNotEmpty() && message.contains("จำ")) return "จากความจำที่เกี่ยวข้อง:\n" + memories.take(4).joinToString("\n") { "• ${it.text.take(300)}" }
        return "ตอนนี้ผมยังหาคำตอบที่มั่นใจไม่ได้ครับ"
    }

    private fun fastPath(message: String, memories: List<MemoryEntity>): String? {
        val q = message.lowercase().trim()
        if (q.contains("นายชื่ออะไร") || q.contains("ชื่อของนาย")) return "ผมชื่อ NEO ครับ เป็นผู้ช่วย AI ส่วนตัวของคุณ"
        if (memories.isNotEmpty() && (q.contains("จำอะไร") || q.contains("ข้อมูลที่จำ"))) {
            val selected = memories.take(5).map { it.text.trim() }.filter { it.isNotBlank() }
            if (selected.isNotEmpty()) return "ข้อมูลที่ผมจำได้ตอนนี้:\n" + selected.joinToString("\n") { "• $it" }
        }
        return null
    }

    private fun buildDirectWebAnswer(message: String, webBlocks: List<String>): String {
        val items = webBlocks.take(5).mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }; if (lines.isEmpty()) return@mapNotNull null
            val title = lines.first().substringAfter(":", lines.first()).substringBeforeLast("]").trim()
            val source = lines.firstOrNull { it.startsWith("SOURCE:") }?.removePrefix("SOURCE:")?.trim().orEmpty()
            val snippet = lines.filterNot { it.startsWith("[WEB ") || it.startsWith("SOURCE:") }.joinToString(" ").replace(Regex("\\s+"), " ").trim().take(600)
            Triple(title, snippet, source)
        }
        if (items.isEmpty()) return "ค้นเว็บแล้ว แต่ยังไม่พบข้อมูลที่ใช้ตอบได้ครับ"
        return buildString {
            append("จากข้อมูลที่ค้นได้:\n\n")
            items.forEachIndexed { i, (title, snippet, source) ->
                append("${i+1}. $title")
                if (snippet.isNotBlank()) append("\n$snippet")
                if (source.isNotBlank()) append("\nแหล่งข้อมูล: $source")
                if (i != items.lastIndex) append("\n\n")
            }
        }
    }

    fun release() {
        inferenceExecutor.shutdownNow()
        if (!inferenceTimedOut) model?.let { runCatching { Llama.releaseModel(it) } }
        model = null
    }
}
