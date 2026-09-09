package com.neo.assistant.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchRoutingTest {
    private val web = WebSearchClient()

    @Test fun personalAndLocalQuestionsNeverGoToWeb() {
        listOf(
            "นายชื่ออะไร", "นายเป็นใคร", "นายอายุเท่าไหร่", "นายชอบอะไร",
            "ทำไรอยู่", "ตอนนี้กี่โมง", "วันนี้วันอะไร", "พรุ่งนี้วันอะไร"
        ).forEach { q ->
            assertFalse("must not search: $q", web.shouldSearch(q))
        }
    }

    @Test fun freshInformationDoesGoToWeb() {
        listOf(
            "ข่าววันนี้", "ราคาทองล่าสุด", "1 dollar กี่บาทไทย", "ค่าเงิน USD THB",
            "อากาศวันนี้", "bitcoin ราคา"
        ).forEach { q ->
            assertTrue("must search: $q", web.shouldSearch(q))
        }
    }

    @Test fun personalQuestionsCannotFallbackToWeb() {
        listOf(
            "นายชื่ออะไร", "นายเป็นใคร", "นายอายุเท่าไหร่", "นายชอบอะไร",
            "นายเก่งอะไร", "ทำอะไรได้บ้าง", "ทำไรอยู่"
        ).forEach { q ->
            assertFalse("must never fallback to web: $q", web.canFallbackSearch(q))
        }
    }
}
