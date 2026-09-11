package com.neo.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.neo.assistant.ai.LocalBrain
import com.neo.assistant.ai.ModelManager
import com.neo.assistant.ai.ModelProfile
import com.neo.assistant.config.NeoSettings
import com.neo.assistant.data.AppDataDao
import com.neo.assistant.data.ChatEntity
import com.neo.assistant.dev.LiveConfigClient
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.knowledge.AdaptiveAnswerEngine
import com.neo.assistant.knowledge.KnowledgeHub
import com.neo.assistant.memory.MemoryEntity
import com.neo.assistant.memory.MemoryHub
import com.neo.assistant.memory.NeoDatabase
import com.neo.assistant.tools.AppTools
import com.neo.assistant.tools.LocalFacts
import com.neo.assistant.tools.NeoIdentity
import com.neo.assistant.voice.NeoSpeechRecognizer
import com.neo.assistant.voice.NeoTts
import com.neo.assistant.web.WebSearchClient
import kotlinx.coroutines.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NeoChatMessage(val role: String, val text: String, val streaming: Boolean = false)
enum class NeoSection { CHAT, MODELS, VOICE, MEMORY, TOOLS }

class MainActivity : ComponentActivity() {
    private lateinit var neoTts: NeoTts
    private lateinit var settings: NeoSettings
    private lateinit var brain: LocalBrain
    private lateinit var modelManager: ModelManager
    private lateinit var liveClient: LiveConfigClient
    private lateinit var memoryHub: MemoryHub
    private lateinit var knowledgeHub: KnowledgeHub
    private lateinit var adaptiveEngine: AdaptiveAnswerEngine
    private lateinit var appDataDao: AppDataDao
    private val webSearch = WebSearchClient()
    private val messageQueue = Channel<String>(Channel.UNLIMITED)

    private val messages = mutableStateListOf<NeoChatMessage>()
    private val memories = mutableStateListOf<MemoryEntity>()
    private val status = mutableStateOf("LOCAL • NEO พร้อมใช้งาน")
    private val section = mutableStateOf(NeoSection.CHAT)
    private val activeModel = mutableStateOf(ModelProfile.BALANCED)
    private val modelProgress = mutableStateMapOf<String, Int>()
    private val downloading = mutableStateMapOf<String, Boolean>()
    private val listening = mutableStateOf(false)
    private val autoSpeak = mutableStateOf(false)
    private val webFallback = mutableStateOf(true)

    @Volatile private var liveConfig = NeoLiveConfig()

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening.value = false
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.let(::onUserMessage)
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            messages.add(NeoChatMessage("assistant", "เลือกไฟล์แล้ว: ${uri.lastPathSegment ?: "document"}\nระบบ Knowledge จะเชื่อมการอ่านไฟล์เต็มในเวอร์ชันถัดไป"))
            section.value = NeoSection.CHAT
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        neoTts = NeoTts(this)
        settings = NeoSettings(this)
        brain = LocalBrain(this)
        modelManager = brain.manager()
        activeModel.value = modelManager.selected()
        autoSpeak.value = settings.autoSpeak
        webFallback.value = settings.webFallbackEnabled
        liveClient = LiveConfigClient(settings.pcWorkerUrl)

        val db = NeoDatabase.get(this)
        memoryHub = MemoryHub(db.memoryDao())
        appDataDao = db.appDataDao()
        knowledgeHub = KnowledgeHub(appDataDao)
        adaptiveEngine = AdaptiveAnswerEngine(knowledgeHub, webSearch)

        lifecycleScope.launch {
            runCatching {
                appDataDao.recentChats(60).asReversed().forEach {
                    messages.add(NeoChatMessage(it.role, it.text))
                }
            }
            refreshMemories()
            if (modelManager.isInstalled(activeModel.value)) {
                brain.prepare(::setStatus)
            } else {
                setStatus("MODEL • ${activeModel.value.modelName} ยังไม่ได้ดาวน์โหลด")
            }
        }

        lifecycleScope.launch {
            while (isActive) {
                liveClient.fetch()?.let { liveConfig = it }
                delay(5000)
            }
        }

        lifecycleScope.launch {
            for (text in messageQueue) processUserMessage(text)
        }

        setContent {
            NeoTheme {
                NeoApp(
                    section = section.value,
                    onSection = { section.value = it },
                    messages = messages,
                    status = status.value,
                    inputModel = activeModel.value,
                    profiles = modelManager.profiles,
                    isInstalled = modelManager::isInstalled,
                    modelProgress = modelProgress,
                    downloading = downloading,
                    memories = memories,
                    listening = listening.value,
                    autoSpeak = autoSpeak.value,
                    webFallback = webFallback.value,
                    onSend = ::onUserMessage,
                    onMic = ::startVoice,
                    onModelAction = ::modelAction,
                    onDeleteModel = ::deleteModel,
                    onDeleteMemory = ::deleteMemory,
                    onClearMemories = ::clearMemories,
                    onOpenYouTube = ::openYouTube,
                    onAlarm = ::openAlarm,
                    onFiles = { fileLauncher.launch(arrayOf("*/*")) },
                    onWeb = { AppTools.openUrl(this, "https://www.google.com") },
                    onAutoSpeak = {
                        autoSpeak.value = it
                        settings.autoSpeak = it
                    },
                    onWebFallback = {
                        webFallback.value = it
                        settings.webFallbackEnabled = it
                    }
                )
            }
        }
    }

    private fun startVoice() {
        listening.value = true
        runCatching { speechLauncher.launch(NeoSpeechRecognizer.intent()) }
            .onFailure { listening.value = false }
    }

    private fun setStatus(value: String) {
        runOnUiThread { status.value = value }
    }

    private fun onUserMessage(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) return
        section.value = NeoSection.CHAT
        messages.add(NeoChatMessage("user", text))
        lifecycleScope.launch { messageQueue.send(text) }
    }

    private suspend fun processUserMessage(text: String) {
        try {
            runCatching { appDataDao.insertChat(ChatEntity(role = "user", text = text)) }

            if (handleToolCommand(text)) return
            NeoIdentity.answer(text)?.let { streamReply(it); return }
            LocalFacts.answer(text)?.let { streamReply(it); return }

            if (text.contains("จำ") || text.contains("remember", ignoreCase = true)) {
                runCatching { memoryHub.save(text, "user", "brain") }
                refreshMemories()
            }

            setStatus("MEMORY • กำลังค้นความจำ…")
            val routed = coroutineScope {
                val memory = async { runCatching { memoryHub.retrieve(text) }.getOrNull() }
                val knowledge = async { runCatching { knowledgeHub.retrieve(text) }.getOrNull() }
                val web = async {
                    if (settings.webFallbackEnabled && webSearch.shouldSearch(text)) {
                        setStatus("WEB • กำลังค้นเว็บ…")
                        runCatching { webSearch.search(text) }.getOrNull()
                    } else null
                }
                Triple(memory.await(), knowledge.await(), web.await())
            }

            val memoryItems = routed.first?.memories.orEmpty()
            val blocks = mutableListOf<String>()
            blocks.addAll(routed.second?.blocks.orEmpty())
            runCatching {
                appDataDao.recentChats(10).asReversed().dropLast(1).takeLast(8).forEach {
                    blocks.add("[CHAT] ${if (it.role == "user") "ผู้ใช้" else "NEO"}: ${it.text.take(260)}")
                }
            }
            routed.third?.results?.take(4)?.forEachIndexed { index, result ->
                blocks.add("[WEB ${index + 1}: ${result.title}]\n${result.snippet}\nSOURCE: ${result.url}")
            }

            val answer = runCatching {
                brain.generate(text, memoryItems, liveConfig, blocks, ::setStatus)
            }.getOrElse {
                "NEO มีปัญหาในการประมวลผลรอบนี้ครับ ลองเลือกโมเดลที่เล็กลงหรือส่งข้อความอีกครั้ง"
            }

            val localFailed = answer.isBlank() || answer.contains("มีปัญหาในการประมวลผล")
            if (localFailed && settings.webFallbackEnabled && webSearch.canFallbackSearch(text)) {
                setStatus("WEB • กำลังกู้คำตอบจากเว็บ…")
                val recovered = runCatching { adaptiveEngine.answer(text, forceWeb = true) }.getOrNull()
                if (recovered != null && recovered.text.isNotBlank()) {
                    streamReply(recovered.text)
                    return
                }
            }

            streamReply(answer.ifBlank { "ยังไม่มีคำตอบที่เพียงพอครับ" })
        } catch (_: Throwable) {
            streamReply("NEO ทำงานรอบนี้ไม่สำเร็จครับ แต่แอปยังทำงานอยู่ ลองส่งใหม่อีกครั้ง")
        } finally {
            val profile = activeModel.value
            setStatus(if (modelManager.isInstalled(profile)) "LOCAL • ${profile.label} พร้อมใช้งาน" else "MODEL • ${profile.modelName} ยังไม่ได้ดาวน์โหลด")
        }
    }

    private suspend fun streamReply(text: String) {
        val index = messages.size
        messages.add(NeoChatMessage("assistant", "", streaming = true))
        setStatus("NEO • กำลังตอบ…")
        val builder = StringBuilder()
        text.chunked(3).forEach { chunk ->
            builder.append(chunk)
            messages[index] = NeoChatMessage("assistant", builder.toString(), streaming = true)
            delay(10)
        }
        messages[index] = NeoChatMessage("assistant", text, streaming = false)
        runCatching { appDataDao.insertChat(ChatEntity(role = "assistant", text = text)) }
        if (settings.autoSpeak) runCatching { neoTts.speak(text) }
    }

    private suspend fun handleToolCommand(text: String): Boolean {
        val q = text.lowercase()
        return when {
            (q.contains("เปิด") || q.contains("open")) && q.contains("youtube") -> {
                openYouTube(); streamReply("เปิด YouTube ให้แล้วครับ"); true
            }
            q.contains("ตั้งปลุก") || q.contains("set alarm") -> {
                openAlarm(); streamReply("เปิดหน้าตั้งปลุกให้แล้วครับ"); true
            }
            q.contains("ค้นไฟล์") || q.contains("หาไฟล์") || q.contains("open file") -> {
                fileLauncher.launch(arrayOf("*/*")); streamReply("เปิดตัวเลือกไฟล์ให้แล้วครับ"); true
            }
            else -> false
        }
    }

    private fun openYouTube() {
        if (!AppTools.openApp(this, "youtube")) AppTools.openUrl(this, "https://www.youtube.com")
    }

    private fun openAlarm() {
        runCatching {
            startActivity(Intent(AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun modelAction(profile: ModelProfile) {
        if (downloading[profile.id] == true) return
        if (modelManager.isInstalled(profile)) {
            activeModel.value = profile
            modelManager.select(profile)
            lifecycleScope.launch {
                setStatus("MODEL • กำลังสลับเป็น ${profile.modelName}…")
                val ok = brain.switchModel(profile, ::setStatus)
                if (!ok) setStatus("MODEL • โหลด ${profile.modelName} ไม่สำเร็จ")
            }
            return
        }

        downloading[profile.id] = true
        modelProgress[profile.id] = 0
        lifecycleScope.launch {
            setStatus("MODEL • กำลังดาวน์โหลด ${profile.modelName}…")
            val result = modelManager.download(profile) { percent, _, _ ->
                runOnUiThread { modelProgress[profile.id] = percent }
            }
            downloading[profile.id] = false
            if (result.isSuccess) {
                modelProgress[profile.id] = 100
                modelManager.select(profile)
                activeModel.value = profile
                setStatus("MODEL • ดาวน์โหลดเสร็จ กำลังโหลด ${profile.modelName}…")
                brain.switchModel(profile, ::setStatus)
            } else {
                setStatus("MODEL • ดาวน์โหลดไม่สำเร็จ กด Download เพื่อลองต่อได้")
            }
        }
    }

    private fun deleteModel(profile: ModelProfile) {
        if (activeModel.value == profile) return
        lifecycleScope.launch {
            modelManager.delete(profile)
            modelProgress.remove(profile.id)
            setStatus("MODEL • ลบ ${profile.modelName} แล้ว")
        }
    }

    private suspend fun refreshMemories() {
        val list = runCatching { NeoDatabase.get(this).memoryDao().recent(100) }.getOrDefault(emptyList())
        memories.clear()
        memories.addAll(list)
    }

    private fun deleteMemory(memory: MemoryEntity) {
        lifecycleScope.launch {
            runCatching { NeoDatabase.get(this@MainActivity).memoryDao().deleteById(memory.id) }
            refreshMemories()
        }
    }

    private fun clearMemories() {
        lifecycleScope.launch {
            runCatching { NeoDatabase.get(this@MainActivity).memoryDao().clearAll() }
            refreshMemories()
        }
    }

    override fun onDestroy() {
        messageQueue.close()
        runCatching { brain.release() }
        runCatching { neoTts.shutdown() }
        super.onDestroy()
    }
}

private val NeoBackground = Color(0xFF080A0F)
private val NeoSurface = Color(0xFF11151D)
private val NeoCard = Color(0xFF171C26)
private val NeoCard2 = Color(0xFF202633)
private val NeoAccent = Color(0xFF7C5CFF)
private val NeoBlue = Color(0xFF4AA3FF)
private val NeoGreen = Color(0xFF38D996)
private val NeoMuted = Color(0xFF9298A6)

@Composable
private fun NeoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = NeoBackground,
            surface = NeoSurface,
            surfaceVariant = NeoCard,
            primary = NeoAccent,
            secondary = NeoBlue,
            tertiary = NeoGreen,
            onPrimary = Color.White,
            onBackground = Color(0xFFF7F8FA),
            onSurface = Color(0xFFF7F8FA)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeoApp(
    section: NeoSection,
    onSection: (NeoSection) -> Unit,
    messages: List<NeoChatMessage>,
    status: String,
    inputModel: ModelProfile,
    profiles: List<ModelProfile>,
    isInstalled: (ModelProfile) -> Boolean,
    modelProgress: Map<String, Int>,
    downloading: Map<String, Boolean>,
    memories: List<MemoryEntity>,
    listening: Boolean,
    autoSpeak: Boolean,
    webFallback: Boolean,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onModelAction: (ModelProfile) -> Unit,
    onDeleteModel: (ModelProfile) -> Unit,
    onDeleteMemory: (MemoryEntity) -> Unit,
    onClearMemories: () -> Unit,
    onOpenYouTube: () -> Unit,
    onAlarm: () -> Unit,
    onFiles: () -> Unit,
    onWeb: () -> Unit,
    onAutoSpeak: (Boolean) -> Unit,
    onWebFallback: (Boolean) -> Unit
) {
    Scaffold(
        containerColor = NeoBackground,
        topBar = { NeoTopBar(status, inputModel) },
        bottomBar = { NeoBottomBar(section, onSection) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (section) {
                NeoSection.CHAT -> NeoChatScreen(messages, status, onSend, onMic)
                NeoSection.MODELS -> NeoModelsScreen(inputModel, profiles, isInstalled, modelProgress, downloading, onModelAction, onDeleteModel)
                NeoSection.VOICE -> NeoVoiceScreen(listening, onMic)
                NeoSection.MEMORY -> NeoMemoryScreen(memories, onDeleteMemory, onClearMemories)
                NeoSection.TOOLS -> NeoToolsScreen(autoSpeak, webFallback, onOpenYouTube, onAlarm, onFiles, onWeb, onAutoSpeak, onWebFallback)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeoTopBar(status: String, profile: ModelProfile) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = NeoBackground),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(NeoAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Text("N", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("NEO", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(status.substringAfter("•", status).trim(), color = NeoMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(color = NeoCard2, shape = RoundedCornerShape(14.dp)) {
                    Text(profile.label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = NeoBlue, fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

@Composable
private fun NeoBottomBar(section: NeoSection, onSection: (NeoSection) -> Unit) {
    NavigationBar(containerColor = NeoSurface) {
        NeoSection.entries.forEach { item ->
            val icon = when (item) {
                NeoSection.CHAT -> Icons.Default.Chat
                NeoSection.MODELS -> Icons.Default.Settings
                NeoSection.VOICE -> Icons.Default.Mic
                NeoSection.MEMORY -> Icons.Default.Storage
                NeoSection.TOOLS -> Icons.Default.Build
            }
            val label = when (item) {
                NeoSection.CHAT -> "Chat"
                NeoSection.MODELS -> "Models"
                NeoSection.VOICE -> "Voice"
                NeoSection.MEMORY -> "Memory"
                NeoSection.TOOLS -> "Tools"
            }
            NavigationBarItem(
                selected = section == item,
                onClick = { onSection(item) },
                icon = { Icon(icon, label) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = NeoCard2, selectedIconColor = NeoAccent, selectedTextColor = Color.White, unselectedIconColor = NeoMuted, unselectedTextColor = NeoMuted)
            )
        }
    }
}

@Composable
private fun NeoChatScreen(messages: List<NeoChatMessage>, status: String, onSend: (String) -> Unit, onMic: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(NeoAccent), contentAlignment = Alignment.Center) {
                    Text("N", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(22.dp))
                Text("มีอะไรให้ NEO ช่วย?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Local AI • Memory • Voice • Tools", color = NeoMuted)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(messages) { message -> NeoMessageBubble(message) }
            }
        }

        if (status.contains("กำลัง")) {
            Text(status, Modifier.padding(horizontal = 18.dp, vertical = 4.dp), color = NeoBlue, style = MaterialTheme.typography.labelMedium)
        }

        Surface(color = NeoBackground) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                Surface(Modifier.weight(1f), color = NeoCard, shape = RoundedCornerShape(26.dp)) {
                    Row(Modifier.padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message NEO", color = NeoMuted) },
                            maxLines = 5,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                        )
                        IconButton(onClick = onMic) { Icon(Icons.Default.Mic, "Voice", tint = Color.White) }
                        FilledIconButton(
                            onClick = {
                                val text = input.trim()
                                if (text.isNotEmpty()) { input = ""; onSend(text) }
                            },
                            enabled = input.isNotBlank(),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = NeoAccent, disabledContainerColor = NeoCard2)
                        ) { Icon(Icons.Default.ArrowUpward, "Send") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NeoMessageBubble(message: NeoChatMessage) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (user) {
            Surface(Modifier.widthIn(max = 320.dp), color = NeoCard2, shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)) {
                Text(message.text, Modifier.padding(15.dp), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(NeoAccent), contentAlignment = Alignment.Center) { Text("N", fontWeight = FontWeight.Black) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    Text(message.text + if (message.streaming) " ▍" else "", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun NeoModelsScreen(
    active: ModelProfile,
    profiles: List<ModelProfile>,
    isInstalled: (ModelProfile) -> Boolean,
    progress: Map<String, Int>,
    downloading: Map<String, Boolean>,
    onAction: (ModelProfile) -> Unit,
    onDelete: (ModelProfile) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("AI Models", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("เก็บโมเดลทั้งหมดไว้ในมือถือ แต่ NEO โหลดเข้าหน่วยความจำทีละตัว", color = NeoMuted)
        }
        items(profiles) { profile ->
            val installed = isInstalled(profile)
            val isActive = active == profile
            val isDownloading = downloading[profile.id] == true
            val p = progress[profile.id] ?: 0
            ModelCard(profile, installed, isActive, isDownloading, p, onAction, onDelete)
        }
    }
}

@Composable
private fun ModelCard(
    profile: ModelProfile,
    installed: Boolean,
    active: Boolean,
    downloading: Boolean,
    progress: Int,
    onAction: (ModelProfile) -> Unit,
    onDelete: (ModelProfile) -> Unit
) {
    val emoji = when (profile) { ModelProfile.FAST -> "⚡"; ModelProfile.BALANCED -> "⚖"; ModelProfile.SMART -> "🧠"; ModelProfile.ULTRA -> "🚀" }
    Surface(color = if (active) NeoCard2 else NeoCard, shape = RoundedCornerShape(22.dp), tonalElevation = if (active) 4.dp else 0.dp) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        if (profile == ModelProfile.BALANCED) { Spacer(Modifier.width(8.dp)); Text("DEFAULT", color = NeoGreen, style = MaterialTheme.typography.labelSmall) }
                    }
                    Text(profile.modelName, color = NeoBlue, style = MaterialTheme.typography.bodyMedium)
                }
                Text("~${profile.approxSizeGb} GB", color = NeoMuted)
            }
            Spacer(Modifier.height(10.dp))
            Text(profile.description, color = NeoMuted, style = MaterialTheme.typography.bodySmall)
            if (downloading) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(5.dp))
                Text("Downloading $progress%", color = NeoBlue, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAction(profile) },
                    enabled = !downloading && !active,
                    colors = ButtonDefaults.buttonColors(containerColor = if (installed) NeoAccent else NeoBlue)
                ) {
                    Icon(if (installed) Icons.Default.PlayArrow else Icons.Default.Download, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (active) "Active" else if (installed) "Use" else "Download")
                }
                if (installed && !active) {
                    OutlinedButton(onClick = { onDelete(profile) }) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Delete")
                    }
                }
                if (active) {
                    AssistChip(onClick = {}, label = { Text("Active") }, leadingIcon = { Icon(Icons.Default.Check, null) })
                }
            }
        }
    }
}

@Composable
private fun NeoVoiceScreen(listening: Boolean, onMic: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "voice")
    val pulse by transition.animateFloat(initialValue = 0.45f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(600), repeatMode = RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Voice", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(if (listening) "NEO กำลังฟัง…" else "แตะไมค์แล้วพูดกับ NEO", color = NeoMuted)
        Spacer(Modifier.height(40.dp))
        Row(Modifier.height(90.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(9) { i ->
                val factor = if (i % 2 == 0) pulse else 1f - pulse / 3f
                Box(Modifier.width(7.dp).height((25 + (i % 5) * 12).dp * factor.coerceAtLeast(0.35f)).clip(RoundedCornerShape(8.dp)).background(if (listening) NeoAccent else NeoCard2))
            }
        }
        Spacer(Modifier.height(32.dp))
        FilledIconButton(onClick = onMic, modifier = Modifier.size(76.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = NeoAccent)) {
            Icon(Icons.Default.Mic, "Speak", modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Speech → NEO → Local Qwen → Answer", color = NeoMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NeoMemoryScreen(memories: List<MemoryEntity>, onDelete: (MemoryEntity) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Memory", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("ข้อมูลที่ NEO จำไว้ในเครื่อง", color = NeoMuted)
            }
            if (memories.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear all", color = Color(0xFFFF6B7A)) }
        }
        if (memories.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("NEO ยังไม่มีความจำที่บันทึกไว้", color = NeoMuted) }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(memories, key = { it.id }) { memory ->
                    Surface(color = NeoCard, shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(memory.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text("${memory.category} • ${memory.source}", color = NeoMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { onDelete(memory) }) { Icon(Icons.Default.Delete, "Delete memory", tint = NeoMuted) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NeoToolsScreen(
    autoSpeak: Boolean,
    webFallback: Boolean,
    onYouTube: () -> Unit,
    onAlarm: () -> Unit,
    onFiles: () -> Unit,
    onWeb: () -> Unit,
    onAutoSpeak: (Boolean) -> Unit,
    onWebFallback: (Boolean) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("เครื่องมือที่ NEO ใช้กับมือถือ", color = NeoMuted)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolCard("YouTube", "เปิดแอป", Icons.Default.PlayArrow, Modifier.weight(1f), onYouTube)
                ToolCard("Alarm", "ตั้งปลุก", Icons.Default.Alarm, Modifier.weight(1f), onAlarm)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolCard("Files", "เลือกไฟล์", Icons.Default.Folder, Modifier.weight(1f), onFiles)
                ToolCard("Web", "เปิดเว็บ", Icons.Default.Language, Modifier.weight(1f), onWeb)
            }
        }
        item {
            Surface(color = NeoCard, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Assistant Settings", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    SettingSwitch("พูดคำตอบอัตโนมัติ", "ใช้ Android Text-to-Speech", autoSpeak, onAutoSpeak)
                    HorizontalDivider(color = NeoCard2)
                    SettingSwitch("Web fallback", "ค้นเว็บเมื่อข้อมูลต้องอัปเดต", webFallback, onWebFallback)
                }
            }
        }
        item {
            Surface(color = NeoCard, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, null, tint = NeoAccent)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Privacy", fontWeight = FontWeight.Bold)
                        Text("AI + Memory เก็บในเครื่องเป็นหลัก", color = NeoMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(title: String, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), color = NeoCard, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(NeoCard2), contentAlignment = Alignment.Center) { Icon(icon, null, tint = NeoBlue) }
            Spacer(Modifier.height(14.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = NeoMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, color = NeoMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
