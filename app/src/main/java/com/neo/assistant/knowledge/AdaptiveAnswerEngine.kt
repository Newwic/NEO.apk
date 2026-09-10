package com.neo.assistant.knowledge

import com.neo.assistant.web.WebSearchClient

/**
 * Evidence-first recovery for questions not covered by local tools.
 * Never returns unrelated search snippets as an answer.
 */
class AdaptiveAnswerEngine(
    private val knowledgeHub: KnowledgeHub,
    private val webSearch: WebSearchClient = WebSearchClient()
) {
    data class Answer(
        val text: String,
        val route: String,
        val learned: Boolean,
        val sources: List<String>
    )

    suspend fun answer(query: String): Answer? {
        val q = query.trim()
        if (q.length < 2 || !webSearch.canFallbackSearch(q)) return null

        // Meta/conversation/ability prompts belong to the local model, not a web search.
        if (isConversationalOrAbility(q)) return null

        // Exact learned Q/A first. Do not reuse a merely "similar" block as an answer.
        val cached = knowledgeHub.retrieve(q, limit = 6)
        val learnedBlocks = cached.blocks.filter {
            it.contains("| learned-answer]") || it.contains("| learned-web]")
        }
        exactCachedAnswer(q, learnedBlocks, cached.sources)?.let { return it }

        val packet = runCatching { webSearch.search(q) }.getOrNull() ?: return null
        val candidates = packet.results
            .filter { it.title.isNotBlank() && it.snippet.isNotBlank() }
            .distinctBy { it.url }

        // Critical anti-hallucination gate: only evidence that actually overlaps the question.
        val relevant = candidates
            .map { it to relevance(q, "${it.title} ${it.snippet}") }
            .filter { (_, score) -> score >= 0.16 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(3)

        if (relevant.isEmpty()) return null

        val answerText = groundedSummary(relevant)
        if (answerText.isBlank()) return null

        val sourceText = relevant.joinToString("\n") { "${it.title} — ${it.url}" }
        val cacheBody = buildString {
            append("คำถาม: ").append(q).append('\n')
            append("คำตอบ: ").append(answerText).append('\n')
            append("แหล่งข้อมูล:\n").append(sourceText)
        }

        runCatching {
            knowledgeHub.importText(
                title = "Q: $q",
                text = cacheBody,
                sourceUri = relevant.first().url,
                source = "learned-answer"
            )
        }
        relevant.forEach { r -> runCatching { knowledgeHub.learnWeb(r.title, r.snippet, r.url) } }

        return Answer(
            text = answerText,
            route = "WEB → verified evidence → local cache",
            learned = true,
            sources = relevant.map { it.url }
        )
    }

    private fun exactCachedAnswer(query: String, blocks: List<String>, sources: List<String>): Answer? {
        val exact = blocks.firstOrNull { it.contains("Q: $query", ignoreCase = true) }
            ?: blocks.firstOrNull { it.contains("คำถาม: $query", ignoreCase = true) }
            ?: return null
        val answer = exact.substringAfter("คำตอบ:", "")
            .substringBefore("แหล่งข้อมูล:")
            .trim()
        if (answer.length < 4) return null
        return Answer(answer.take(900), "LOCAL EXACT CACHE", learned = false, sources = sources.take(3))
    }

    private fun isConversationalOrAbility(query: String): Boolean {
        val q = query.lowercase().replace(Regex("\\s+"), "")
        val patterns = listOf(
            "ทำได้ไหม", "ได้ไหม", "เขียนcode", "เขียนโค้ด", "ช่วยได้", "ช่วยอะไร",
            "ทำอะไรได้", "เก่งอะไร", "ตอบไม่ตรง", "ตอบมั่ว", "เข้าใจไหม", "รู้ไหม",
            "canyou", "areyou", "doyou"
        )
        // Questions such as "รู้ไหมว่า X คืออะไร" can still be factual; only short/meta prompts are local.
        return patterns.any { q.contains(it) } && q.length < 55
    }

    private fun relevance(query: String, evidence: String): Double {
        val q = normalize(query)
        val e = normalize(evidence)
        if (q.isBlank() || e.isBlank()) return 0.0

        val latin = Regex("[a-z0-9]{3,}").findAll(q).map { it.value }.toSet()
        if (latin.isNotEmpty()) {
            val hits = latin.count { e.contains(it) }
            if (hits > 0) return (0.55 + 0.45 * hits.toDouble() / latin.size).coerceAtMost(1.0)
        }

        val q3 = trigrams(q)
        val e3 = trigrams(e)
        if (q3.isEmpty() || e3.isEmpty()) return 0.0
        val overlap = q3.count { it in e3 }
        // Coverage of query trigrams is more useful than Jaccard because evidence is much longer.
        return overlap.toDouble() / q3.size
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace(Regex("https?://\\S+"), " ")
        .replace(Regex("[^a-z0-9ก-๙]+"), "")
        .replace("อะไร", "")
        .replace("คือ", "")
        .replace("เท่าไหร่", "")
        .replace("เท่ากับ", "")
        .replace("ขอ", "")
        .replace("หน่อย", "")
        .trim()

    private fun trigrams(s: String): Set<String> {
        if (s.length < 3) return if (s.isBlank()) emptySet() else setOf(s)
        return (0..s.length - 3).map { s.substring(it, it + 3) }.toSet()
    }

    private fun groundedSummary(results: List<WebSearchClient.Result>): String {
        val unique = results.map { clean(it.snippet) }
            .filter { it.length >= 12 }
            .distinct()
            .take(2)
        if (unique.isEmpty()) return ""
        // One strong result is preferable to dumping several unrelated search fragments.
        return unique.first().take(650)
    }

    private fun clean(text: String): String = text
        .replace(Regex("<[^>]+>"), " ")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
}
