package com.neo.assistant.ai

import android.content.Context
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.knowledge.KnowledgeHub
import com.neo.assistant.memory.MemoryEntity
import com.neo.assistant.memory.NeoDatabase
import com.neo.assistant.web.WebSearchClient
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LocalBrain(private val context: Context) {
    companion object {
        private const val MODEL_FILE = "Qwen2.5-7B-Instruct-Q4_K_M.gguf"
        private const val MODEL_URL = "https://huggingface.co/lmstudio-community/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf?download=true"
        private const val MIN_MODEL_BYTES = 4_000_000_000L
        private const val INFERENCE_TIMEOUT_SECONDS = 60L
    }

    private val mutex = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val webSearch = WebSearchClient()
    private val knowledgeHub = KnowledgeHub(NeoDatabase.get(context).appDataDao())
    @Volatile private var model: LlamaModel? = null
    @Volatile private var inferenceTimedOut = false

    fun modelFile(): File {
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_FILE)
    }

    suspend fun prepare(onStatus: (String) -> Unit = {}): Boolean = mutex.withLock {
        if (model != null) return@withLock true
        val file = modelFile()
        if (!file.exists() || file.length() < MIN_MODEL_BYTES) {
            if (!downloadModel(file, onStatus)) return@withLock false
        }
        return@withLock try {
            onStatus("LOCAL • กำลังโหลด Qwen2.5 7B…")
            model = Llama.loadModel(file.absolutePath, LlamaConfig(contextSize=1024, threads=Runtime.getRuntime().availableProcessors().coerceIn(4,5), gpuLayers=0, temperature=0.45f, topP=0.9f, topK=40))
            inferenceTimedOut = false
            onStatus("LOCAL • Qwen2.5 7B พร้อมใช้งาน")
            true
        } catch (e: Throwable) {
            onStatus("LOCAL • โหลด 7B ไม่สำเร็จ • ${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun downloadModel(target: File, onStatus: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, "$MODEL_FILE.part")
        try {
            target.parentFile?.mkdirs()
            val req = Request.Builder().url(MODEL_URL).get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext false
                val body = res.body ?: return@withContext false
                val total = body.contentLength(); var done = 0L; var last = -1
                body.byteStream().use { input -> FileOutputStream(tmp,false).use { out ->
                    val buffer=ByteArray(1024*1024)
                    while(true){ val n=input.read(buffer); if(n<=0) break; out.write(buffer,0,n); done+=n
                        if(total>0){ val p=((done*100)/total).toInt(); if(p!=last && p%2==0){last=p; onStatus("LOCAL • ดาวน์โหลดสมอง 7B $p%")}}
                    }
                }}
            }
            if(tmp.length()<MIN_MODEL_BYTES){tmp.delete();return@withContext false}
            if(target.exists())target.delete(); if(!tmp.renameTo(target)){tmp.copyTo(target,true);tmp.delete()}; true
        } catch (_:Throwable){ false }
    }

    suspend fun generate(message:String, memories:List<MemoryEntity>, cfg:NeoLiveConfig, extraContext:List<String> = emptyList(), onStatus:(String)->Unit = {}):String = withContext(Dispatchers.IO){
        fastPath(message,memories)?.let{return@withContext it}
        val webBlocks=extraContext.filter{it.startsWith("[WEB ")}
        if(webBlocks.isNotEmpty()) return@withContext buildDirectWebAnswer(message,webBlocks)
        if(inferenceTimedOut || !prepare(onStatus)) return@withContext searchFallback(message,memories,extraContext,onStatus)

        val memoryText=memories.take(4).joinToString("\n"){"[${it.category}] ${it.text.take(180)}"}
        val contextText=extraContext.take(3).joinToString("\n\n"){it.take(350)}
        val system=buildString{
            append(cfg.systemPrompt.take(1200))
            append("\nคุณชื่อ NEO ตอบภาษาเดียวกับผู้ใช้")
            append("\nตอบสั้น ชัด เข้าใจง่าย ปกติ 1-3 ประโยค ถ้าผู้ใช้ไม่ได้ขอรายละเอียดห้ามตอบยาว")
            append("\nใช้ความรู้ในโมเดลก่อน Memory ใช้เฉพาะข้อมูลผู้ใช้ Knowledge ใช้เมื่อเกี่ยวข้อง")
            append("\nถ้าเป็นข้อมูลสดหรือไม่มั่นใจให้ตอบ [[NEED_WEB]] เท่านั้น")
            if(memoryText.isNotBlank()) append("\nMEMORY:\n$memoryText")
            if(contextText.isNotBlank()) append("\nKNOWLEDGE:\n$contextText")
        }
        val current=model ?: return@withContext searchFallback(message,memories,extraContext,onStatus)
        onStatus("LOCAL • NEO กำลังคิด…")
        val future=inferenceExecutor.submit<String>{runBlocking{Llama.complete(current,message.take(700),system.take(4000),maxTokens=cfg.maxTokens.coerceIn(48,120)).text.trim()}}
        try{
            val ans=future.get(INFERENCE_TIMEOUT_SECONDS,TimeUnit.SECONDS)
            if(ans.isBlank()||ans.contains("[[NEED_WEB]]")) searchFallback(message,memories,extraContext,onStatus) else ans
        }catch(_:TimeoutException){inferenceTimedOut=true;future.cancel(true);searchFallback(message,memories,extraContext,onStatus)}
        catch(_:Throwable){future.cancel(true);searchFallback(message,memories,extraContext,onStatus)}
    }

    private suspend fun searchFallback(message:String,memories:List<MemoryEntity>,extraContext:List<String>,onStatus:(String)->Unit):String{
        if(webSearch.canFallbackSearch(message)){
            onStatus("WEB • กำลังค้นข้อมูลที่เกี่ยวข้อง…")
            val p=webSearch.search(message)
            if(p.results.isNotEmpty()){
                p.results.take(3).forEach{r->runCatching{knowledgeHub.learnWeb(r.title,r.snippet,r.url)}}
                return buildDirectWebAnswer(message,p.results.take(3).mapIndexed{i,r->"[WEB ${i+1}: ${r.title}]\n${r.snippet}\nSOURCE: ${r.url}"})
            }
        }
        if(extraContext.isNotEmpty()) return extraContext.first().substringAfter(']').trim().take(350)
        if(memories.isNotEmpty() && message.contains("จำ")) return "ผมจำได้ว่า ${memories.first().text.take(220)}"
        return "ตอนนี้ผมยังหาคำตอบที่มั่นใจไม่ได้ครับ"
    }

    private fun fastPath(message:String,memories:List<MemoryEntity>):String?{
        val q=message.lowercase().trim()
        if(q.contains("นายชื่ออะไร")||q.contains("ชื่อของนาย")||q.contains("what is your name")) return "ผมชื่อ NEO ครับ"
        if(memories.isNotEmpty()&&(q.contains("จำอะไร")||q.contains("ข้อมูลที่จำ"))) return "ผมจำได้ว่า ${memories.take(3).joinToString(" / "){it.text.take(100)}}"
        return null
    }

    private fun buildDirectWebAnswer(message:String,webBlocks:List<String>):String{
        val q=message.lowercase()
        val items=webBlocks.take(3).mapNotNull{b->
            val lines=b.lines().filter{it.isNotBlank()}; if(lines.isEmpty()) null else {
                val title=lines.first().substringAfter(":",lines.first()).substringBeforeLast("]").trim()
                val snippet=lines.filterNot{it.startsWith("[WEB ")||it.startsWith("SOURCE:")}.joinToString(" ").replace(Regex("\\s+")," ").trim()
                title to snippet
            }
        }
        if(items.isEmpty()) return "ยังไม่พบข้อมูลที่ตรงกับคำถามครับ"
        if(listOf("dollar","ดอลลาร์","usd","ค่าเงิน","อัตราแลกเปลี่ยน","บาท").any{q.contains(it)}) return items.first().second.take(120)
        if(listOf("ข่าว","news","ล่าสุด","อัปเดต").any{q.contains(it)}){
            return "ข่าวล่าสุดที่พบ:\n" + items.take(3).mapIndexed{i,(t,_)->"${i+1}. ${t.take(100)}"}.joinToString("\n")
        }
        return items.first().second.take(320).ifBlank{items.first().first}
    }

    fun release(){inferenceExecutor.shutdownNow();if(!inferenceTimedOut)model?.let{runCatching{Llama.releaseModel(it)}};model=null}
}
