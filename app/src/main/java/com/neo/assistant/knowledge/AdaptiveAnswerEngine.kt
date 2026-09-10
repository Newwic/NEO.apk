package com.neo.assistant.knowledge

import com.neo.assistant.web.WebSearchClient

/**
 * Generic answer recovery layer for questions that are not hard-coded.
 *
 * Flow:
 * 1) Try previously learned local knowledge first.
 * 2) If nothing relevant is cached, search the web.
 * 3) Build a short grounded answer from search snippets.
 * 4) Save the answer + sources locally so the same/similar question is faster next time.
 *
 * This layer deliberately does not invent facts. If it cannot obtain useful evidence,
 * it returns null and lets the caller report that evidence was insufficient.
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

        // Fast path: use locally learned answer before touching the network.
        val cached = knowledgeHub.retrieve(q, limit = 4)
        val learnedBlocks = cached.blocks.filter {
            it.contains("| learned-answer]") || it.contains("| learned-web]")
        }
        cachedAnswer(q, learnedBlocks, cached.sources)?.let { return it }

        // No useful cached answer: search externally.
        val packet = runCatching { webSearch.search(q) }.getOrNull() ?: return null
        val results = packet.results
            .filter { it.title.isNotBlank() && it.snippet.isNotBlank() }
            .take(4)
        if (results.isEmpty()) return null

        val answerText = groundedSummary(results)
        if (answerText.isBlank()) return null

        val sourceText = results.joinToString("\n") { "${it.title} — ${it.url}" }
        val cacheBody = buildString {
            append("คำถาม: ").append(q).append('\n')
            append("คำตอบ: ").append(answerText).append('\n')
            append("แหล่งข้อมูล:\n").append(sourceText)
        }

        // Save both a Q/A cache and raw web evidence for later retrieval.
        runCatching {
            knowledgeHub.importText(
                title = "Q: $q",
                text = cacheBody,
                sourceUri = results.first().url,
                source = "learned-answer"
            )
        }
        results.take(3).forEach { r ->
            runCatching { knowledgeHub.learnWeb(r.title, r.snippet, r.url) }
        }

        return Answer(
            text = answerText,
            route = "WEB → learned-answer → local knowledge",
            learned = true,
            sources = results.map { it.url }
        )
    }

    private fun cachedAnswer(query: String, blocks: List<String>, sources: List<String>): Answer? {
        if (blocks.isEmpty()) return null
        // Prefer an exact learned Q/A entry when available.
        val exact = blocks.firstOrNull { it.contains("Q: $query", ignoreCase = true) }
            ?: blocks.firstOrNull { it.contains("คำถาม: $query", ignoreCase = true) }
        if (exact != null) {
            val body = exact.substringAfter('\n', "")
            val answer = body.substringAfter("คำตอบ:", "")
                .substringBefore("แหล่งข้อมูล:")
                .trim()
            if (answer.length >= 8) {
                return Answer(answer, "LOCAL CACHE", learned = false, sources = sources.take(3))
            }
        }

        // For similar questions, only use a sufficiently informative learned block.
        val best = blocks.firstOrNull() ?: return null
        val body = best.substringAfter('\n', "").trim()
        val extracted = body.substringAfter("คำตอบ:", body)
            .substringBefore("แหล่งข้อมูล:")
            .trim()
        if (extracted.length < 24) return null
        return Answer(extracted.take(900), "LOCAL RAG CACHE", learned = false, sources = sources.take(3))
    }

    private fun groundedSummary(results: List<WebSearchClient.Result>): String {
        // Keep only evidence returned by providers. No model-generated facts are added here.
        val unique = results
            .map { clean(it.snippet) }
            .filter { it.length >= 12 }
            .distinct()
            .take(3)
        if (unique.isEmpty()) return ""
        return when (unique.size) {
            1 -> unique.first().take(700)
            else -> unique.mapIndexed { index, text -> "${index + 1}. ${text.take(500)}" }.joinToString("\n")
        }
    }

    private fun clean(text: String): String = text
        .replace(Regex("<[^>]+>"), " ")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
}
