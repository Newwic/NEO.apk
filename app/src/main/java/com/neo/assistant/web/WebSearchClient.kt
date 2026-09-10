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
import java.util.Locale
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
        val localOnly = listOf(
            "กี่โมง", "ตอนนี้เวลา", "เวลาตอนนี้", "วันนี้วันอะไร", "พรุ่งนี้วันอะไร", "เมื่อวานวันอะไร", "วันที่เท่าไหร่",
            "นายเป็นใคร", "คุณเป็นใคร", "นายชื่อ", "คุณชื่อ", "อายุเท่า", "นายชอบอะไร", "คุณชอบอะไร",
            "นายเก่งอะไร", "ทำอะไรได้บ้าง", "ช่วยอะไรได้บ้าง", "ทำไรอยู่", "ทำอะไรอยู่"
        )
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
        val t = query.lowercase().trim()
        if (t.length < 2) return false
        val neverWeb = listOf(
            "กี่โมง", "ตอนนี้เวลา", "เวลาตอนนี้", "วันนี้วันอะไร", "พรุ่งนี้วันอะไร", "เมื่อวานวันอะไร", "วันที่เท่าไหร่",
            "นายเป็นใคร", "คุณเป็นใคร", "นายชื่อ", "คุณชื่อ", "ชื่ออะไร", "อายุเท่า", "นายชอบอะไร", "คุณชอบอะไร",
            "นายเก่งอะไร", "ทำอะไรได้บ้าง", "ช่วยอะไรได้บ้าง", "ทำไรอยู่", "ทำอะไรอยู่",
            "จำอะไร", "ผมชื่อ", "ฉันชื่อ", "ผมชอบ", "ข้อมูลของผม", "เปิดแอป"
        )
        return neverWeb.none { t.contains(it) }
    }

    suspend fun search(query: String): Packet = coroutineScope {
        val started = System.currentTimeMillis()
        val t = query.lowercase()
        val isNews = listOf("ข่าว", "news", "ล่าสุด", "update", "อัปเดต").any { t.contains(it) }
        val isCurrency = listOf(
            "dollar", "ดอลลาร์", "usd", "euro", "ยูโร", "eur", "yen", "เยน", "jpy",
            "pound", "ปอนด์", "gbp", "บาท", "thb", "ค่าเงิน", "อัตราแลกเปลี่ยน"
        ).any { t.contains(it) }

        val currency = async { if (isCurrency) searchCurrency(query) else emptyList() }
        val news = async { if (isNews) searchGoogleNews(query) else emptyList() }
        val ddg = async { if (!isCurrency && !isNews) searchDuckDuckGo(query) else emptyList() }
        val wiki = async { if (!isNews && !isCurrency) searchThaiWikipedia(query) else emptyList() }

        val raw = (currency.await() + news.await() + ddg.await() + wiki.await())
            .filter { it.title.isNotBlank() && it.snippet.isNotBlank() }
            .distinctBy { (it.title + it.url).lowercase() }

        // Structured currency data is already intent-specific. Everything else must prove
        // relevance before NEO is allowed to see or remember it.
        val ranked = if (isCurrency) {
            raw.take(5)
        } else {
            raw.map { it to relevanceScore(query, it) }
                .filter { (_, score) -> score >= minimumRelevance(query) }
                .sortedByDescending { it.second }
                .map { it.first }
                .take(5)
        }

        Packet(ranked, System.currentTimeMillis() - started)
    }

    internal fun relevanceScore(query: String, result: Result): Double {
        val q = normalize(query)
        val hay = normalize(result.title + " " + result.snippet)
        if (q.isBlank() || hay.isBlank()) return 0.0
        if (hay.contains(q)) return 1.0

        val keys = keywords(query)
        if (keys.isEmpty()) return 0.0
        var matched = 0.0
        keys.forEach { key ->
            if (hay.contains(key)) matched += if (key.length >= 5) 1.25 else 1.0
        }
        val denom = keys.sumOf { if (it.length >= 5) 1.25 else 1.0 }
        var score = if (denom > 0) matched / denom else 0.0

        val title = normalize(result.title)
        if (keys.any { it.length >= 2 && title.contains(it) }) score += 0.15
        return score.coerceAtMost(1.0)
    }

    private fun minimumRelevance(query: String): Double {
        val keys = keywords(query)
        return when {
            keys.size <= 1 -> 0.75
            keys.size == 2 -> 0.50
            else -> 0.42
        }
    }

    internal fun keywords(query: String): List<String> {
        var s = query.lowercase(Locale.ROOT)
        val noisePhrases = listOf(
            "ช่วยค้นให้หน่อย", "ช่วยค้น", "ค้นหาเว็บ", "ค้นเว็บ", "search web",
            "คืออะไร", "เป็นอะไร", "ทำงานยังไง", "ทำงานอย่างไร", "มีส่วนประกอบอะไร", "มีองค์ประกอบอะไร",
            "ประกอบจากอะไร", "มีอะไรบ้าง", "ได้ไหม", "ได้มั้ย", "หรือไม่", "หน่อย", "ครับ", "ค่ะ", "คะ",
            "ตอนนี้", "วันนี้", "ล่าสุด", "อัปเดต", "update", "current", "latest", "today"
        )
        noisePhrases.forEach { s = s.replace(it, " ") }
        s = s.replace(Regex("[^a-z0-9ก-๙]+"), " ").trim()
        if (s.isBlank()) return emptyList()

        val stop = setOf(
            "อะไร", "ทำไม", "ยังไง", "อย่างไร", "เท่าไร", "เท่าไหร่", "กี่", "มี", "คือ", "เป็น", "และ", "หรือ",
            "ของ", "ให้", "ผม", "ฉัน", "เรา", "นาย", "คุณ", "the", "a", "an", "is", "are", "what", "how"
        )
        return s.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stop }
            .distinct()
            .take(8)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9ก-๙]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

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
                listOf(Result("$base/THB", "1 $base ≈ ${String.format(Locale.US, "%.2f", rate)} บาท", url))
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun searchGoogleNews(query: String): List<Result> = withContext(Dispatchers.IO) {
        try {
            val cleaned = query
                .replace("มีอะไรบ้าง", "")
                .replace("ข่าว", "")
                .replace("ล่าสุด", "")
                .trim()
                .ifBlank { "Thailand" }
            val q = URLEncoder.encode(cleaned, "UTF-8")
            val url = "https://news.google.com/rss/search?q=$q&hl=th&gl=TH&ceid=TH:th"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 NEO-Android/1.0").build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext emptyList()
                val xml = res.body?.string().orEmpty()
                Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(xml)
                    .take(8)
                    .mapNotNull { m ->
                        val item = m.groupValues[1]
                        val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                            .find(item)?.groupValues?.get(1)?.let(::decodeXml).orEmpty()
                        val link = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
                            .find(item)?.groupValues?.get(1)?.let(::decodeXml).orEmpty()
                        if (title.isBlank()) null else Result(title, title, link)
                    }.toList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun decodeXml(s: String): String = s
        .replace("<![CDATA[", "")
        .replace("]]>", "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private suspend fun searchDuckDuckGo(query: String): List<Result> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1")
                .header("User-Agent", "NEO-Android/1.0")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext emptyList()
                val json = JSONObject(res.body?.string().orEmpty())
                val out = mutableListOf<Result>()
                val abstract = json.optString("AbstractText")
                val abstractUrl = json.optString("AbstractURL")
                if (abstract.isNotBlank() && abstractUrl.isNotBlank()) {
                    out += Result(json.optString("Heading", "Web"), abstract, abstractUrl)
                }
                collectTopics(json.optJSONArray("RelatedTopics"), out)
                out.take(6)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun collectTopics(array: JSONArray?, out: MutableList<Result>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val nested = item.optJSONArray("Topics")
            if (nested != null) {
                collectTopics(nested, out)
            } else {
                val text = item.optString("Text")
                val url = item.optString("FirstURL")
                if (text.isNotBlank() && url.isNotBlank()) out += Result(text.take(100), text, url)
            }
            if (out.size >= 6) return
        }
    }

    private suspend fun searchThaiWikipedia(query: String): List<Result> = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://th.wikipedia.org/w/api.php?action=query&list=search&srsearch=$q&format=json&utf8=1&srlimit=5")
                .header("User-Agent", "NEO-Android/1.0")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext emptyList()
                val arr = JSONObject(res.body?.string().orEmpty())
                    .optJSONObject("query")?.optJSONArray("search") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val title = o.optString("title")
                        val snippet = o.optString("snippet")
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        if (title.isNotBlank() && snippet.isNotBlank()) {
                            add(Result(title, snippet, "https://th.wikipedia.org/wiki/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8")}"))
                        }
                    }
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
