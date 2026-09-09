package com.neo.assistant.knowledge

import com.neo.assistant.data.AppDataDao
import com.neo.assistant.data.KnowledgeEntity

class KnowledgeHub(private val dao: AppDataDao) {
    data class Packet(
        val blocks: List<String>,
        val sources: List<String>,
        val route: String,
        val elapsedMs: Long
    )

    suspend fun importText(title: String, text: String, sourceUri: String = "", source: String = "local-file"): Int {
        val clean = text.trim()
        if (clean.isBlank()) return 0
        val chunks = chunk(clean, 1800)
        chunks.forEachIndexed { index, part ->
            dao.insertKnowledge(
                KnowledgeEntity(
                    title = if (chunks.size == 1) title else "$title • ${index + 1}/${chunks.size}",
                    content = part,
                    source = source,
                    sourceUri = sourceUri,
                    keywords = tokenize(title + " " + part).take(140).joinToString(" ")
                )
            )
        }
        return chunks.size
    }

    suspend fun learnWeb(title: String, text: String, url: String): Int =
        importText(title = title, text = text, sourceUri = url, source = "learned-web")

    suspend fun retrieve(query: String, limit: Int = 8): Packet {
        val started = System.currentTimeMillis()
        val q = tokenize(query)
        // Scan a much larger local pool so the store can keep growing for a long time.
        val ranked = dao.knowledge(3000)
            .map { item -> item to score(item, q) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

        return Packet(
            blocks = ranked.map { "[KNOWLEDGE: ${it.title} | ${it.source}]\n${it.content}" },
            sources = ranked.map { if (it.sourceUri.isBlank()) it.title else "${it.title} • ${it.sourceUri}" },
            route = "multilingual-local-knowledge → rag → brain",
            elapsedMs = System.currentTimeMillis() - started
        )
    }

    private fun score(item: KnowledgeEntity, query: Set<String>): Int {
        if (query.isEmpty()) return 0
        val title = tokenize(item.title)
        val body = tokenize(item.content + " " + item.keywords)
        var score = query.intersect(title).size * 35 + query.intersect(body).size * 10
        if (item.source == "learned-web") score += 3
        return score
    }

    private fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var pos = 0
        while (pos < text.length) {
            var end = (pos + max).coerceAtMost(text.length)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end)
                val space = text.lastIndexOf(' ', end)
                val cut = maxOf(newline, space)
                if (cut > pos + max / 2) end = cut
            }
            out += text.substring(pos, end).trim()
            pos = end
        }
        return out.filter { it.isNotBlank() }
    }

    /** Unicode tokenizer: Thai/English/CJK/Korean/European scripts are all retained. */
    private fun tokenize(text: String): Set<String> = text
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}_]+"), " ")
        .split(' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}
