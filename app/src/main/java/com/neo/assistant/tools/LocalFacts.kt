package com.neo.assistant.tools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object LocalFacts {
    private val th = Locale("th", "TH")
    private val dayNames = mapOf(
        java.time.DayOfWeek.MONDAY to "วันจันทร์",
        java.time.DayOfWeek.TUESDAY to "วันอังคาร",
        java.time.DayOfWeek.WEDNESDAY to "วันพุธ",
        java.time.DayOfWeek.THURSDAY to "วันพฤหัสบดี",
        java.time.DayOfWeek.FRIDAY to "วันศุกร์",
        java.time.DayOfWeek.SATURDAY to "วันเสาร์",
        java.time.DayOfWeek.SUNDAY to "วันอาทิตย์"
    )

    fun isLocalFactQuestion(query: String): Boolean = answer(query) != null

    fun answer(query: String): String? {
        val q = query.lowercase().replace(Regex("\\s+"), "")
        val now = LocalDateTime.now()

        val asksTime = listOf(
            "ตอนนี้กี่โมง", "กี่โมงแล้ว", "เวลาเท่าไหร่", "เวลาตอนนี้", "ตอนนี้เวลา",
            "ตอนนี้เวลาเท่าไร", "ตอนนี้เวลาเท่าไหร่", "เวลาอะไร", "ขอเวลา", "กี่โมง"
        ).any { q.contains(it.replace(" ", "")) }
        if (asksTime) return "ตอนนี้ ${now.format(DateTimeFormatter.ofPattern("HH:mm"))} น."

        val asksToday = listOf("วันนี้วันอะไร", "วันนี้วันไหน", "วันนี้วัน").any { q.contains(it.replace(" ", "")) }
        if (asksToday) return "วันนี้${dayNames[now.dayOfWeek]}ครับ"

        val asksTomorrow = listOf("พรุ่งนี้วันอะไร", "พรุ่งนี้วันไหน", "พรุ่งนี้เป็นวันอะไร").any { q.contains(it.replace(" ", "")) }
        if (asksTomorrow) {
            val tomorrow = LocalDate.now().plusDays(1)
            return "พรุ่งนี้${dayNames[tomorrow.dayOfWeek]}ครับ"
        }

        if (q.contains("วันที่เท่าไหร่") || q.contains("วันนี้วันที่") || q == "วันที่") {
            return "วันนี้วันที่ ${now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", th))}"
        }

        return null
    }
}
