package com.neo.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeoIdentityTest {
    @Test fun identityQuestionsAreLocalAndStable() {
        assertEquals("ผมชื่อ NEO ครับ เป็นผู้ช่วย AI ส่วนตัวของคุณ", NeoIdentity.answer("นายชื่อ"))
        assertEquals("ผมชื่อ NEO ครับ เป็นผู้ช่วย AI ส่วนตัวของคุณ", NeoIdentity.answer("นายชื่ออะไร"))
        assertEquals("ผมคือ NEO ผู้ช่วย AI ส่วนตัวของคุณครับ", NeoIdentity.answer("นายเป็นใคร"))
        assertEquals("ผมเป็น AI เลยไม่มีอายุแบบมนุษย์ครับ", NeoIdentity.answer("นายอายุเท่าไหร่"))
        assertEquals("ผมไม่มีความชอบส่วนตัวแบบมนุษย์ครับ แต่ผมถนัดช่วยเรื่องเทคโนโลยี โค้ด การค้นข้อมูล และงานต่าง ๆ", NeoIdentity.answer("นายชอบอะไร"))
        assertEquals("ตอนนี้ผมพร้อมคุยและช่วยคุณอยู่ครับ", NeoIdentity.answer("ทำไรอยู่"))
    }

    @Test fun everydayConversationNeverNeedsModelOrWeb() {
        assertEquals("สวัสดีครับ ผม NEO พร้อมช่วยคุณครับ", NeoIdentity.answer("สวัสดี"))
        assertEquals("สวัสดีครับ ผม NEO พร้อมช่วยคุณครับ", NeoIdentity.answer("hello"))
        assertEquals("ยินดีครับ", NeoIdentity.answer("ขอบคุณ"))
        assertEquals("ครับ", NeoIdentity.answer("โอเค"))
    }

    @Test fun spellingAndWhitespaceVariantsRemainStable() {
        assertEquals("ผมชื่อ NEO ครับ เป็นผู้ช่วย AI ส่วนตัวของคุณ", NeoIdentity.answer("  นายชื่ออะไร  "))
        assertEquals("ผมเป็น AI เลยไม่มีอายุแบบมนุษย์ครับ", NeoIdentity.answer("คุณอายุเท่าไร"))
        assertEquals("ตอนนี้ผมพร้อมคุยและช่วยคุณอยู่ครับ", NeoIdentity.answer("ตอนนี้ทำอะไรอยู่"))
    }

    @Test fun unrelatedQuestionsAreDelegated() {
        assertNull(NeoIdentity.answer("คอมพิวเตอร์มีส่วนประกอบอะไรบ้าง"))
        assertNull(NeoIdentity.answer("ข่าววันนี้มีอะไร"))
        assertNull(NeoIdentity.answer("1 กิโลเท่ากับกี่กรัม"))
        assertNull(NeoIdentity.answer("เขียนโค้ด Python ให้หน่อย"))
    }
}
