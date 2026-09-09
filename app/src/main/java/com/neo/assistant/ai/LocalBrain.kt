package com.neo.assistant.ai

import com.neo.assistant.dev.NeoLiveConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LocalBrain(private var baseUrl: String = "http://127.0.0.1:8080") {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun setBaseUrl(url: String) { baseUrl = url.trim().removeSuffix("/") }

    suspend fun generate(message: String, memories: List<String>, cfg: NeoLiveConfig): String = withContext(Dispatchers.IO) {
        val memoryText = memories.take(12).joinToString("\n")
        val model = when (cfg.mode.lowercase()) {
            "fast" -> cfg.fastModel
            "smart" -> cfg.smartModel
            else -> if (looksHard(message)) cfg.smartModel else cfg.fastModel
        }
        val system = "${cfg.systemPrompt}\n\nความจำที่เกี่ยวข้อง:\n$memoryText"
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", cfg.temperature)
            put("max_tokens", cfg.maxTokens)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", message))
            })
        }
        try {
            val req = Request.Builder().url("$baseUrl/v1/chat/completions")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext "สมอง Local ตอบกลับผิดพลาด (${res.code})"
                JSONObject(res.body?.string().orEmpty()).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim()
            }
        } catch (_: Exception) {
            "ยังเชื่อมต่อสมอง Local บนมือถือไม่ได้ครับ ตรวจว่า llama.cpp server ทำงานที่ $baseUrl"
        }
    }

    private fun looksHard(text: String): Boolean {
        val t = text.lowercase()
        return t.length > 500 || listOf("วิเคราะห์", "เขียนโค้ด", "แก้โค้ด", "debug", "error", "วางแผน", "โปรเจกต์").any { t.contains(it) }
    }
}
