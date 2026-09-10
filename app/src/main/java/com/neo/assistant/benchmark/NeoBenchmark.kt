package com.neo.assistant.benchmark

/**
 * Deterministic 100-level benchmark for NEO.
 * Levels increase from local identity/facts to reasoning and finally web-grounded synthesis.
 * This suite is intentionally data-only so it can be run on-device against the real local model.
 */
data class BenchmarkCase(
    val level: Int,
    val category: String,
    val question: String,
    val expectedKeywords: List<String> = emptyList(),
    val requiresWeb: Boolean = false,
    val mustNotHallucinate: Boolean = true
)

data class BenchmarkResult(
    val level: Int,
    val category: String,
    val question: String,
    val answer: String,
    val latencyMs: Long,
    val usedWeb: Boolean,
    val passed: Boolean,
    val reason: String
)

object NeoBenchmark {
    val cases: List<BenchmarkCase> = buildList {
        fun c(level:Int, category:String, q:String, vararg keys:String, web:Boolean=false) =
            add(BenchmarkCase(level, category, q, keys.toList(), web))

        c(1,"identity","นายชื่ออะไร","NEO")
        c(2,"identity","นายเป็นใคร","NEO","AI")
        c(3,"identity","นายทำอะไรได้บ้าง","ช่วย")
        c(4,"basic","1 กิโลกรัมเท่ากับกี่กรัม","1000")
        c(5,"basic","1 เมตรเท่ากับกี่เซนติเมตร","100")
        c(6,"basic","2+3 เท่ากับเท่าไร","5")
        c(7,"basic","10-4 เท่ากับเท่าไร","6")
        c(8,"basic","6 คูณ 7 เท่ากับเท่าไร","42")
        c(9,"basic","100 หาร 4 เท่ากับเท่าไร","25")
        c(10,"basic","น้ำแข็งเกิดจากอะไร","น้ำ")
        c(11,"general","ประเทศไทยอยู่ในทวีปอะไร","เอเชีย")
        c(12,"general","เมืองหลวงของประเทศไทยคืออะไร","กรุงเทพ")
        c(13,"general","โลกโคจรรอบอะไร","ดวงอาทิตย์")
        c(14,"general","มนุษย์หายใจเอาแก๊สอะไรเข้าไป","ออกซิเจน")
        c(15,"general","CPU คืออะไร","ประมวลผล")
        c(16,"general","RAM มีหน้าที่อะไร","หน่วยความจำ")
        c(17,"general","SSD ต่างจาก HDD อย่างไร","SSD","HDD")
        c(18,"general","HTTPS ช่วยเรื่องอะไร","เข้ารหัส")
        c(19,"general","DNS คืออะไร","โดเมน","IP")
        c(20,"general","ฐานข้อมูลคืออะไร","ข้อมูล")

        c(21,"reasoning","ถ้ามีแอปเปิล 12 ลูก แบ่งให้ 3 คนเท่ากัน คนละกี่ลูก","4")
        c(22,"reasoning","ซื้อของ 250 บาท จ่าย 500 บาท ต้องทอนเท่าไร","250")
        c(23,"reasoning","รถวิ่ง 60 กม./ชม. เป็นเวลา 2 ชั่วโมง เดินทางกี่กิโลเมตร","120")
        c(24,"reasoning","สินค้า 1000 บาท ลด 10% เหลือเท่าไร","900")
        c(25,"reasoning","VAT 7% ของ 1000 บาทเท่ากับเท่าไร","70")
        c(26,"reasoning","ถ้า A มากกว่า B และ B มากกว่า C ใครมากที่สุด","A")
        c(27,"reasoning","ลำดับ 2,4,6,8 ตัวถัดไปคืออะไร","10")
        c(28,"reasoning","ลำดับ 1,2,4,8 ตัวถัดไปคืออะไร","16")
        c(29,"reasoning","ถ้าวันนี้วันจันทร์ อีก 3 วันเป็นวันอะไร","พฤหัส")
        c(30,"reasoning","ไฟล์ 2 GB จำนวน 3 ไฟล์รวมกี่ GB","6")
        c(31,"coding","ตัวแปรในโปรแกรมคืออะไร","ค่า")
        c(32,"coding","if statement ใช้ทำอะไร","เงื่อนไข")
        c(33,"coding","loop ใช้ทำอะไร","ซ้ำ")
        c(34,"coding","API คืออะไร","เชื่อม","โปรแกรม")
        c(35,"coding","JSON ใช้เก็บข้อมูลแบบใด","ข้อมูล")
        c(36,"coding","Git commit คืออะไร","การเปลี่ยนแปลง")
        c(37,"coding","HTTP 404 โดยทั่วไปหมายถึงอะไร","ไม่พบ")
        c(38,"coding","HTTP 500 โดยทั่วไปหมายถึงอะไร","server")
        c(39,"coding","SQL SELECT ใช้ทำอะไร","ข้อมูล")
        c(40,"coding","Primary key มีไว้ทำอะไร","ระบุ","ไม่ซ้ำ")

        c(41,"multi_step","สินค้า 800 บาท ลด 15% แล้วบวก VAT 7% ราคาสุทธิประมาณเท่าไร","727")
        c(42,"multi_step","มีเงิน 10000 บาท เก็บ 20% ใช้ไป 3000 บาท เหลือเงินที่ยังไม่ใช้และไม่เก็บเท่าไร","5000")
        c(43,"multi_step","รถใช้ไฟ 15 kWh ต่อ 100 กม. วิ่ง 200 กม. ใช้ไฟกี่ kWh","30")
        c(44,"multi_step","ซื้อสินค้า 4 ชิ้น ชิ้นละ 125 บาท จ่าย 1000 บาท ทอนเท่าไร","500")
        c(45,"multi_step","ถ้า server สำรองข้อมูลทุกวัน 7 วัน จะมีรอบสำรองกี่รอบ","7")
        c(46,"multi_step","RAM 16GB เพิ่มอีก 16GB รวมเป็นเท่าไร","32")
        c(47,"multi_step","ไฟล์ 500MB จำนวน 8 ไฟล์รวมประมาณกี่ GB","4")
        c(48,"multi_step","อินเทอร์เน็ต 100 Mbps ดาวน์โหลด 1 GB ในทางทฤษฎีใช้ประมาณกี่วินาที","80")
        c(49,"multi_step","ถ้ากำไร 20% จากต้นทุน 500 บาท ราคาขายเท่าไร","600")
        c(50,"multi_step","ถ้าพนักงาน 5 คนทำคนละ 8 ชั่วโมง รวมกี่คน-ชั่วโมง","40")
        c(51,"quality","อธิบายความต่างระหว่าง RAM กับ Storage แบบสั้น","RAM","Storage")
        c(52,"quality","อธิบาย CPU และ GPU ต่างกันอย่างไรแบบสั้น","CPU","GPU")
        c(53,"quality","ทำไมต้อง backup ข้อมูล","ข้อมูล")
        c(54,"quality","ทำไมรหัสผ่านไม่ควรใช้ซ้ำ","บัญชี")
        c(55,"quality","RAG ในระบบ AI มีประโยชน์อะไร","ข้อมูล","ตอบ")
        c(56,"quality","Hallucination ของ AI คืออะไร","ข้อมูล","ผิด")
        c(57,"quality","ทำไมข้อมูลสดไม่ควรพึ่งความจำของโมเดลอย่างเดียว","ล่าสุด")
        c(58,"quality","ทำไมควรตรวจหลายแหล่งเมื่อข่าวขัดแย้งกัน","แหล่ง")
        c(59,"quality","ถ้าไม่รู้คำตอบ AI ควรทำอย่างไร","ไม่","ค้น")
        c(60,"quality","การตอบตรงคำถามสำคัญอย่างไร","คำถาม")

        c(61,"memory","ถ้าผู้ใช้บอกว่า 'จำไว้ว่ารหัสโปรเจกต์คือ NEO-61' แล้วถามรหัสโปรเจกต์ ควรตอบอะไร","NEO-61")
        c(62,"memory","ถ้าผู้ใช้บอกว่าชอบคำตอบสั้น แล้วถามต่อ ควรปรับรูปแบบอย่างไร","สั้น")
        c(63,"rag","ถ้า Knowledge มีเอกสารที่ตรงคำถามและอีกเอกสารไม่เกี่ยว ควรใช้เอกสารไหน","ตรง")
        c(64,"rag","ถ้า RAG พบข้อมูลที่มีคำเหมือนแต่คนละเรื่อง ควรทำอย่างไร","ไม่","เกี่ยว")
        c(65,"rag","ถ้า Knowledge ขัดกับข้อมูลเว็บล่าสุดในคำถามเรื่องราคา ควรให้น้ำหนักอะไร","เว็บ","ล่าสุด")
        c(66,"rag","RAG ควรส่งเอกสารทั้งหมดเข้าโมเดลหรือเฉพาะที่เกี่ยวข้อง","เกี่ยว")
        c(67,"memory","ข้อมูลส่วนตัวจาก Memory ควรถูกนำมาใช้เมื่อใด","เกี่ยว")
        c(68,"memory","ถ้าความจำไม่เกี่ยวกับคำถาม ควรใส่ใน context หรือไม่","ไม่")
        c(69,"rag","ถ้าหลักฐานไม่พอควรเดาคำตอบหรือบอกว่าไม่พอ","ไม่พอ")
        c(70,"rag","การ deduplicate ผลค้นช่วยอะไร","ซ้ำ")
        c(71,"analysis","เปรียบเทียบ Local AI กับ Cloud AI อย่างน้อย 2 ด้าน","Local","Cloud")
        c(72,"analysis","อธิบายข้อดีข้อเสียของโมเดล 7B บนมือถือแบบกระชับ","เร็ว","จำกัด")
        c(73,"analysis","ถ้าคำตอบจากเว็บ 3 แหล่งไม่ตรงกัน ควรสรุปอย่างไร","แหล่ง")
        c(74,"analysis","ถ้าผู้ใช้ถามข้อมูลที่เปลี่ยนทุกวัน ควรใช้ความรู้ในโมเดลหรือเว็บ","เว็บ")
        c(75,"analysis","ถ้าเว็บล่มแต่คำถามเป็นความรู้พื้นฐาน ควรทำอย่างไร","Local")

        c(76,"web","วันนี้วันที่เท่าไร", "", web=true)
        c(77,"web","ตอนนี้ 1 ดอลลาร์สหรัฐแลกได้ประมาณกี่บาทไทย", "บาท", web=true)
        c(78,"web","ราคาทองคำล่าสุดเป็นอย่างไร", "ทอง", web=true)
        c(79,"web","ราคา Bitcoin ล่าสุดประมาณเท่าไร", "Bitcoin", web=true)
        c(80,"web","ข่าวเทคโนโลยีสำคัญวันนี้มีอะไรบ้าง", "", web=true)
        c(81,"web","ข่าว AI ล่าสุดวันนี้มีอะไรสำคัญ", "AI", web=true)
        c(82,"web","ข่าวเศรษฐกิจสหรัฐล่าสุดมีประเด็นอะไร", "สหรัฐ", web=true)
        c(83,"web","ค่าเงิน USD/THB ล่าสุดเป็นอย่างไร", "THB", web=true)
        c(84,"web","ราคาน้ำมันล่าสุดมีแนวโน้มอย่างไรจากข้อมูลปัจจุบัน", "น้ำมัน", web=true)
        c(85,"web","สรุปข่าวตลาดหุ้นสหรัฐล่าสุดแบบสั้น", "สหรัฐ", web=true)
        c(86,"web_verify","ค้นข้อมูลสดแล้วตอบพร้อมบอกว่าใช้แหล่งข้อมูลอะไร: ค่าเงิน USD/THB ล่าสุด", "USD", web=true)
        c(87,"web_verify","ค้นข่าว AI ล่าสุดและสรุปเฉพาะประเด็นที่มีหลักฐานในผลค้น", "AI", web=true)
        c(88,"web_verify","ค้นข่าวเทคโนโลยีล่าสุดจากหลายผลค้น แล้วบอกประเด็นที่ตรงกัน", "", web=true)
        c(89,"web_verify","ถ้าผลค้นเว็บไม่พอตอบคำถาม ให้บอกว่าไม่พอแทนการเดา: ข่าวล่าสุดของบริษัทสมมติ XYZ-NEO-UNKNOWN", "ไม่", web=true)
        c(90,"web_verify","ค้นข้อมูลล่าสุดเรื่อง Bitcoin แล้วแยกข้อเท็จจริงออกจากการคาดการณ์", "Bitcoin", web=true)
        c(91,"hard_web","เปรียบเทียบข้อมูล USD/THB ล่าสุดกับปัจจัยเศรษฐกิจที่ผลค้นกล่าวถึง โดยห้ามแต่งเหตุผลที่ไม่มีในแหล่ง", "USD", web=true)
        c(92,"hard_web","สรุปข่าว AI ล่าสุด 3 ประเด็น โดยเลือกเฉพาะข้อมูลที่เกี่ยวกับคำถามและไม่เอาข่าวคนละเรื่อง", "AI", web=true)
        c(93,"hard_web","จากข่าวเทคโนโลยีล่าสุด แยกสิ่งที่เป็นข้อเท็จจริงกับสิ่งที่ยังเป็นการคาดการณ์", "", web=true)
        c(94,"hard_web","ค้นข่าวเศรษฐกิจล่าสุดแล้วสรุปผลที่อาจเกี่ยวกับค่าเงิน โดยระบุเมื่อข้อมูลไม่พอ", "", web=true)
        c(95,"hard_web","ค้นข้อมูลล่าสุดจากหลายผลค้นแล้วตอบว่าแหล่งข้อมูลขัดแย้งกันหรือไม่ ห้ามเดา", "", web=true)
        c(96,"hard_web","หาข้อมูลสดเรื่องราคาทอง แล้วตอบราคา/ทิศทางเฉพาะเท่าที่ผลค้นรองรับ", "ทอง", web=true)
        c(97,"hard_web","ค้นข่าว AI ล่าสุดและอธิบายความสำคัญโดยแยกข้อเท็จจริงออกจากความคิดเห็น", "AI", web=true)
        c(98,"hard_web","ใช้ข้อมูลเว็บล่าสุดตอบคำถามหนึ่งย่อหน้า: ภาพรวมตลาดเทคโนโลยีวันนี้เป็นอย่างไร โดยห้ามนำข้อมูลเก่าที่ไม่เกี่ยวมาปน", "", web=true)
        c(99,"hard_web","ค้นข้อมูลสดหลายแหล่งเกี่ยวกับ USD/THB แล้วสรุปสั้น ๆ พร้อมยอมรับหากแหล่งข้อมูลไม่ตรงกัน", "USD", web=true)
        c(100,"hard_web","ทำคำตอบแบบมีหลักฐาน: สรุป 3 ข่าว AI สำคัญล่าสุด ระบุใจความ แหล่งที่พบ และสิ่งที่ยังยืนยันไม่ได้ โดยห้ามสร้างข้อมูลเพิ่ม", "AI", web=true)
    }

    fun grade(case: BenchmarkCase, answer: String, usedWeb: Boolean, latencyMs: Long): BenchmarkResult {
        val normalized = answer.lowercase()
        val routeOk = !case.requiresWeb || usedWeb
        val keywordsOk = case.expectedKeywords.filter { it.isNotBlank() }.all { normalized.contains(it.lowercase()) }
        val failureText = listOf("สร้างคำตอบไม่ได้", "ลองถามใหม่", "เกิดข้อผิดพลาด")
        val answered = answer.isNotBlank() && failureText.none { normalized.contains(it) }
        val suspicious = normalized.contains("xyz-neo-unknown") && !normalized.contains("ไม่พบ") && !normalized.contains("ไม่พอ")
        val passed = answered && routeOk && keywordsOk && !suspicious
        val reason = when {
            !answered -> "NO_ANSWER"
            !routeOk -> "WRONG_ROUTE"
            !keywordsOk -> "MISSING_EXPECTED_FACT"
            suspicious -> "POSSIBLE_HALLUCINATION"
            else -> "PASS"
        }
        return BenchmarkResult(case.level, case.category, case.question, answer, latencyMs, usedWeb, passed, reason)
    }

    fun summary(results: List<BenchmarkResult>): String {
        val passed = results.count { it.passed }
        val avg = if (results.isEmpty()) 0 else results.sumOf { it.latencyMs } / results.size
        val web = results.count { it.usedWeb }
        val wrongRoute = results.count { it.reason == "WRONG_ROUTE" }
        val noAnswer = results.count { it.reason == "NO_ANSWER" }
        val hallucination = results.count { it.reason == "POSSIBLE_HALLUCINATION" }
        return "NEO Benchmark: $passed/${results.size} | avg ${avg}ms | web $web | wrong-route $wrongRoute | no-answer $noAnswer | hallucination $hallucination"
    }
}
