package com.neo.assistant.ai

import android.content.Context
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.knowledge.KnowledgeHub
import com.neo.assistant.memory.MemoryEntity
import com.neo.assistant.memory.NeoDatabase
import com.neo.assistant.web.WebSearchClient
import com.neo.assistant.web.WebSearchClient.Result as SearchResult
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LocalBrain(private val context: Context) {
 companion object { private const val MODEL_FILE="Qwen2.5-7B-Instruct-Q4_K_M.gguf"; private const val MODEL_URL="https://huggingface.co/lmstudio-community/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf?download=true"; private const val MIN_MODEL_BYTES=4_000_000_000L; private const val INFERENCE_TIMEOUT_SECONDS=28L }
 private val modelMutex=Mutex(); private val generationMutex=Mutex()
 private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).build()
 @Volatile private var inferenceExecutor:ExecutorService=Executors.newSingleThreadExecutor()
 private val appDataDao=NeoDatabase.get(context).appDataDao(); private val webSearch=WebSearchClient(); private val knowledgeHub=KnowledgeHub(appDataDao)
 @Volatile private var model:LlamaModel?=null; @Volatile private var lastInferenceFailure:String?=null
 fun modelFile():File { val d=context.getExternalFilesDir("models")?:File(context.filesDir,"models"); if(!d.exists())d.mkdirs(); return File(d,MODEL_FILE) }
 suspend fun prepare(onStatus:(String)->Unit={}):Boolean=modelMutex.withLock { if(model!=null)return@withLock true; val f=modelFile(); if((!f.exists()||f.length()<MIN_MODEL_BYTES)&&!downloadModel(f,onStatus))return@withLock false; try { onStatus("LOCAL • กำลังโหลด Qwen2.5 7B…"); model=Llama.loadModel(f.absolutePath,LlamaConfig(contextSize=1024,threads=Runtime.getRuntime().availableProcessors().coerceIn(4,6),gpuLayers=0,temperature=.22f,topP=.82f,topK=28)); lastInferenceFailure=null; onStatus("LOCAL • Qwen2.5 7B พร้อมใช้งาน"); true } catch(e:Throwable){ model=null; lastInferenceFailure="LOAD_${e.javaClass.simpleName}"; false } }
 private suspend fun downloadModel(target:File,onStatus:(String)->Unit):Boolean=withContext(Dispatchers.IO){ val tmp=File(target.parentFile,"$MODEL_FILE.part"); try { target.parentFile?.mkdirs(); client.newCall(Request.Builder().url(MODEL_URL).build()).execute().use{r->if(!r.isSuccessful)return@withContext false; val b=r.body?:return@withContext false; val total=b.contentLength();var done=0L;var last=-1;b.byteStream().use{i->FileOutputStream(tmp,false).use{o->val buf=ByteArray(1024*1024);while(true){val n=i.read(buf);if(n<=0)break;o.write(buf,0,n);done+=n;if(total>0){val p=((done*100)/total).toInt();if(p!=last&&p%2==0){last=p;onStatus("LOCAL • ดาวน์โหลดสมอง 7B $p%")}}}}}}; if(tmp.length()<MIN_MODEL_BYTES){tmp.delete();return@withContext false};if(target.exists())target.delete();if(!tmp.renameTo(target)){tmp.copyTo(target,true);tmp.delete()};true }catch(_:Throwable){false} }
 suspend fun generate(message:String,memories:List<MemoryEntity>,cfg:NeoLiveConfig,extraContext:List<String> = emptyList(),onStatus:(String)->Unit={}):String=generationMutex.withLock { withContext(Dispatchers.IO){ fastPath(message,memories)?.let{return@withContext it}; if(!prepare(onStatus))return@withContext safeFallback(message,memories,extraContext,onStatus); val current=model?:return@withContext safeFallback(message,memories,extraContext,onStatus); val web=extraContext.filter{it.startsWith("[WEB ")};val chats=extraContext.filter{it.startsWith("[CHAT ")};val knowledge=extraContext.filterNot{it.startsWith("[WEB ")||it.startsWith("[CHAT ")};val system=buildString{append("คุณคือ NEO ผู้ช่วย AI ตอบภาษาเดียวกับผู้ใช้ ตรงคำถาม กระชับ 1-4 ประโยค\n");append("ใช้ CHAT_HISTORY เข้าใจคำถามต่อเนื่อง ห้ามแต่งข้อมูล ห้ามตอบคนละเรื่อง\n");if(chats.isNotEmpty())append("CHAT_HISTORY:\n${chats.takeLast(6).joinToString("\n"){it.take(220)}}\n");if(memories.isNotEmpty())append("MEMORY:\n${memories.take(3).joinToString("\n"){it.text.take(160)}}\n");if(knowledge.isNotEmpty())append("KNOWLEDGE:\n${knowledge.take(2).joinToString("\n"){it.take(320)}}\n");if(web.isNotEmpty())append("WEB_CONTEXT:\n${web.take(3).joinToString("\n"){it.take(420)}}\n");if(webSearch.shouldSearch(message)&&web.isEmpty())append("ข้อมูลสดไม่มี WEB_CONTEXT ให้ตอบ [[NEED_WEB]] เท่านั้น\n");if(cfg.systemPrompt.isNotBlank())append(cfg.systemPrompt.take(300))};onStatus(if(web.isNotEmpty())"WEB • NEO กำลังสรุป…" else "LOCAL • NEO 7B กำลังคิด…");val answer=runInference(current,message.take(600),system.take(3600),cfg.maxTokens.coerceIn(40,96));if(answer.isNullOrBlank()){onStatus("LOCAL • 7B ไม่ตอบ กำลังกู้ระบบ…");recoverAfterFailure();return@withContext safeFallback(message,memories,extraContext,onStatus)};lastInferenceFailure=null;val clean=answer.trim();if(clean.contains("[[NEED_WEB]]"))return@withContext searchAndSynthesize(message,memories,onStatus);clean } }
 private fun runInference(current:LlamaModel,prompt:String,system:String,maxTokens:Int):String?{val ex=inferenceExecutor;val f=ex.submit<String>{runBlocking{Llama.complete(current,prompt,system,maxTokens=maxTokens).text.trim()}};return try{f.get(INFERENCE_TIMEOUT_SECONDS,TimeUnit.SECONDS)}catch(_:TimeoutException){lastInferenceFailure="TIMEOUT";f.cancel(true);null}catch(e:Throwable){lastInferenceFailure=e.javaClass.simpleName;f.cancel(true);null}}
 @Synchronized private fun recoverAfterFailure(){runCatching{inferenceExecutor.shutdownNow()};inferenceExecutor=Executors.newSingleThreadExecutor();model=null}
 private suspend fun searchAndSynthesize(message:String,memories:List<MemoryEntity>,onStatus:(String)->Unit):String{if(!webSearch.canFallbackSearch(message))return safeFallback(message,memories,emptyList(),onStatus);onStatus("WEB • กำลังค้นข้อมูล…");val p=runCatching{webSearch.search(message)}.getOrNull();if(p==null||p.results.isEmpty())return "ยังไม่พบข้อมูลที่ตรงกับคำถามครับ";p.results.take(2).forEach{r->runCatching{knowledgeHub.learnWeb(r.title,r.snippet,r.url)}};return conciseWebFallback(p.results)}
 private suspend fun safeFallback(message:String,memories:List<MemoryEntity>,extra:List<String>,onStatus:(String)->Unit):String{val q=message.lowercase().trim();if(q.contains("เงียบ")||q.contains("ไม่ตอบ")||q.contains("ตอบช้า")||q.contains("ทำไมช้า"))return "เมื่อกี้สมอง 7B ประมวลผลค้างครับ ตอนนี้ NEO ตัดงานที่ค้างแล้วและรับข้อความถัดไปต่อได้";if(q in setOf("ดี","โอเค","ok","ครับ","ใช่","อืม","อือ","นาย"))return "ครับ ผมอยู่นี่ ถามต่อได้เลย";val wb=extra.filter{it.startsWith("[WEB ")}.mapNotNull(::parseBlock);if(wb.isNotEmpty())return conciseWebFallback(wb);if(webSearch.canFallbackSearch(message)){onStatus("WEB • Local ไม่ตอบ กำลังค้นข้อมูล…");val p=runCatching{webSearch.search(message)}.getOrNull();if(p!=null&&p.results.isNotEmpty())return conciseWebFallback(p.results)};if(memories.isNotEmpty()&&q.contains("จำ"))return memories.take(3).joinToString("\n"){it.text};return "สมอง 7B รอบนี้ไม่ตอบภายในเวลาที่กำหนดครับ ผมยกเลิกงานนั้นแล้ว คุณส่งคำถามต่อได้ทันที"}
 private fun conciseWebFallback(results:List<SearchResult>)=results.take(2).joinToString("\n"){"${it.title}: ${it.snippet.take(260)}"}
 private fun parseBlock(block:String):SearchResult?{val l=block.lines();if(l.size<2)return null;val title=l.first().substringAfter(":","ข้อมูลเว็บ").substringBeforeLast("]").trim();val url=l.firstOrNull{it.startsWith("SOURCE:")}?.substringAfter("SOURCE:")?.trim().orEmpty();val sn=l.drop(1).firstOrNull{it.isNotBlank()&&!it.startsWith("SOURCE:")}?:return null;return SearchResult(title,sn,url)}
 private fun fastPath(message:String,memories:List<MemoryEntity>):String?{val q=message.lowercase().trim();if(q=="นาย"||q=="neo"||q=="นีโอ")return "ครับ ผม NEO อยู่นี่ ถามมาได้เลย";if(q.contains("ชื่ออะไร")||q.contains("นายคือใคร"))return "ผมชื่อ NEO ผู้ช่วย AI ส่วนตัวของคุณครับ";if((q.contains("จำอะไร")||q.contains("จำได้"))&&memories.isNotEmpty())return memories.take(3).joinToString("\n"){"• ${it.text}"};return null}
 fun release(){runCatching{inferenceExecutor.shutdownNow()};model=null}
}
