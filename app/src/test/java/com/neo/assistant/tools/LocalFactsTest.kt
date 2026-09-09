package com.neo.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFactsTest {
    @Test fun kgToGramWorks() {
        assertEquals("1 กิโลกรัม = 1,000 กรัมครับ", LocalFacts.answer("1 กิโลเท่ากับกี่กรัม"))
        assertEquals("2.5 กิโลกรัม = 2,500 กรัมครับ", LocalFacts.answer("2.5 kg เท่ากับกี่กรัม"))
        assertEquals("0.5 กิโลกรัม = 500 กรัมครับ", LocalFacts.answer("0.5 kg เป็นกี่กรัม"))
    }

    @Test fun gramToKgWorks() {
        assertEquals("1,000 กรัม = 1 กิโลกรัมครับ", LocalFacts.answer("1000 กรัมเท่ากับกี่กิโล"))
        assertEquals("500 กรัม = 0.5 กิโลกรัมครับ", LocalFacts.answer("500 กรัมเป็นกี่ kg"))
    }

    @Test fun timeAndDayAreHandledLocally() {
        assertNotNull(LocalFacts.answer("ตอนนี้กี่โมง"))
        assertNotNull(LocalFacts.answer("วันนี้วันอะไร"))
        assertNotNull(LocalFacts.answer("พรุ่งนี้วันอะไร"))
        assertNotNull(LocalFacts.answer("เมื่อวานวันอะไร"))
    }

    @Test fun commonThaiVariantsDoNotCrash() {
        val prompts = listOf("1kg เท่ากับกี่กรัม", "2 กิโล เป็นกี่กรัม", "1000g เท่ากับกี่กิโล", "กี่โมงแล้ว", "วันนี้วันที่เท่าไหร่")
        prompts.forEach { prompt ->
            val result = LocalFacts.answer(prompt)
            assertTrue("LocalFacts must return safely for: $prompt", result == null || result.isNotBlank())
        }
    }

    @Test fun unrelatedQuestionsNeverGetInventedLocalFacts() {
        val unrelated = listOf("คอมพิวเตอร์มีส่วนประกอบอะไรบ้าง", "ประเทศไทยมีประชากรกี่คน", "เขียนโค้ด Kotlin ให้หน่อย", "AMD คือบริษัทอะไร", "สรุปข่าววันนี้", "ช่วยวางแผนการตลาด", "สวัสดี", "ฉันชื่ออะไร")
        unrelated.forEach { prompt -> assertNull("Must delegate unrelated question: $prompt", LocalFacts.answer(prompt)) }
    }

    @Test fun emptyAndGarbageInputDoesNotCrashOrHallucinate() {
        listOf("", "   ", "?", "...", "asdfgh", "123456").forEach { prompt -> assertNull("Unexpected local answer for: $prompt", LocalFacts.answer(prompt)) }
    }
}
