package com.neo.assistant.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchClientTest {
    private val client = WebSearchClient()

    @Test fun currentInformationUsesWeb() {
        listOf(
            "ราคาทองตอนนี้",
            "ข่าว AMD ล่าสุด",
            "อากาศวันนี้",
            "ค่าเงิน USD THB ตอนนี้",
            "bitcoin ล่าสุด"
        ).forEach { q -> assertTrue("Expected web routing: $q", client.shouldSearch(q)) }
    }

    @Test fun stableKnowledgeDoesNotForceWeb() {
        listOf(
            "RAM มีส่วนประกอบอะไร",
            "GPU ประกอบจากอะไร",
            "แอร์ทำงานยังไง",
            "YouTube คืออะไร",
            "เขียน Kotlin ได้ไหม",
            "1 ชั่วโมงมีกี่นาที"
        ).forEach { q -> assertFalse("Stable knowledge should be local-first: $q", client.shouldSearch(q)) }
    }

    @Test fun personalAndConversationQuestionsNeverFallbackToWeb() {
        listOf(
            "นายเป็นใคร",
            "นายชื่ออะไร",
            "ผมชื่ออะไร",
            "จำอะไรเกี่ยวกับผมได้บ้าง",
            "ทำอะไรอยู่"
        ).forEach { q -> assertFalse("Must not leak personal/conversation query to web: $q", client.canFallbackSearch(q)) }
    }

    @Test fun knowledgeQuestionsMayFallbackIfLocalFails() {
        listOf(
            "RAM ทำงานอย่างไร",
            "GPU คืออะไร",
            "เครื่องปรับอากาศทำงานอย่างไร",
            "HTTP คืออะไร"
        ).forEach { q -> assertTrue("Knowledge question should permit verified fallback: $q", client.canFallbackSearch(q)) }
    }

    @Test fun irrelevantWebResultsAreScoredLow() {
        val wrongMusic = WebSearchClient.Result(
            title = "เพลงฮิตประจำสัปดาห์",
            snippet = "รวมเพลงและศิลปินยอดนิยม พร้อมเนื้อเพลงใหม่",
            url = "https://example.com/music"
        )
        val rightCode = WebSearchClient.Result(
            title = "Kotlin programming language",
            snippet = "Kotlin is a programming language used to write Android applications and other software.",
            url = "https://example.com/kotlin"
        )
        assertTrue(client.relevanceScore("เขียน Kotlin ได้ไหม", wrongMusic) < 0.5)
        assertTrue(client.relevanceScore("เขียน Kotlin ได้ไหม", rightCode) >= 0.5)
    }

    @Test fun topicMismatchCannotLookRelevantJustBecauseQuestionWordsMatch() {
        val wrong = WebSearchClient.Result(
            title = "ดาราคนนี้คือใคร",
            snippet = "ประวัติและผลงานของนักแสดงชื่อดัง",
            url = "https://example.com/actor"
        )
        val right = WebSearchClient.Result(
            title = "GPU",
            snippet = "GPU หรือหน่วยประมวลผลกราฟิกประกอบด้วยหน่วยคำนวณ หน่วยความจำ และวงจรควบคุม",
            url = "https://example.com/gpu"
        )
        assertTrue(client.relevanceScore("GPU คืออะไร", wrong) < 0.5)
        assertTrue(client.relevanceScore("GPU คืออะไร", right) >= 0.5)
    }
}
