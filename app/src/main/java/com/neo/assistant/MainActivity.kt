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
import com.neo.assistant.dev.LiveConfigClient
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.memory.MemoryHub
import com.neo.assistant.memory.NeoDatabase
import com.neo.assistant.pc.PcWorkerClient
import com.neo.assistant.tools.AppTools
import com.neo.assistant.voice.NeoSpeechRecognizer
import com.neo.assistant.voice.NeoTts
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var neoTts: NeoTts
    private lateinit var settings: NeoSettings
    private lateinit var brain: LocalBrain
    private lateinit var liveClient: LiveConfigClient
    private lateinit var memoryHub: MemoryHub
    @Volatile private var liveConfig = NeoLiveConfig()
    private var submitMessage: ((String) -> Unit)? = null
    private var updateStatus: ((String) -> Unit)? = null
    private var lastBrainStatus: String = "LOCAL • เตรียมสมอง 7B"

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(::onUserMessage)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        neoTts = NeoTts(this)
        settings = NeoSettings(this)
        brain = LocalBrain(this)
        liveClient = LiveConfigClient(settings.pcWorkerUrl)
        memoryHub = MemoryHub(NeoDatabase.get(this).memoryDao())

        lifecycleScope.launch {
            delay(500)
            brain.prepare(::setBrainStatus)
        }

        lifecycleScope.launch {
            while (isActive) {
                liveClient.fetch()?.let { cfg ->
                    if (cfg.revision != liveConfig.revision) liveConfig = cfg
                }
                delay(liveConfig.liveReloadSeconds * 1000)
            }
        }

        setContent {
            MaterialTheme {
                NeoScreen(
                    initialPcUrl = settings.pcWorkerUrl,
                    onSaveSettings = { pcUrl ->
                        settings.pcWorkerUrl = pcUrl
                        liveClient.setWorkerUrl(pcUrl)
                    },
                    onSend = ::onUserMessage,
                    onMic = { speechLauncher.launch(NeoSpeechRecognizer.intent()) },
                    onInstallBrain = { lifecycleScope.launch { brain.prepare(::setBrainStatus) } },
                    registerSubmitter = { submitMessage = it },
                    registerStatus = {
                        updateStatus = it
                        it(lastBrainStatus)
                    }
                )
            }
        }
    }

    private fun setBrainStatus(text: String) {
        lastBrainStatus = text
        runOnUiThread { updateStatus?.invoke(text) }
    }

    private fun onUserMessage(text: String) {
        submitMessage?.invoke("คุณ: $text")
        lifecycleScope.launch {
            if (text.contains("จำ")) {
                memoryHub.save(text, source = "user", destination = "brain")
            }

            Regex("เปิด\\s*(.+)").find(text)?.let { match ->
                if (!text.contains("โปรเจกต์")) {
                    val target = match.groupValues[1].trim()
                    reply(
                        if (AppTools.openApp(this@MainActivity, target)) "เปิด $target ให้แล้วครับ"
                        else "ผมหาแอป $target ไม่เจอครับ"
                    )
                    return@launch
                }
            }

            val cfg = liveConfig
            val isCodingTask = cfg.codingKeywords.any { text.contains(it, ignoreCase = true) }
            if (isCodingTask && settings.pcWorkerUrl.isNotBlank()) {
                val pcResult = PcWorkerClient(settings.pcWorkerUrl).runTask(text)
                if (!pcResult.contains("เชื่อม", ignoreCase = true) && !pcResult.contains("ไม่ได้", ignoreCase = true)) {
                    reply(pcResult)
                    return@launch
                }
            }

            setBrainStatus("MEM • กำลังดึงข้อมูลหลายทางพร้อมกัน…")
            val packet = memoryHub.retrieve(text)
            submitMessage?.invoke("TRACE: ${packet.route} • ${packet.memories.size} รายการ • ${packet.elapsedMs} ms")

            setBrainStatus("LOCAL • NEO 7B กำลังคิด…")
            val answer = brain.generate(text, packet.memories, cfg)
            setBrainStatus("LOCAL • Qwen2.5 7B พร้อมใช้งาน")
            reply(answer)
        }
    }

    private fun reply(text: String) {
        submitMessage?.invoke("${liveConfig.assistantName}: $text")
        neoTts.speak(text)
    }

    override fun onDestroy() {
        brain.release()
        neoTts.shutdown()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeoScreen(
    initialPcUrl: String,
    onSaveSettings: (String) -> Unit,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onInstallBrain: () -> Unit,
    registerSubmitter: (((String) -> Unit) -> Unit),
    registerStatus: (((String) -> Unit) -> Unit)
) {
    val messages = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var pcUrl by remember { mutableStateOf(initialPcUrl) }
    var status by remember { mutableStateOf("LOCAL • เตรียมสมอง 7B") }

    LaunchedEffect(Unit) {
        registerSubmitter { messages.add(it) }
        registerStatus { status = it }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("ตั้งค่า NEO", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("สมอง Local", fontWeight = FontWeight.SemiBold)
                            Text("Qwen2.5 7B Q4 • llama.cpp • ทำงานบนมือถือ", style = MaterialTheme.typography.bodySmall)
                            Text("Memory Hub: recent + category + source → brain แบบขนาน", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = onInstallBrain, modifier = Modifier.fillMaxWidth()) {
                        Text("ติดตั้ง / โหลดสมอง Local 7B")
                    }
                    OutlinedTextField(
                        value = pcUrl,
                        onValueChange = { pcUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("PC Worker URL") },
                        supportingText = { Text("ไม่จำเป็นสำหรับการแชต Local") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    Text("7B ใช้พื้นที่หลาย GB และ RAM มากกว่า 3B แต่ตอบและเขียนโค้ดได้ดีขึ้น", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveSettings(pcUrl)
                    showSettings = false
                }) { Text("บันทึก") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("ปิด") } }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(38.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("N", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("NEO", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text("Local 7B • Routed Memory", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = { TextButton(onClick = { showSettings = true }) { Text("⚙") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("●", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(7.dp))
                    Text(status, style = MaterialTheme.typography.labelMedium)
                }
            }

            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("N", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("มีอะไรให้ NEO ช่วย?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("7B Local AI + Memory Hub ที่ดึงข้อมูลหลายต้นทางพร้อมกัน", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SuggestionChip(onClick = { input = "นายทำอะไรได้บ้าง" }, label = { Text("ทำอะไรได้บ้าง") })
                            SuggestionChip(onClick = { input = "จำข้อมูลนี้ให้หน่อย" }, label = { Text("ความจำ") })
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(messages) { raw -> NeoMessage(raw) }
                }
            }

            Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            TextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("ข้อความถึง NEO") },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    focusedIndicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                maxLines = 5
                            )
                            TextButton(onClick = onMic, contentPadding = PaddingValues(8.dp)) { Text("🎙") }
                            FilledIconButton(
                                onClick = {
                                    if (input.isNotBlank()) {
                                        val text = input.trim()
                                        input = ""
                                        onSend(text)
                                    }
                                },
                                enabled = input.isNotBlank()
                            ) { Text("↑") }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "NEO • Local 7B • ข้อมูลและความจำอยู่บนอุปกรณ์ของคุณ",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun NeoMessage(raw: String) {
    if (raw.startsWith("TRACE:")) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                "↔ ${raw.removePrefix("TRACE:").trim()}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
        return
    }

    val isUser = raw.startsWith("คุณ:")
    val text = raw.substringAfter(":", raw).trim()

    if (isUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                modifier = Modifier.widthIn(max = 310.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Surface(modifier = Modifier.size(32.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Box(contentAlignment = Alignment.Center) {
                    Text("N", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("NEO", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
