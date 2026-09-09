package com.neo.assistant.knowledge

import com.neo.assistant.data.AppDataDao
import com.neo.assistant.data.ChatEntity
import com.neo.assistant.data.KnowledgeEntity
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeHubRelevanceTest {
    private val dao = object : AppDataDao {
        override suspend fun insertChat(item: ChatEntity) {}
        override suspend fun insertKnowledge(item: KnowledgeEntity) {}
        override suspend fun recentChats(limit: Int): List<ChatEntity> = emptyList()
        override suspend fun knowledge(limit: Int): List<KnowledgeEntity> = emptyList()
        override suspend fun knowledgeCount(): Int = 0
    }

    private val hub = KnowledgeHub(dao)

    @Test fun singleWeakBodyHitIsRejected() {
        val item = KnowledgeEntity(
            title = "ประวัติบุคคล",
            content = "บทความนี้กล่าวถึงคำว่า computer เพียงครั้งเดียวและไม่เกี่ยวกับส่วนประกอบคอมพิวเตอร์",
            source = "learned-web"
        )
        val score = hub.score(item, setOf("computer", "components"))
        assertTrue(score < KnowledgeHub.MIN_RELEVANCE_SCORE)
    }

    @Test fun titleHitIsAccepted() {
        val item = KnowledgeEntity(
            title = "Computer components",
            content = "CPU RAM SSD GPU PSU",
            source = "local-file"
        )
        val score = hub.score(item, setOf("computer", "components"))
        assertTrue(score >= KnowledgeHub.MIN_RELEVANCE_SCORE)
    }

    @Test fun multipleBodyHitsAreAccepted() {
        val item = KnowledgeEntity(
            title = "Hardware basics",
            content = "computer components include cpu ram storage and gpu",
            source = "local-file"
        )
        val score = hub.score(item, setOf("computer", "components"))
        assertTrue(score >= KnowledgeHub.MIN_RELEVANCE_SCORE)
    }
}
