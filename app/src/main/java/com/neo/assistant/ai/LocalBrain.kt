package com.neo.assistant.ai

import android.content.Context
import com.neo.assistant.dev.NeoLiveConfig
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
import java.util.concurrent.TimeUnit

class LocalBrain(private val context: Context) {
    companion object {
        private const val MODEL_FILE = "Qwen2.5-3B-Instruct-Q4_K_M.gguf"
        private const val MODEL_URL = "https://huggingface.co/lmstudio-community/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf?download=true"
        private const val MIN_MODEL_BYTES = 1_800_000_000L
    }

    private val mutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var model: LlamaModel? = null

    @Suppress("UNUSED_PARAMETER")
    fun setBaseUrl(url: String) { /* kept for compatibility: local engine no longer uses HTTP */ }

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
            onStatus("LOCAL • กำลังโหลดสมอง 3B…")
            model = Llama.loadModel(
                modelPath = file.absolutePath,
                config = LlamaConfig(
                    contextSize = 3072,
                    threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
                    gpuLayers = 0,
                    temperature = 0.65f,
                    topP = 0.9f,
                    topK = 40
                )
            )
            onStatus("LOCAL • Qwen2.5 3B พร้อมใช้งาน")
            true
        } catch (e: Exception) {
            onStatus("LOCAL • โหลดโมเดลไม่สำเร็จ")
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
                    onStatus("LOCAL • ดาวน์โหลดสมองไม่สำเร็จ (${res.code})")
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
                                    onStatus("LOCAL • ติดตั้งสมอง 3B $percent%")
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
                onStatus("LOCAL • ไฟล์โมเดลไม่สมบูรณ์")
                return@withContext false
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            onStatus("LOCAL • ดาวน์โหลดสมองขัดข้อง")
            false
        }
    }

    suspend fun generate(message: String, memories: List<String>, cfg: NeoLiveConfig): String {
        if (!prepare()) return "ยังติดตั้งสมอง Local ไม่สำเร็จครับ เช็กอินเทอร์เน็ตและพื้นที่ว่างประมาณ 3 GB แล้วลองใหม่"
        val memoryText = memories.take(12).joinToString("\n")
        val system = buildString {
            append(cfg.systemPrompt)
            append("\nคุณชื่อ NEO เป็นผู้ช่วยส่วนตัวของผู้ใช้ ตอบภาษาไทยเป็นหลัก เว้นแต่ผู้ใช้ขอภาษาอื่น")
            append("\nคุณทำงานบนมือถือแบบ Local โดยไม่ต้องใช้ Cloud inference")
            if (memoryText.isNotBlank()) append("\n\nความจำที่เกี่ยวข้อง:\n$memoryText")
        }
        return try {
            val current = model ?: return "สมอง Local ยังไม่พร้อมครับ"
            val result = Llama.complete(
                model = current,
                prompt = message,
                systemPrompt = system,
                maxTokens = cfg.maxTokens.coerceIn(64, 900)
            )
            result.text.trim().ifBlank { "ผมยังคิดคำตอบไม่ออกครับ ลองถามใหม่อีกครั้ง" }
        } catch (_: Exception) {
            "สมอง Local มีปัญหาระหว่างประมวลผลครับ ลองใหม่อีกครั้ง"
        }
    }

    fun release() {
        model?.let { Llama.releaseModel(it) }
        model = null
    }
}
