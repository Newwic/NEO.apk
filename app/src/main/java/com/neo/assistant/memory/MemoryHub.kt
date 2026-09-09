package com.neo.assistant.memory

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Structured local memory router.
 * Supports multilingual text and a growing local memory store.
 */
class MemoryHub(private val dao: MemoryDao) {

    data class Packet(
        val memories: List<MemoryEntity>,
        val route: String,
        val elapsedMs: Long
    )

    suspend fun save(text: String, source: String = "user", destination: String = "brain") {
        val clean = text.trim()
        if (clean.isBlank()) return
        val category = classify(clean)
        dao.insert(
            MemoryEntity(
                text = clean,
                category = category,
                source = source,
                destination = destination,
                keywords = tokenize(clean).take(120).joinToString(" "),
                importance = importance(clean, category)
            )
        )
    }

    suspend fun retrieve(query: String, limit: Int = 16): Packet = coroutineScope {
        val started = System.currentTimeMillis()
        val category = classify(query)

        // Larger lanes keep retrieval useful as the database grows over time.
        val recentLane = async { dao.recent(300) }
        val categoryLane = async { dao.byCategory(category, 250) }
        val sourceLane = async { dao.bySource("user", 250) }

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
            route = "multilingual + recent + $category → memory-hub → brain",
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
        score += memory.accessCount.coerceAtMost(30)
        return score
    }

    private fun classify(text: String): String {
        val t = text.lowercase()
        return when {
            listOf("rpv", "สินค้า", "ลูกค้า", "บริษัท", "ขาย", "quotation", "purchase order", "business", "customer", "company", "sales", "empresa", "cliente", "entreprise", "client", "会社", "顧客", "公司", "客户").any { t.contains(it) } -> "work"
            listOf("code", "โค้ด", "android", "github", "program", "โปรแกรม", "debug", "coding", "python", "java", "kotlin", "程序", "代码", "プログラム", "コード").any { t.contains(it) } -> "coding"
            listOf("จำ", "ชอบ", "ไม่ชอบ", "ชื่อ", "อายุ", "เป้าหมาย", "ของผม", "remember", "my name", "i like", "i dislike", "my goal", "me llamo", "mi nombre", "j'aime", "je m'appelle", "ich heiße", "mein name", "私の名前", "覚えて", "我叫", "记住").any { t.contains(it) } -> "profile"
            listOf("งาน", "ทำ", "todo", "เตือน", "task", "โปรเจกต์", "project", "remind", "任务", "项目", "タスク", "プロジェクト").any { t.contains(it) } -> "task"
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
        val t = text.lowercase()
        if (listOf("สำคัญ", "จำไว้", "important", "remember this", "重要", "重要です").any { t.contains(it) }) value += 10
        return value.coerceIn(1, 100)
    }

    /** Unicode letters/numbers keeps Thai, English, Chinese, Japanese, Korean and most languages searchable. */
    private fun tokenize(text: String): Set<String> = text
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}_]+"), " ")
        .split(' ')
        .map { it.trim() }
        .filter { it.length >= 1 }
        .toSet()
}
