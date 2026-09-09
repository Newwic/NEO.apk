package com.neo.assistant.ai

import android.content.Context
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.memory.MemoryEntity
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

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
            val ok = downloadModel(file, onStatus)
            if (!ok) return@withLock false
        }
        return@withLock try {
            val gb = file.length().toDouble() / 1_073_741_824.0
            onStatus("LOCAL • พบโมเดล 7B %.1f GB • กำลังโหลด…".format(gb))
            model = Llama.loadModel(
                modelPath = file.absolutePath,
                config = LlamaConfig(
                    contextSize = 768,
                    threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 5),
                    gpuLayers = 0,
                    temperature = 0.62f,
                    topP = 0.9f,
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
            if (target.parentFile?.exists() != true) target.parentFile?.mkdirs()
            val req = Request.Builder().url(MODEL_URL).get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    onStatus("LOCAL • ดาวน์โหลด 7B ไม่สำเร็จ (${res.code})")
                    return@withContext false
                }
                val body = res.body ?: return@withContext false
                val total = body.contentLength()
                var done = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    FileOutputStream(tmp, false).use { out ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = ((done * 100) / total).toInt()
                                if (percent != lastPercent && (percent % 2 == 0 || percent == 100)) {
                                    lastPercent = percent
                                    onStatus("LOCAL • ติดตั้งสมอง 7B $percent%")
                                }
                            } else {
                                onStatus("LOCAL • ติดตั้งสมอง ${(done / 1_048_576)} MB")
                            }
                        }
                    }
                }
            }
            if (tmp.length() < MIN_MODEL_BYTES) {
                tmp.delete()
                onStatus("LOCAL • ไฟล์ 7B ไม่สมบูรณ์")
                return@withContext false
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Throwable) {
            onStatus("LOCAL • ดาวน์โหลด 7B ขัดข้อง • ${e.javaClass.simpleName}")
            false
        }
    }

    suspend fun generate(
        message: String,
        memories: List<MemoryEntity>,
        cfg: NeoLiveConfig,
        extraContext: List<String> = emptyList(),
        onStatus: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        fastPath(message, memories, extraContext)?.let {
            onStatus("LOCAL • ตอบจากข้อมูลโดยตรง")
            return@withContext it
        }

        val webBlocks = extraContext.filter { it.startsWith("[WEB ") }
        if (webBlocks.isNotEmpty()) return@withContext buildDirectWebAnswer(message, webBlocks)

        if (inferenceTimedOut) {
            return@withContext buildFallback(memories, extraContext,
                "สมอง 7B ยังไม่พร้อมหลังจากรอบก่อนใช้เวลานานเกินกำหนด")
        }

        if (!prepare(onStatus)) {
            return@withContext buildFallback(memories, extraContext,
                "สมอง Local 7B ยังโหลดไม่สำเร็จ")
        }

        val memoryText = memories.take(6).joinToString("\n") {
            "[${it.category} | p${it.importance}] ${it.text.take(220)}"
        }
        val contextText = extraContext.take(3).joinToString("\n\n") { it.take(450) }
        val system = buildString {
            append(cfg.systemPrompt.take(2200))
            append("\nคุณชื่อ NEO เป็นผู้ช่วยส่วนตัวของผู้ใช้ ตอบภาษาไทยเป็นหลัก")
            append("\nตอบสั้น ตรงคำถาม ใช้ Memory/RAG ที่ให้มา ห้ามแต่งข้อมูล")
            if (memoryText.isNotBlank()) append("\nMEMORY:\n$memoryText")
            if (contextText.isNotBlank()) append("\nRAG:\n$contextText")
        }

        val current = model ?: return@withContext buildFallback(memories, extraContext, "สมอง Local ยังไม่พร้อม")
        onStatus("LOCAL • 7B กำลังสร้างคำตอบ • สูงสุด ${INFERENCE_TIMEOUT_SECONDS}s")

        val future = inferenceExecutor.submit<String> {
            runBlocking {
                val result = Llama.complete(
                    model = current,
                    prompt = message.take(700),
                    systemPrompt = system.take(4200),
                    maxTokens = cfg.maxTokens.coerceIn(40, 96)
                )
                result.text.trim().ifBlank { "ผมยังคิดคำตอบไม่ออกครับ ลองถามใหม่อีกครั้ง" }
            }
        }

        try {
            val answer = future.get(INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            onStatus("LOCAL • Qwen2.5 7B พร้อมใช้งาน")
            answer
        } catch (_: TimeoutException) {
            inferenceTimedOut = true
            future.cancel(true)
            onStatus("LOCAL • 7B ช้าเกิน ${INFERENCE_TIMEOUT_SECONDS}s • ใช้ fallback")
            buildFallback(memories, extraContext, "7B ใช้เวลานานเกิน ${INFERENCE_TIMEOUT_SECONDS} วินาที")
        } catch (e: Throwable) {
            future.cancel(true)
            onStatus("LOCAL • 7B error • ${e.javaClass.simpleName}")
            buildFallback(memories, extraContext, "7B error: ${e.javaClass.simpleName}")
        }
    }

    private fun fastPath(message: String, memories: List<MemoryEntity>, extraContext: List<String>): String? {
        val q = message.lowercase().trim()
        if (q.contains("นายชื่ออะไร") || q.contains("ชื่อของนาย") || q == "ชื่ออะไร") {
            return "ผมชื่อ NEO ครับ เป็นผู้ช่วย AI ส่วนตัวของคุณ"
        }
        if (memories.isNotEmpty() && (q.contains("จำอะไร") || q.contains("ข้อมูลที่จำ") || q.contains("รายชื่ออะไร") || q.contains("ชื่ออะไร"))) {
            val selected = memories.take(5).map { it.text.trim() }.filter { it.isNotBlank() }
            if (selected.isNotEmpty()) return "ข้อมูลที่ผมดึงจากความจำได้ตอนนี้:\n" + selected.joinToString("\n") { "• $it" }
        }
        if (extraContext.isNotEmpty() && (q.contains("จากข้อมูล") || q.contains("ในไฟล์") || q.contains("knowledge"))) {
            return "ข้อมูลที่เกี่ยวข้องที่ผมพบ:\n\n" + extraContext.take(3).joinToString("\n\n") { it.take(600) }
        }
        return null
    }

    private fun buildFallback(memories: List<MemoryEntity>, extraContext: List<String>, reason: String): String {
        val parts = mutableListOf<String>()
        if (memories.isNotEmpty()) {
            parts += "Memory ที่เกี่ยวข้อง:\n" + memories.take(4).joinToString("\n") { "• ${it.text.take(300)}" }
        }
        if (extraContext.isNotEmpty()) {
            parts += "Knowledge/RAG ที่เกี่ยวข้อง:\n" + extraContext.take(3).joinToString("\n\n") { it.take(500) }
        }
        return if (parts.isEmpty()) {
            "$reason ครับ แต่แอปไม่ค้างแล้ว คุณยังใช้ Memory, Knowledge และ Web ได้ตามปกติ"
        } else {
            "$reason ครับ ผมจึงตอบจากข้อมูลที่ดึงได้โดยตรงก่อน:\n\n" + parts.joinToString("\n\n")
        }
    }

    private fun buildDirectWebAnswer(message: String, webBlocks: List<String>): String {
        val items = webBlocks.take(4).mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return@mapNotNull null
            val title = lines.first().substringAfter(":", lines.first()).substringBeforeLast("]").trim()
            val source = lines.firstOrNull { it.startsWith("SOURCE:") }
                ?.removePrefix("SOURCE:")?.trim().orEmpty()
            val snippet = lines
                .filterNot { it.startsWith("[WEB ") || it.startsWith("SOURCE:") }
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(500)
            Triple(title.ifBlank { "แหล่งข้อมูลเว็บ" }, snippet, source)
        }

        if (items.isEmpty()) return "ค้นเว็บได้แล้ว แต่ยังไม่พบข้อมูลที่ใช้ตอบได้ครับ"

        return buildString {
            append("ผมหาข้อมูลจากเว็บให้แล้วครับ")
            if (message.isNotBlank()) append(" สำหรับคำถาม: “${message.take(120)}”")
            append("\n\n")
            items.forEachIndexed { index, (title, snippet, source) ->
                append("${index + 1}. $title")
                if (snippet.isNotBlank()) append("\n$snippet")
                if (source.isNotBlank()) append("\n$source")
                if (index != items.lastIndex) append("\n\n")
            }
        }
    }

    fun release() {
        inferenceExecutor.shutdownNow()
        if (!inferenceTimedOut) {
            model?.let { runCatching { Llama.releaseModel(it) } }
        }
        model = null
    }
}
