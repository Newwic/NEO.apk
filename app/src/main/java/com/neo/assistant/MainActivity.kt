package com.neo.assistant

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.neo.assistant.ai.LocalBrain
import com.neo.assistant.config.NeoSettings
import com.neo.assistant.data.AppDataDao
import com.neo.assistant.data.ChatEntity
import com.neo.assistant.dev.LiveConfigClient
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.knowledge.AdaptiveAnswerEngine
import com.neo.assistant.knowledge.KnowledgeHub
import com.neo.assistant.memory.MemoryHub
import com.neo.assistant.memory.NeoDatabase
import com.neo.assistant.tools.LocalFacts
import com.neo.assistant.tools.NeoIdentity
import com.neo.assistant.voice.NeoSpeechRecognizer
import com.neo.assistant.voice.NeoTts
import com.neo.assistant.web.WebSearchClient
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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
    private lateinit var adaptiveEngine: AdaptiveAnswerEngine
    private lateinit var appDataDao: AppDataDao

    private val webSearch = WebSearchClient()
    private val messageQueue = Channel<String>(Channel.UNLIMITED)
    @Volatile private var liveConfig = NeoLiveConfig()
    private var submitMessage: ((String) -> Unit)? = null
    private var updateStatus: ((String) -> Unit)? = null
    private var lastBrainStatus = "LOCAL • เตรียมสมอง 7B"

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.let(::onUserMessage)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        neoTts = NeoTts(this)
        settings = NeoSettings(this)
        brain = LocalBrain(this)
        liveClient = LiveConfigClient(settings.pcWorkerUrl)
        val db = NeoDatabase.get(this)
        memoryHub = MemoryHub(db.memoryDao())
        appDataDao = db.appDataDao()
        knowledgeHub = KnowledgeHub(appDataDao)
        adaptiveEngine = AdaptiveAnswerEngine(knowledgeHub, webSearch)

        lifecycleScope.launch {
            delay(300)
            brain.prepare(::setBrainStatus)
        }
        lifecycleScope.launch {
            while (isActive) {
                liveClient.fetch()?.let { liveConfig = it }
                delay(3000)
            }
        }

        // One consumer guarantees FIFO processing: every user message gets one turn in order.
        lifecycleScope.launch {
            for (text in messageQueue) {
                processUserMessage(text)
            }
        }

        setContent {
            NeoTheme {
                NeoScreen(
                    onSend = ::onUserMessage,
                    onMic = { speechLauncher.launch(NeoSpeechRecognizer.intent()) },
                    registerSubmitter = { submitMessage = it },
                    registerStatus = {
                        updateStatus = it
                        it(lastBrainStatus)
                    }
                )
            }
        }

        lifecycleScope.launch {
            delay(300)
            runCatching {
                appDataDao.recentChats(50).asReversed().forEach {
                    submitMessage?.invoke((if (it.role == "user") "คุณ: " else "NEO: ") + it.text)
                }
            }
        }
    }

    private fun setBrainStatus(s: String) {
        lastBrainStatus = s
        runOnUiThread { updateStatus?.invoke(s) }
    }

    private fun onUserMessage(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) return
        submitMessage?.invoke("คุณ: $text")
        lifecycleScope.launch { messageQueue.send(text) }
    }

    private suspend fun processUserMessage(text: String) {
        try {
            runCatching { appDataDao.insertChat(ChatEntity(role = "user", text = text)) }

            NeoIdentity.answer(text)?.let {
                safeReply(it)
                return
            }
            LocalFacts.answer(text)?.let {
                safeReply(it)
                return
            }

            if (text.contains("จำ")) runCatching { memoryHub.save(text, "user", "brain") }

            // Local-first architecture: only fetch the web up front for genuinely fresh/current queries.
            // Stable knowledge is answered by Qwen first; if Qwen fails, verified web recovery runs below.
            setBrainStatus("NEO • กำลังคิด…")
            val routed = coroutineScope {
                val m = async { runCatching { memoryHub.retrieve(text) }.getOrNull() }
                val k = async { runCatching { knowledgeHub.retrieve(text) }.getOrNull() }
                val w = async {
                    if (webSearch.shouldSearch(text)) runCatching { webSearch.search(text) }.getOrNull() else null
                }
                Triple(m.await(), k.await(), w.await())
            }

            val memories = routed.first?.memories.orEmpty()
            val blocks = mutableListOf<String>()
            blocks.addAll(routed.second?.blocks.orEmpty())

            // Sliding conversation context for follow-up questions such as "แล้วล่ะ" / "ทำไม" / "ต่อ".
            runCatching {
                appDataDao.recentChats(10).asReversed().dropLast(1).takeLast(8).forEach {
                    blocks.add("[CHAT] ${if (it.role == "user") "ผู้ใช้" else "NEO"}: ${it.text.take(220)}")
                }
            }

            // Fresh data is supplied as evidence. Local Qwen synthesizes it instead of exposing raw snippets.
            routed.third?.results?.take(3)?.forEachIndexed { i, r ->
                blocks.add("[WEB ${i + 1}: ${r.title}]\n${r.snippet}\nSOURCE: ${r.url}")
            }

            val answer = runCatching {
                brain.generate(text, memories, liveConfig, blocks, ::setBrainStatus)
            }.getOrElse {
                "ขออภัย ระบบสมองมีปัญหาชั่วคราว แต่แอปยังทำงานอยู่ครับ"
            }

            val failedLocal = answer.isBlank() ||
                answer.contains("สมอง Local ยังสร้างคำตอบไม่ได้") ||
                answer.contains("ประมวลผล Local ไม่สำเร็จ") ||
                answer.contains("ระบบสมองมีปัญหา") ||
                answer.contains("[[NEED_WEB]]")

            if (failedLocal && webSearch.canFallbackSearch(text)) {
                setBrainStatus("WEB • Local ตอบไม่ได้ กำลังหาหลักฐานที่ตรงคำถาม…")
                val recovered = runCatching { adaptiveEngine.answer(text, forceWeb = true) }.getOrNull()
                if (recovered != null && recovered.text.isNotBlank()) {
                    safeReply(recovered.text)
                    return
                }
            }

            safeReply(answer.ifBlank { "ยังไม่พบข้อมูลที่เพียงพอสำหรับตอบคำถามนี้ครับ" })
        } catch (e: Throwable) {
            safeReply("เกิดข้อผิดพลาด ${e.javaClass.simpleName} — ลองส่งอีกครั้งครับ")
        } finally {
            setBrainStatus(if (messageQueue.isEmpty) "LOCAL • NEO พร้อมใช้งาน" else "NEO • มีคำถามรอตอบ…")
        }
    }

    private suspend fun safeReply(text: String) {
        runCatching { appDataDao.insertChat(ChatEntity(role = "assistant", text = text)) }
        submitMessage?.invoke("NEO: $text")
        runCatching { neoTts.speak(text) }
    }

    override fun onDestroy() {
        messageQueue.close()
        runCatching { brain.release() }
        runCatching { neoTts.shutdown() }
        super.onDestroy()
    }
}

private val NeoDark = Color(0xFF101214)
private val NeoSurface = Color(0xFF181B1F)
private val NeoCard = Color(0xFF20242A)
private val NeoGreen = Color(0xFF22C55E)

@Composable
private fun NeoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = NeoDark,
            surface = NeoSurface,
            surfaceVariant = NeoCard,
            primary = NeoGreen,
            onPrimary = Color.Black,
            onBackground = Color(0xFFF4F4F5),
            onSurface = Color(0xFFF4F4F5)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeoScreen(
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    registerSubmitter: (((String) -> Unit) -> Unit),
    registerStatus: (((String) -> Unit) -> Unit)
) {
    val messages = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("LOCAL • เตรียมสมอง 7B") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        registerSubmitter { messages.add(it) }
        registerStatus { status = it }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        containerColor = NeoDark,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NeoDark),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(NeoGreen), contentAlignment = Alignment.Center) {
                            Text("N", color = Color.Black, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text("NEO", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(if (status.contains("พร้อม")) NeoGreen else Color(0xFFF59E0B)))
                                Spacer(Modifier.width(6.dp))
                                Text(status.replace("LOCAL • ", "").replace("NEO • ", ""), style = MaterialTheme.typography.labelSmall, color = Color(0xFFA1A1AA))
                            }
                        }
                    }
                }
            )
        }
    ) { p ->
        Column(Modifier.padding(p).fillMaxSize().imePadding()) {
            if (messages.isEmpty()) EmptyNeoState()
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) { items(messages) { NeoMessage(it) } }
            Surface(color = NeoDark, shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(26.dp), color = NeoCard) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 6.dp)) {
                            TextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("ถาม NEO ได้ทุกเรื่อง", color = Color(0xFF71717A)) },
                                maxLines = 5,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                )
                            )
                            IconButton(onClick = onMic) { Icon(Icons.Default.Mic, "ไมโครโฟน", tint = Color(0xFFD4D4D8)) }
                            FilledIconButton(
                                onClick = {
                                    val t = input.trim()
                                    if (t.isNotBlank()) {
                                        input = ""
                                        onSend(t)
                                        scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
                                    }
                                },
                                enabled = input.isNotBlank(),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (input.isNotBlank()) NeoGreen else Color(0xFF3F3F46), contentColor = Color.Black)
                            ) { Icon(Icons.Default.ArrowUpward, "ส่ง") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNeoState() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(62.dp).clip(CircleShape).background(NeoGreen), contentAlignment = Alignment.Center) {
            Text("N", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color.Black)
        }
        Spacer(Modifier.height(18.dp))
        Text("มีอะไรให้ NEO ช่วย?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text("Local 7B • Memory • Knowledge • Web", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8B8B94))
    }
}

@Composable
private fun NeoMessage(raw: String) {
    val user = raw.startsWith("คุณ:")
    val text = raw.substringAfter(":", raw).trim()
    if (user) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(modifier = Modifier.widthIn(max = 310.dp), shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp), color = Color(0xFF2B3036)) {
                Text(text, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), color = Color(0xFFF4F4F5), style = MaterialTheme.typography.bodyLarge)
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(NeoGreen), contentAlignment = Alignment.Center) {
                Text("N", fontWeight = FontWeight.Black, color = Color.Black, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f).padding(top = 3.dp, end = 18.dp), color = Color(0xFFE4E4E7), style = MaterialTheme.typography.bodyLarge)
        }
    }
}