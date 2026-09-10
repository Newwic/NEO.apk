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
        if (listOf("ตอนนี้กี่โมง","กี่โมงแล้ว","เวลาเท่าไหร่","เวลาเท่าไร","เวลาตอนนี้","ตอนนี้เวลา","เวลาอะไร","ขอเวลา","กี่โมง").any { q.contains(it) }) return "ตอนนี้ ${now.format(DateTimeFormatter.ofPattern("HH:mm"))} น."
        if (listOf("วันนี้วันอะไร","วันนี้วันไหน","วันนี้วัน").any { q.contains(it) }) return "วันนี้${dayNames[now.dayOfWeek]}ครับ"
        if (listOf("พรุ่งนี้วันอะไร","พรุ่งนี้วันไหน","พรุ่งนี้เป็นวันอะไร").any { q.contains(it) }) { val d=LocalDate.now().plusDays(1); return "พรุ่งนี้${dayNames[d.dayOfWeek]}ครับ" }
        if (listOf("เมื่อวานวันอะไร","เมื่อวานวันไหน","เมื่อวานเป็นวันอะไร").any { q.contains(it) }) { val d=LocalDate.now().minusDays(1); return "เมื่อวาน${dayNames[d.dayOfWeek]}ครับ" }
        if (q.contains("วันที่เท่าไหร่") || q.contains("วันที่เท่าไร") || q.contains("วันนี้วันที่") || q=="วันที่") return "วันนี้วันที่ ${now.format(DateTimeFormatter.ofPattern("d MMMM yyyy", th))}"

        if (Regex("(?:1)?ชั่วโมง(?:มี|เท่ากับ)?กี่นาที").containsMatchIn(q)) return "1 ชั่วโมง = 60 นาทีครับ"
        if (Regex("(?:1)?นาที(?:มี|เท่ากับ)?กี่วินาที").containsMatchIn(q)) return "1 นาที = 60 วินาทีครับ"
        if (Regex("(?:1)?วัน(?:มี|เท่ากับ)?กี่ชั่วโมง").containsMatchIn(q)) return "1 วัน = 24 ชั่วโมงครับ"
        if ((q.contains("โลก") || q.contains("ผิวโลก")) && (q.contains("แรงดึงดูด") || q.contains("แรงโน้มถ่วง")) && (q.contains("เท่าไร") || q.contains("เท่าไหร่"))) return "ความเร่งเนื่องจากแรงโน้มถ่วงที่ผิวโลกโดยเฉลี่ยประมาณ 9.81 m/s² ครับ"

        val kg=Regex("([0-9]+(?:\\.[0-9]+)?)(?:กิโลกรัม|กิโล|kg)(?:เท่ากับ|เป็น|=)?(?:กี่)?กรัม").find(q)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (kg!=null) return "${pretty(kg)} กิโลกรัม = ${pretty(kg*1000)} กรัมครับ"
        val g=Regex("([0-9]+(?:\\.[0-9]+)?)(?:กรัม|g)(?:เท่ากับ|เป็น|=)?(?:กี่)?(?:กิโลกรัม|กิโล|kg)").find(q)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (g!=null) return "${pretty(g)} กรัม = ${pretty(g/1000)} กิโลกรัมครับ"
        if (q.contains("1กิโลเท่ากับกี่กรัม") || q.contains("กิโลมีกี่กรัม")) return "1 กิโลกรัม = 1,000 กรัมครับ"
        return null
    }
    private fun pretty(v: Double): String = if(v%1.0==0.0) String.format(Locale.US,"%,.0f",v) else String.format(Locale.US,"%,.3f",v).trimEnd('0').trimEnd('.')
}
