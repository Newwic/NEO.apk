package com.neo.assistant.ai

import android.content.Context
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.memory.MemoryEntity
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
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
        private const val INFERENCE_TIMEOUT_SECONDS = 75L
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
                    // Smaller context/token budget for phone CPU/RAM stability.
                    contextSize = 1024,
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
        val webBlocks = extraContext.filter { it.startsWith("[WEB ") }
        if (webBlocks.isNotEmpty()) return@withContext buildDirectWebAnswer(message, webBlocks)

        if (inferenceTimedOut) {
            return@withContext "สมอง 7B เคยค้างในรอบนี้ครับ กรุณาปิด NEO แล้วเปิดใหม่ หรือใช้ Web/Knowledge ก่อน ระหว่างที่ปรับโหมด 7B ให้เหมาะกับเครื่อง"
        }

        if (!prepare(onStatus)) {
            if (extraContext.isNotEmpty()) {
                return@withContext "ผมพบข้อมูลใน Knowledge แล้ว แต่สมอง Local 7B ยังไม่พร้อมครับ\n\n" +
                    extraContext.take(4).joinToString("\n\n") { it.take(700) }
            }
            return@withContext "ยังติดตั้งสมอง Local 7B ไม่สำเร็จครับ ต้องมีพื้นที่ว่างอย่างน้อยประมาณ 6 GB และ RAM ว่างพอ"
        }

        val memoryText = memories.take(8).joinToString("\n") {
            "[${it.category} | ${it.source}→${it.destination} | p${it.importance}] ${it.text.take(350)}"
        }
        val contextText = extraContext.take(4).joinToString("\n\n") { it.take(600) }
        val system = buildString {
            append(cfg.systemPrompt)
            append("\nคุณชื่อ NEO เป็นผู้ช่วยส่วนตัวของผู้ใช้ ตอบภาษาไทยเป็นหลัก เว้นแต่ผู้ใช้ขอภาษาอื่น")
            append("\nคุณทำงานบนมือถือแบบ Local และสามารถใช้ Memory กับ Local Knowledge")
            append("\nตอบสั้น กระชับ และตรงคำถาม เพื่อลดเวลาประมวลผลบนมือถือ")
            append("\nห้ามแต่งข้อมูล หากข้อมูลไม่พอให้บอกตรง ๆ")
            if (memoryText.isNotBlank()) append("\n\nMEMORY ROUTE DATA:\n$memoryText")
            if (contextText.isNotBlank()) append("\n\nRAG CONTEXT:\n$contextText")
        }

        val current = model ?: return@withContext "สมอง Local ยังไม่พร้อมครับ"
        onStatus("LOCAL • 7B กำลังสร้างคำตอบ • จำกัด ${INFERENCE_TIMEOUT_SECONDS}s")

        val future = inferenceExecutor.submit<String> {
            val result = Llama.complete(
                model = current,
                prompt = message.take(1200),
                systemPrompt = system.take(7000),
                maxTokens = cfg.maxTokens.coerceIn(48, 128)
            )
            result.text.trim().ifBlank { "ผมยังคิดคำตอบไม่ออกครับ ลองถามใหม่อีกครั้ง" }
        }

        try {
            val answer = future.get(INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            onStatus("LOCAL • Qwen2.5 7B พร้อมใช้งาน")
            answer
        } catch (_: TimeoutException) {
            inferenceTimedOut = true
            future.cancel(true)
            onStatus("LOCAL • 7B ใช้เวลานานเกิน 75s • หยุดรอแล้ว")
            "สมอง 7B ใช้เวลานานเกิน 75 วินาทีบนเครื่องนี้ ผมหยุดรอเพื่อไม่ให้แอปค้างครับ ข้อมูล Memory/RAG ยังทำงานปกติ — รอบถัดไปควรใช้โหมด 3B/7B Auto"
        } catch (e: Throwable) {
            future.cancel(true)
            onStatus("LOCAL • 7B error • ${e.javaClass.simpleName}")
            "สมอง Local 7B มีปัญหาระหว่างประมวลผล (${e.javaClass.simpleName}) ครับ"
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
