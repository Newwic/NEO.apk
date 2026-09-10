package com.neo.assistant.knowledge

import com.neo.assistant.web.WebSearchClient

/** Evidence-first web recovery. General knowledge stays with the local 7B model. */
class AdaptiveAnswerEngine(
    private val knowledgeHub: KnowledgeHub,
    private val webSearch: WebSearchClient = WebSearchClient()
) {
    data class Answer(val text:String,val route:String,val learned:Boolean,val sources:List<String>)
    @Volatile private var lastWebQuestion: String? = null

    suspend fun answer(query: String): Answer? {
        val raw=query.trim()
        if(raw.length<2) return null

        // Do not web-search ordinary knowledge. Qwen should answer these first.
        // Web is reserved for explicitly fresh/current/search requests or recovery after local failure.
        val q=resolveFollowUp(raw)
        if(isConversationalOrAbility(raw) && q==raw) return null
        if(!needsFreshWeb(q)) return null
        if(!webSearch.canFallbackSearch(q)) return null

        val cached=knowledgeHub.retrieve(q,limit=6)
        val learned=cached.blocks.filter{it.contains("| learned-answer]")||it.contains("| learned-web]")}
        exactCachedAnswer(q,learned,cached.sources)?.let{ lastWebQuestion=q; return it }

        val packet=runCatching{webSearch.search(q)}.getOrNull()?:return null
        val ranked=packet.results
            .filter{it.title.isNotBlank()&&it.snippet.isNotBlank()}
            .distinctBy{it.url}
            .map{it to relevance(q,"${it.title} ${it.snippet}")}
            .filter{it.second>=0.32}
            .sortedByDescending{it.second}
            .take(3)
        if(ranked.isEmpty()) return null
        // Require a strong top match. Weak snippets must never become learned truth.
        if(ranked.first().second<0.42) return null
        val relevant=ranked.map{it.first}
        val answerText=clean(relevant.first().snippet).take(650)
        if(answerText.length<12) return null
        val sourceText=relevant.joinToString("\n"){"${it.title} — ${it.url}"}
        runCatching{knowledgeHub.importText("Q: $q","คำถาม: $q\nคำตอบ: $answerText\nแหล่งข้อมูล:\n$sourceText",relevant.first().url,"learned-answer")}
        relevant.forEach{r->runCatching{knowledgeHub.learnWeb(r.title,r.snippet,r.url)}}
        lastWebQuestion=q
        return Answer(answerText,"WEB → verified evidence → local cache",true,relevant.map{it.url})
    }

    private fun resolveFollowUp(raw:String):String {
        val compact=raw.lowercase().replace(Regex("\\s+"),"")
        val previous=lastWebQuestion?:return raw
        val follow=compact in setOf("ใช่","ต่อ","ต่อเลย","แล้วล่ะ","แล้วละ","ตอนนี้ล่ะ","ตอนนี้ละ","เท่าไร","เท่าไหร่","ขอเพิ่ม","รายละเอียด") ||
            compact.startsWith("แล้วตอนนี้") || compact.startsWith("แล้วราคา") || compact.startsWith("แล้วมัน")
        return if(follow) "$previous $raw" else raw
    }

    private fun needsFreshWeb(q:String):Boolean {
        val s=q.lowercase()
        val fresh=listOf("ตอนนี้","ล่าสุด","วันนี้","ข่าว","ราคา","current","latest","today","ตอนนี้เท่า","ปัจจุบัน","อัปเดต","update","ค้นเว็บ","หาในเว็บ")
        return fresh.any{s.contains(it)}
    }

    private fun exactCachedAnswer(query:String,blocks:List<String>,sources:List<String>):Answer? {
        val exact=blocks.firstOrNull{it.contains("Q: $query",true)}?:blocks.firstOrNull{it.contains("คำถาม: $query",true)}?:return null
        val a=exact.substringAfter("คำตอบ:","").substringBefore("แหล่งข้อมูล:").trim()
        if(a.length<4)return null
        return Answer(a.take(900),"LOCAL EXACT CACHE",false,sources.take(3))
    }

    private fun isConversationalOrAbility(query:String):Boolean {
        val q=query.lowercase().replace(Regex("\\s+"),"")
        val p=listOf("ทำได้ไหม","ได้ไหม","เขียนcode","เขียนโค้ด","ช่วยได้","ช่วยอะไร","ทำอะไรได้","เก่งอะไร","ตอบไม่ตรง","ตอบมั่ว","เข้าใจไหม","canyou","areyou","doyou")
        return p.any{q.contains(it)}&&q.length<55
    }

    private fun relevance(query:String,evidence:String):Double {
        val q=normalize(query); val e=normalize(evidence)
        if(q.isBlank()||e.isBlank())return 0.0
        val latin=Regex("[a-z0-9]{3,}").findAll(q).map{it.value}.toSet()
        if(latin.isNotEmpty()){
            val hits=latin.count{e.contains(it)}
            if(hits>0)return (0.55+0.45*hits.toDouble()/latin.size).coerceAtMost(1.0)
        }
        val q3=trigrams(q);val e3=trigrams(e)
        if(q3.isEmpty()||e3.isEmpty())return 0.0
        return q3.count{it in e3}.toDouble()/q3.size
    }
    private fun normalize(s:String)=s.lowercase().replace(Regex("https?://\\S+")," ").replace(Regex("[^a-z0-9ก-๙]+"),"").replace("อะไร","").replace("คือ","").replace("เท่าไหร่","").replace("เท่าไร","").replace("เท่ากับ","").replace("ขอ","").replace("หน่อย","").trim()
    private fun trigrams(s:String):Set<String>{if(s.length<3)return if(s.isBlank()) emptySet() else setOf(s);return(0..s.length-3).map{s.substring(it,it+3)}.toSet()}
    private fun clean(t:String)=t.replace(Regex("<[^>]+>")," ").replace("&quot;","\"").replace("&amp;","&").replace("&#39;","'").replace(Regex("\\s+")," ").trim()
}
