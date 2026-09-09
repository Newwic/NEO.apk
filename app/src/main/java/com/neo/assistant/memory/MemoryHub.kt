package com.neo.assistant.memory

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Structured local memory router.
 * Multiple retrieval lanes run in parallel, then merge/rank into one packet for the brain.
 */
class MemoryHub(private val dao: MemoryDao) {

    data class Packet(
        val memories: List<MemoryEntity>,
        val route: String,
        val elapsedMs: Long
    )

    suspend fun save(text: String, source: String = "user", destination: String = "brain") {
        val category = classify(text)
        dao.insert(
            MemoryEntity(
                text = text.trim(),
                category = category,
                source = source,
                destination = destination,
                keywords = tokenize(text).joinToString(" "),
                importance = importance(text, category)
            )
        )
    }

    suspend fun retrieve(query: String, limit: Int = 12): Packet = coroutineScope {
        val started = System.currentTimeMillis()
        val category = classify(query)

        // Three independent lanes fetch in parallel: recent, semantic category, and user-origin data.
        val recentLane = async { dao.recent(120) }
        val categoryLane = async { dao.byCategory(category, 100) }
        val sourceLane = async { dao.bySource("user", 100) }

        val queryTokens = tokenize(query)
        val merged = (recentLane.await() + categoryLane.await() + sourceLane.await())
            .distinctBy { it.id }
            .map { it to score(it, queryTokens, category) }
            .sortedWith(compareByDescending<Pair<MemoryEntity, Int>> { it.second }
                .thenByDescending { it.first.importance }
                .thenByDescending { it.first.updatedAt })
            .take(limit)
            .map { it.first }

        if (merged.isNotEmpty()) dao.markAccessed(merged.map { it.id })

        Packet(
            memories = merged,
            route = "user + recent + $category → memory-hub → brain",
            elapsedMs = System.currentTimeMillis() - started
        )
    }

    private fun score(memory: MemoryEntity, queryTokens: Set<String>, category: String): Int {
        val memoryTokens = tokenize(memory.text + " " + memory.keywords)
        val overlap = queryTokens.intersect(memoryTokens).size
        var score = overlap * 20
        if (memory.category == category) score += 30
        if (memory.source == "user") score += 10
        score += memory.importance / 5
        score += memory.accessCount.coerceAtMost(20)
        return score
    }

    private fun classify(text: String): String {
        val t = text.lowercase()
        return when {
            listOf("rpv", "สินค้า", "ลูกค้า", "บริษัท", "ขาย", "quotation", "po").any { t.contains(it) } -> "work"
            listOf("code", "โค้ด", "android", "github", "program", "โปรแกรม", "debug").any { t.contains(it) } -> "coding"
            listOf("จำ", "ชอบ", "ไม่ชอบ", "ชื่อ", "อายุ", "เป้าหมาย", "ของผม").any { t.contains(it) } -> "profile"
            listOf("งาน", "ทำ", "todo", "เตือน", "task", "โปรเจกต์").any { t.contains(it) } -> "task"
            else -> "general"
        }
    }

    private fun importance(text: String, category: String): Int {
        var value = when (category) {
            "profile" -> 90
            "work" -> 80
            "coding" -> 75
            "task" -> 85
            else -> 55
        }
        if (text.contains("สำคัญ") || text.contains("จำไว้")) value += 10
        return value.coerceIn(1, 100)
    }

    private fun tokenize(text: String): Set<String> = text
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}_]+"), " ")
        .split(' ')
        .map { it.trim() }
        .filter { it.length >= 2 }
        .toSet()
}
