package com.neo.assistant.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
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
        // Time/date are local facts and must never go to web search.
        val localOnly = listOf("กี่โมง", "ตอนนี้เวลา", "เวลาตอนนี้", "วันนี้วันอะไร", "พรุ่งนี้วันอะไร", "วันที่เท่าไหร่")
        if (localOnly.any { t.contains(it) }) return false

        val dynamic = listOf(
            "ค้นเว็บ", "ค้นหาเว็บ", "search web", "ล่าสุด", "ข่าว", "ราคา", "current", "latest", "today",
            "news", "update", "อัปเดต", "สด", "อัตราแลกเปลี่ยน", "ค่าเงิน", "แลกเงิน", "exchange rate", "fx",
            "dollar", "ดอลลาร์", "usd", "บาทไทย", "เงินบาท", "thb", "euro", "ยูโร", "eur",
            "yen", "เยน", "jpy", "pound", "ปอนด์", "gbp", "weather", "อากาศ", "หุ้น", "ทอง", "crypto", "bitcoin"
        )
        return dynamic.any { t.contains(it) }
    }

    fun canFallbackSearch(query: String): Boolean {
        val t = query.trim()
        if (t.length < 2) return false
        val localOnly = listOf("กี่โมง", "ตอนนี้เวลา", "เวลาตอนนี้", "วันนี้วันอะไร", "พรุ่งนี้วันอะไร", "วันที่เท่าไหร่")
        if (localOnly.any { t.contains(it, ignoreCase = true) }) return false
        val personalOnly = listOf("จำอะไร", "ผมชื่อ", "ฉันชื่อ", "ผมชอบ", "ข้อมูลของผม", "เปิดแอป")
        return personalOnly.none { t.contains(it, ignoreCase = true) }
    }

    suspend fun search(query: String): Packet = coroutineScope {
        val started = System.currentTimeMillis()
        val t = query.lowercase()
        val isNews = listOf("ข่าว", "news", "ล่าสุด", "update", "อัปเดต").any { t.contains(it) }
        val isCurrency = listOf("dollar", "ดอลลาร์", "usd", "euro", "ยูโร", "eur", "yen", "เยน", "jpy", "pound", "ปอนด์", "gbp", "บาท", "thb", "ค่าเงิน", "อัตราแลกเปลี่ยน").any { t.contains(it) }

        val currency = async { if (isCurrency) searchCurrency(query) else emptyList() }
        val news = async { if (isNews) searchGoogleNews(query) else emptyList() }
        val ddg = async { if (!isCurrency) searchDuckDuckGo(query) else emptyList() }
        val wiki = async { if (!isNews && !isCurrency) searchThaiWikipedia(query) else emptyList() }

        val merged = (currency.await() + news.await() + ddg.await() + wiki.await())
            .filter { it.title.isNotBlank() && it.snippet.isNotBlank() }
            .distinctBy { it.title.lowercase() }
            .take(5)
        Packet(merged, System.currentTimeMillis() - started)
    }

    private suspend fun searchCurrency(query: String): List<Result> = withContext(Dispatchers.IO) {
        try {
            val t = query.lowercase()
            val base = when {
                listOf("euro", "ยูโร", "eur").any { t.contains(it) } -> "EUR"
                listOf("yen", "เยน", "jpy").any { t.contains(it) } -> "JPY"
                listOf("pound", "ปอนด์", "gbp").any { t.contains(it) } -> "GBP"
                else -> "USD"
            }
            val url = "https://open.er-api.com/v6/latest/$base"
            val req = Request.Builder().url(url).header("User-Agent", "NEO-Android/1.0").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext emptyList()
                val root = JSONObject(res.body?.string().orEmpty())
                val rate = root.optJSONObject("rates")?.optDouble("THB", Double.NaN) ?: Double.NaN
                if (rate.isNaN()) return@withContext emptyList()
                val formatted = String.format("%.2f", rate)
                listOf(Result("$base/THB", "1 $base ≈ $formatted บาท", url))
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun searchGoogleNews(query: String): List<Result> = withContext(Dispatchers.IO) {
        try {
            val cleaned = query.replace("มีอะไรบ้าง", "").replace("ข่าว", "").trim().ifBlank { "Thailand" }
            val q = URLEncoder.encode(cleaned, "UTF-8")
            val url = "https://news.google.com/rss/search?q=$q&hl=th&gl=TH&ceid=TH:th"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 NEO-Android/1.0").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext emptyList()
                val xml = res.body?.string().orEmpty()
                Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(5).mapNotNull { m ->
                    val item = m.groupValues[1]
                    val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let(::decodeXml).orEmpty()
                    val link = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let(::decodeXml).orEmpty()
                    val pub = Regex("<pubDate>(.*?)</pubDate>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1).orEmpty()
                    if (title.isBlank()) null else Result(title, if (pub.isBlank()) title else "$title • $pub", link)
                }.toList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun decodeXml(s: String): String = s
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

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
                if (abstract.isNotBlank() && abstractUrl.isNotBlank()) out += Result(json.optString("Heading", "Web"), abstract, abstractUrl)
                collectTopics(json.optJSONArray("RelatedTopics"), out)
                out.take(3)
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
                if (text.isNotBlank() && url.isNotBlank()) out += Result(text.take(100), text, url)
            }
            if (out.size >= 3) return
        }
    }

    private suspend fun searchThaiWikipedia(query: String): List<Result> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://th.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&format=json&utf8=1&srlimit=3"
            val req = Request.Builder().url(url).header("User-Agent", "NEO-Android/1.0").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext emptyList()
                val root = JSONObject(res.body?.string().orEmpty())
                val arr = root.optJSONObject("query")?.optJSONArray("search") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val title = o.optString("title")
                        val snippet = o.optString("snippet").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                        add(Result(title, snippet, "https://th.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"))
                    }
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
