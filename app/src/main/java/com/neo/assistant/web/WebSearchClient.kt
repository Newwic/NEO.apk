package com.neo.assistant.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WebSearchClient {
    data class Result(val title: String, val snippet: String, val url: String)
    data class Packet(val results: List<Result>, val elapsedMs: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun shouldSearch(query: String): Boolean {
        val t = query.lowercase().trim()
        val dynamic = listOf(
            "ค้นเว็บ", "ค้นหาเว็บ", "search web", "วันนี้", "ตอนนี้", "ล่าสุด", "ข่าว", "ราคา",
            "current", "latest", "today", "news", "update", "อัปเดต", "สด", "เท่าไหร่วันนี้",
            "อัตราแลกเปลี่ยน", "ค่าเงิน", "weather", "อากาศ", "หุ้น", "ทอง", "crypto", "bitcoin"
        )
        return dynamic.any { t.contains(it) }
    }

    /** Questions that are factual but not necessarily current. Used only as a fallback
     * when local reasoning cannot produce a useful answer. */
    fun canFallbackSearch(query: String): Boolean {
        val t = query.trim()
        if (t.length < 2) return false
        val personalOnly = listOf("จำอะไร", "ผมชื่อ", "ฉันชื่อ", "ผมชอบ", "ข้อมูลของผม", "เปิดแอป")
        return personalOnly.none { t.contains(it, ignoreCase = true) }
    }

    suspend fun search(query: String): Packet = coroutineScope {
        val started = System.currentTimeMillis()
        val ddg = async { searchDuckDuckGo(query) }
        val wiki = async { searchThaiWikipedia(query) }
        val merged = (ddg.await() + wiki.await()).distinctBy { it.url }.take(7)
        Packet(merged, System.currentTimeMillis() - started)
    }

    private suspend fun searchDuckDuckGo(query: String): List<Result> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder().url(url).header("User-Agent", "NEO-Android/1.0").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext emptyList()
                val json = JSONObject(res.body?.string().orEmpty())
                val out = mutableListOf<Result>()
                val abstract = json.optString("AbstractText")
                val abstractUrl = json.optString("AbstractURL")
                if (abstract.isNotBlank() && abstractUrl.isNotBlank()) out += Result(json.optString("Heading", "DuckDuckGo"), abstract, abstractUrl)
                collectTopics(json.optJSONArray("RelatedTopics"), out)
                out.take(4)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun collectTopics(array: JSONArray?, out: MutableList<Result>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val nested = item.optJSONArray("Topics")
            if (nested != null) collectTopics(nested, out) else {
                val text = item.optString("Text")
                val url = item.optString("FirstURL")
                if (text.isNotBlank() && url.isNotBlank()) out += Result(text.take(90), text, url)
            }
            if (out.size >= 4) return
        }
    }

    private suspend fun searchThaiWikipedia(query: String): List<Result> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://th.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&format=json&utf8=1&srlimit=4"
            val req = Request.Builder().url(url).header("User-Agent", "NEO-Android/1.0").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext emptyList()
                val root = JSONObject(res.body?.string().orEmpty())
                val arr = root.optJSONObject("query")?.optJSONArray("search") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val title = o.optString("title")
                        val snippet = o.optString("snippet").replace(Regex("<[^>]+>"), " ").replace("&quot;", "\"")
                        val page = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
                        add(Result(title, snippet, "https://th.wikipedia.org/wiki/$page"))
                    }
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
