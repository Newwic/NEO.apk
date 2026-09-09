package com.neo.assistant

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.neo.assistant.ai.LocalBrain
import com.neo.assistant.config.NeoSettings
import com.neo.assistant.dev.LiveConfigClient
import com.neo.assistant.dev.NeoLiveConfig
import com.neo.assistant.memory.MemoryEntity
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
    @Volatile private var liveConfig = NeoLiveConfig()
    private var submitMessage: ((String) -> Unit)? = null
    private var updateStatus: ((String) -> Unit)? = null
    private var lastBrainStatus: String = "LOCAL • เตรียมสมอง 3B"

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

        lifecycleScope.launch {
            delay(500)
            brain.prepare(::setBrainStatus)
        }

        lifecycleScope.launch {
            while (isActive) {
                liveClient.fetch()?.let { cfg ->
                    if (cfg.revision != liveConfig.revision) {
                        liveConfig = cfg
                    }
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
            val dao = NeoDatabase.get(this@MainActivity).memoryDao()
            if (text.contains("จำ")) dao.insert(MemoryEntity(text = text))

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

            setBrainStatus("LOCAL • NEO กำลังคิด…")
            val answer = brain.generate(text, dao.recent().map { it.text }, cfg)
            setBrainStatus("LOCAL • Qwen2.5 3B พร้อมใช้งาน")
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
    val messages = remember { mutableStateListOf("NEO: พร้อมใช้งาน • Local First") }
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var pcUrl by remember { mutableStateOf(initialPcUrl) }
    var status by remember { mutableStateOf("LOCAL • เตรียมสมอง 3B") }

    LaunchedEffect(Unit) {
        registerSubmitter { messages.add(it) }
        registerStatus { status = it }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("NEO Local Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("สมอง: Qwen2.5 3B Q4 • llama.cpp • ทำงานในมือถือ")
                    Button(onClick = onInstallBrain, modifier = Modifier.fillMaxWidth()) {
                        Text("ติดตั้ง / โหลดสมอง Local 3B")
                    }
                    Text("PC Worker / Live Reload (ไม่จำเป็นสำหรับแชต Local)")
                    OutlinedTextField(pcUrl, { pcUrl = it }, label = { Text("PC Worker URL") })
                    Text("หลังดาวน์โหลดโมเดลครั้งแรกประมาณ 1.9 GB สามารถคุยกับ NEO แบบออฟไลน์ได้")
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveSettings(pcUrl)
                    showSettings = false
                }) { Text("บันทึก") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("ยกเลิก") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NEO • Local Assistant")
                        Text(status, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { IconButton(onClick = { showSettings = true }) { Text("⚙") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) {
                    Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp)) }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("พิมพ์หา NEO...") }
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onMic) { Text("🎙") }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        val t = input
                        input = ""
                        onSend(t)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("ส่ง") }
        }
    }
}
