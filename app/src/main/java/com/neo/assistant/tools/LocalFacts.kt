package com.neo.assistant.tools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object LocalFacts {
    private val th = Locale("th", "TH")
    private val dayNames = mapOf(
        java.time.DayOfWeek.MONDAY to "วันจันทร์", java.time.DayOfWeek.TUESDAY to "วันอังคาร",
        java.time.DayOfWeek.WEDNESDAY to "วันพุธ", java.time.DayOfWeek.THURSDAY to "วันพฤหัสบดี",
        java.time.DayOfWeek.FRIDAY to "วันศุกร์", java.time.DayOfWeek.SATURDAY to "วันเสาร์",
        java.time.DayOfWeek.SUNDAY to "วันอาทิตย์"
    )

    fun isLocalFactQuestion(query: String) = answer(query) != null

    fun answer(query: String): String? {
        val q = query.lowercase().replace(Regex("\\s+"), "")
        if (q.isBlank()) return null
        val now = LocalDateTime.now()

        if (listOf("ตอนนี้กี่โมง", "กี่โมงแล้ว", "เวลาเท่าไหร่", "เวลาเท่าไร", "เวลาตอนนี้", "ตอนนี้เวลา", "ตอนนี้เวลาเท่าไร", "ตอนนี้เวลาเท่าไหร่", "เวลาอะไร", "ขอเวลา", "กี่โมง").any { q.contains(it.replace(" ", "")) }) {
            return "ตอนนี้ ${now.format(DateTimeFormatter.ofPattern("HH:mm"))} น."
        }
        if (listOf("วันนี้วันอะไร", "วันนี้วันไหน", "วันนี้วัน").any { q.contains(it.replace(" ", "")) }) {
            return "วันนี้${dayNames[now.dayOfWeek]}ครับ"
        }
        if (listOf("พรุ่งนี้วันอะไร", "พรุ่งนี้วันไหน", "พรุ่งนี้เป็นวันอะไร").any { q.contains(it.replace(" ", "")) }) {
            val d = LocalDate.now().plusDays(1)
            return "พรุ่งนี้${dayNames[d.dayOfWeek]}ครับ"
        }
        if (listOf("เมื่อวานวันอะไร", "เมื่อวานวันไหน", "เมื่อวานเป็นวันอะไร").any { q.contains(it.replace(" ", "")) }) {
            val d = LocalDate.now().minusDays(1)
            return "เมื่อวาน${dayNames[d.dayOfWeek]}ครับ"
        }
        if (q.contains("วันที่เท่าไหร่") || q.contains("วันที่เท่าไร") || q.contains("วันนี้วันที่") || q == "วันที่") {
            return "วันนี้วันที่ ${now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", th))}"
        }

        val kg = Regex("([0-9]+(?:\\.[0-9]+)?)(?:กิโลกรัม|กิโล|kg)(?:เท่ากับ|เป็น|=)?(?:กี่)?กรัม").find(q)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (kg != null) {
            val g = kg * 1000
            return "${pretty(kg)} กิโลกรัม = ${pretty(g)} กรัมครับ"
        }
        val g = Regex("([0-9]+(?:\\.[0-9]+)?)(?:กรัม|g)(?:เท่ากับ|เป็น|=)?(?:กี่)?(?:กิโลกรัม|กิโล|kg)").find(q)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (g != null) {
            val kgv = g / 1000
            return "${pretty(g)} กรัม = ${pretty(kgv)} กิโลกรัมครับ"
        }
        if (q.contains("1กิโลเท่ากับกี่กรัม") || q.contains("กิโลมีกี่กรัม")) return "1 กิโลกรัม = 1,000 กรัมครับ"
        return null
    }

    private fun pretty(v: Double): String =
        if (v % 1.0 == 0.0) String.format(Locale.US, "%,.0f", v)
        else String.format(Locale.US, "%,.3f", v).trimEnd('0').trimEnd('.')
}
