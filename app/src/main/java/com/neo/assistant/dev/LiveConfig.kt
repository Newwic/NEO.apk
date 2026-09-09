package com.neo.assistant.dev

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class NeoLiveConfig(
    val revision: Int = 0,
    val assistantName: String = "NEO",
    val mode: String = "auto",
    val fastModel: String = "neo-3b-q4",
    val smartModel: String = "neo-7b-q4",
    val temperature: Double = 0.7,
    val maxTokens: Int = 700,
    val systemPrompt: String = "คุณคือ NEO ผู้ช่วย AI ส่วนตัวที่รันแบบ Local",
    val codingKeywords: List<String> = listOf("เขียนโค้ด", "แก้โค้ด", "build", "test", "error"),
    val liveReloadSeconds: Long = 2
)

class LiveConfigClient(private var workerUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    fun setWorkerUrl(url: String) { workerUrl = url.trim().removeSuffix("/") }

    suspend fun fetch(): NeoLiveConfig? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$workerUrl/neo-config").get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val j = JSONObject(res.body?.string().orEmpty())
                val keywordsJson = j.optJSONArray("coding_keywords")
                val keywords = buildList {
                    if (keywordsJson != null) for (i in 0 until keywordsJson.length()) add(keywordsJson.optString(i))
                }.ifEmpty { listOf("เขียนโค้ด", "แก้โค้ด", "build", "test", "error") }
                NeoLiveConfig(
                    revision = j.optInt("revision", 0),
                    assistantName = j.optString("assistant_name", "NEO"),
                    mode = j.optString("mode", "auto"),
                    fastModel = j.optString("fast_model", "neo-3b-q4"),
                    smartModel = j.optString("smart_model", "neo-7b-q4"),
                    temperature = j.optDouble("temperature", 0.7),
                    maxTokens = j.optInt("max_tokens", 700),
                    systemPrompt = j.optString("system_prompt", "คุณคือ NEO ผู้ช่วย AI ส่วนตัวที่รันแบบ Local"),
                    codingKeywords = keywords,
                    liveReloadSeconds = j.optLong("live_reload_seconds", 2).coerceIn(1, 60)
                )
            }
        } catch (_: Exception) { null }
    }
}
