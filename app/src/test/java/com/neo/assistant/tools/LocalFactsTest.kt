package com.neo.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalFactsTest {
    @Test fun kgToGramWorks() {
        assertEquals("1 กิโลกรัม = 1,000 กรัมครับ", LocalFacts.answer("1 กิโลเท่ากับกี่กรัม"))
        assertEquals("2.5 กิโลกรัม = 2,500 กรัมครับ", LocalFacts.answer("2.5 kg เท่ากับกี่กรัม"))
    }

    @Test fun gramToKgWorks() {
        assertEquals("1,000 กรัม = 1 กิโลกรัมครับ", LocalFacts.answer("1000 กรัมเท่ากับกี่กิโล"))
    }

    @Test fun timeAndDayAreHandledLocally() {
        assertNotNull(LocalFacts.answer("ตอนนี้กี่โมง"))
        assertNotNull(LocalFacts.answer("วันนี้วันอะไร"))
        assertNotNull(LocalFacts.answer("พรุ่งนี้วันอะไร"))
    }

    @Test fun unrelatedQuestionDoesNotGetFakeFact() {
        assertEquals(null, LocalFacts.answer("คอมพิวเตอร์มีส่วนประกอบอะไรบ้าง"))
    }
}
