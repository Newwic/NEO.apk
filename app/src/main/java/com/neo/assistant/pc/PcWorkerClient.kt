package com.neo.assistant.pc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PcWorkerClient(private val baseUrl: String) {
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).build()

    suspend fun runTask(task: String): String = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("task", task).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("${baseUrl.removeSuffix("/")}/task").post(body).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext "PC Worker error ${res.code}"
                val json = JSONObject(res.body?.string().orEmpty())
                json.optString("message", json.optString("result", json.toString()))
            }
        } catch (_: Exception) {
            "ยังเชื่อมต่อ NEO PC Worker ไม่ได้ครับ ตรวจ IP ของคอมและให้มือถืออยู่ Wi‑Fi เดียวกัน"
        }
    }
}
