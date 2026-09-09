package com.neo.assistant

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.neo.assistant.ai.LocalBrain
import com.neo.assistant.config.NeoSettings
import com.neo.assistant.data.AppDataDao
import com.neo.assistant.data.ChatEntity
import com.neo.assistant.dev.LiveConfigClient
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.knowledge.KnowledgeHub
import com.neo.assistant.memory.MemoryHub
import com.neo.assistant.memory.NeoDatabase
import com.neo.assistant.tools.LocalFacts
import com.neo.assistant.voice.NeoSpeechRecognizer
import com.neo.assistant.voice.NeoTts
import com.neo.assistant.web.WebSearchClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var neoTts: NeoTts
    private lateinit var settings: NeoSettings
    private lateinit var brain: LocalBrain
    private lateinit var liveClient: LiveConfigClient
    private lateinit var memoryHub: MemoryHub
    private lateinit var knowledgeHub: KnowledgeHub
    private lateinit var appDataDao: AppDataDao
    private val webSearch = WebSearchClient()
    @Volatile private var liveConfig = NeoLiveConfig()
    private var submitMessage: ((String) -> Unit)? = null
    private var updateStatus: ((String) -> Unit)? = null
    private var lastBrainStatus = "LOCAL • เตรียมสมอง 7B"

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(::onUserMessage)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        neoTts = NeoTts(this); settings = NeoSettings(this); brain = LocalBrain(this); liveClient = LiveConfigClient(settings.pcWorkerUrl)
        val db = NeoDatabase.get(this); memoryHub = MemoryHub(db.memoryDao()); appDataDao = db.appDataDao(); knowledgeHub = KnowledgeHub(appDataDao)
        lifecycleScope.launch { delay(300); brain.prepare(::setBrainStatus) }
        lifecycleScope.launch { while (isActive) { liveClient.fetch()?.let { liveConfig = it }; delay(3000) } }
        setContent { MaterialTheme { NeoScreen(::onUserMessage, { speechLauncher.launch(NeoSpeechRecognizer.intent()) }, { submitMessage = it }, { updateStatus = it; it(lastBrainStatus) }) } }
        lifecycleScope.launch { delay(300); runCatching { appDataDao.recentChats(50).asReversed().forEach { submitMessage?.invoke((if(it.role=="user") "คุณ: " else "NEO: ")+it.text) } } }
    }

    private fun setBrainStatus(s:String){ lastBrainStatus=s; runOnUiThread{updateStatus?.invoke(s)} }

    private fun onUserMessage(raw:String){
        val text=raw.trim(); if(text.isBlank()) return
        submitMessage?.invoke("คุณ: $text")
        lifecycleScope.launch {
            try {
                runCatching { appDataDao.insertChat(ChatEntity(role="user",text=text)) }
                LocalFacts.answer(text)?.let { safeReply(it); return@launch }
                if(text.contains("จำ")) runCatching { memoryHub.save(text,"user","brain") }
                setBrainStatus("NEO • กำลังคิด…")
                val routed=coroutineScope {
                    val m=async { runCatching { memoryHub.retrieve(text) }.getOrNull() }
                    val k=async { runCatching { knowledgeHub.retrieve(text) }.getOrNull() }
                    val w=async { if(webSearch.shouldSearch(text)) runCatching { webSearch.search(text) }.getOrNull() else null }
                    Triple(m.await(),k.await(),w.await())
                }
                val memories=routed.first?.memories.orEmpty(); val blocks=mutableListOf<String>(); blocks.addAll(routed.second?.blocks.orEmpty())
                routed.third?.results?.take(3)?.forEachIndexed { i,r -> blocks.add("[WEB ${i+1}: ${r.title}]\n${r.snippet}\nSOURCE: ${r.url}") }
                val answer=runCatching { brain.generate(text,memories,liveConfig,blocks,::setBrainStatus) }.getOrElse { "ขออภัย ระบบสมองมีปัญหาชั่วคราว แต่แอปยังทำงานอยู่ครับ" }
                safeReply(answer.ifBlank { "ผมรับข้อความแล้วครับ แต่สมองยังสร้างคำตอบไม่ได้ ลองอีกครั้งได้เลย" })
            } catch(e:Throwable){ safeReply("เกิดข้อผิดพลาด ${e.javaClass.simpleName} — ลองส่งอีกครั้งครับ") }
            finally { setBrainStatus("LOCAL • NEO พร้อมใช้งาน") }
        }
    }

    private suspend fun safeReply(text:String){ runCatching { appDataDao.insertChat(ChatEntity(role="assistant",text=text)) }; submitMessage?.invoke("NEO: $text"); runCatching { neoTts.speak(text) } }
    override fun onDestroy(){ runCatching{brain.release()}; runCatching{neoTts.shutdown()}; super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NeoScreen(onSend:(String)->Unit,onMic:()->Unit,registerSubmitter:(((String)->Unit)->Unit),registerStatus:(((String)->Unit)->Unit)){
    val messages=remember{mutableStateListOf<String>()}; var input by remember{mutableStateOf("")}; var status by remember{mutableStateOf("LOCAL • เตรียมสมอง 7B")}
    LaunchedEffect(Unit){registerSubmitter{messages.add(it)};registerStatus{status=it}}
    Scaffold(topBar={TopAppBar(title={Column{Text("NEO",fontWeight=FontWeight.Bold);Text("7B • Memory • RAG • Web",style=MaterialTheme.typography.labelSmall)}})}){p->
        Column(Modifier.padding(p).fillMaxSize().imePadding()){
            Surface(Modifier.padding(16.dp),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant){Text("● $status",Modifier.padding(12.dp))}
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){items(messages){NeoMessage(it)}}
            Surface(tonalElevation=3.dp){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){TextField(input,{input=it},Modifier.weight(1f),placeholder={Text("ข้อความถึง NEO")});TextButton(onClick=onMic){Text("🎙")};Button(onClick={val t=input.trim();if(t.isNotBlank()){input="";onSend(t)}},enabled=input.isNotBlank()){Text("ส่ง")}}}
        }
    }
}

@Composable private fun NeoMessage(raw:String){
    val user=raw.startsWith("คุณ:"); val text=raw.substringAfter(":",raw).trim()
    Row(Modifier.fillMaxWidth(),horizontalArrangement=if(user) Arrangement.End else Arrangement.Start){Surface(Modifier.widthIn(max=330.dp),shape=RoundedCornerShape(20.dp),color=if(user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant){Text(text,Modifier.padding(14.dp))}}
}