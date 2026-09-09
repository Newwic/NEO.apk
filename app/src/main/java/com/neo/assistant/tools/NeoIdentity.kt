package com.neo.assistant.tools

object NeoIdentity {
    fun answer(input: String): String? {
        val q = input.lowercase().trim().replace(Regex("\\s+"), " ")
        if (q.isBlank()) return null

        if (listOf("นายชื่อ", "นายชื่ออะไร", "คุณชื่ออะไร", "ชื่อของนาย", "ชื่ออะไร", "what is your name").any { q == it || q.contains(it) }) {
            return "ผมชื่อ NEO ครับ เป็นผู้ช่วย AI ส่วนตัวของคุณ"
        }
        if (listOf("นายเป็นใคร", "คุณเป็นใคร", "who are you").any { q.contains(it) }) {
            return "ผมคือ NEO ผู้ช่วย AI ส่วนตัวของคุณครับ"
        }
        if (listOf("นายอายุเท่าไร", "นายอายุเท่าไหร่", "คุณอายุเท่าไร", "คุณอายุเท่าไหร่", "อายุเท่าไร", "อายุเท่าไหร่").any { q.contains(it) }) {
            return "ผมเป็น AI เลยไม่มีอายุแบบมนุษย์ครับ"
        }
        if (listOf("นายชอบอะไร", "คุณชอบอะไร", "ชอบอะไร").any { q.contains(it) }) {
            return "ผมไม่มีความชอบส่วนตัวแบบมนุษย์ครับ แต่ผมถนัดช่วยเรื่องเทคโนโลยี โค้ด การค้นข้อมูล และงานต่าง ๆ"
        }
        if (listOf("นายเก่งอะไร", "ทำอะไรได้บ้าง", "ช่วยอะไรได้บ้าง", "what can you do").any { q.contains(it) }) {
            return "ผมช่วยตอบคำถาม อธิบายความรู้ เขียนและช่วยคิดโค้ด จำข้อมูล ค้น Knowledge และค้นเว็บสำหรับข้อมูลสดได้ครับ"
        }
        if (listOf("ทำไรอยู่", "ทำอะไรอยู่", "ตอนนี้ทำอะไรอยู่").any { q.contains(it) }) {
            return "ตอนนี้ผมพร้อมคุยและช่วยคุณอยู่ครับ"
        }
        return null
    }
}
