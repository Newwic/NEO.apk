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

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(::onUserMessage)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        neoTts = NeoTts(this)
        settings = NeoSettings(this)
        brain = LocalBrain(settings.brainUrl)
        liveClient = LiveConfigClient(settings.pcWorkerUrl)

        lifecycleScope.launch {
            while (isActive) {
                liveClient.fetch()?.let { cfg ->
                    if (cfg.revision != liveConfig.revision) {
                        liveConfig = cfg
                        updateStatus?.invoke("LIVE • config r${cfg.revision} • ${cfg.mode.uppercase()}")
                    }
                }
                delay(liveConfig.liveReloadSeconds * 1000)
            }
        }

        setContent {
            MaterialTheme {
                NeoScreen(
                    initialBrainUrl = settings.brainUrl,
                    initialPcUrl = settings.pcWorkerUrl,
                    onSaveSettings = { brainUrl, pcUrl ->
                        settings.brainUrl = brainUrl; settings.pcWorkerUrl = pcUrl
                        brain.setBaseUrl(brainUrl); liveClient.setWorkerUrl(pcUrl)
                    },
                    onSend = ::onUserMessage,
                    onMic = { speechLauncher.launch(NeoSpeechRecognizer.intent()) },
                    registerSubmitter = { submitMessage = it },
                    registerStatus = { updateStatus = it }
                )
            }
        }
    }

    private fun onUserMessage(text: String) {
        submitMessage?.invoke("คุณ: $text")
        lifecycleScope.launch {
            val dao = NeoDatabase.get(this@MainActivity).memoryDao()
            if (text.contains("จำ")) dao.insert(MemoryEntity(text = text))

            Regex("เปิด\\s*(.+)").find(text)?.let { match ->
                if (!text.contains("โปรเจกต์")) {
                    val target = match.groupValues[1].trim()
                    reply(if (AppTools.openApp(this@MainActivity, target)) "เปิด $target ให้แล้วครับ" else "ผมหาแอป $target ไม่เจอครับ")
                    return@launch
                }
            }

            val cfg = liveConfig
            val isCodingTask = cfg.codingKeywords.any { text.contains(it, ignoreCase = true) }
            if (isCodingTask) {
                reply(PcWorkerClient(settings.pcWorkerUrl).runTask(text))
                return@launch
            }
            reply(brain.generate(text, dao.recent().map { it.text }, cfg))
        }
    }

    private fun reply(text: String) { submitMessage?.invoke("${liveConfig.assistantName}: $text"); neoTts.speak(text) }
    override fun onDestroy() { neoTts.shutdown(); super.onDestroy() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeoScreen(
    initialBrainUrl: String, initialPcUrl: String,
    onSaveSettings: (String, String) -> Unit, onSend: (String) -> Unit, onMic: () -> Unit,
    registerSubmitter: (((String) -> Unit) -> Unit), registerStatus: (((String) -> Unit) -> Unit)
) {
    val messages = remember { mutableStateListOf("NEO: พร้อมใช้งาน • Local First") }
    var input by remember { mutableStateOf("") }; var showSettings by remember { mutableStateOf(false) }
    var brainUrl by remember { mutableStateOf(initialBrainUrl) }; var pcUrl by remember { mutableStateOf(initialPcUrl) }
    var status by remember { mutableStateOf("LOCAL • waiting config") }
    LaunchedEffect(Unit) { registerSubmitter { messages.add(it) }; registerStatus { status = it } }

    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false }, title = { Text("NEO Local Settings") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("llama.cpp บนมือถือ"); OutlinedTextField(brainUrl, { brainUrl = it }, label = { Text("Brain URL") })
            Text("PC Worker / Live Reload"); OutlinedTextField(pcUrl, { pcUrl = it }, label = { Text("PC Worker URL") })
            Text("แก้ config/neo-config.json บน PC แล้วมือถือจะอัปเดตเอง")
        } },
        confirmButton = { Button(onClick = { onSaveSettings(brainUrl, pcUrl); showSettings = false }) { Text("บันทึก") } },
        dismissButton = { TextButton(onClick = { showSettings = false }) { Text("ยกเลิก") } }
    )

    Scaffold(topBar = { TopAppBar(title = { Column { Text("NEO • Local Assistant"); Text(status, style = MaterialTheme.typography.labelSmall) } }, actions = { IconButton(onClick = { showSettings = true }) { Text("⚙") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(messages) { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp)) } }
            }
            Spacer(Modifier.height(10.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("พิมพ์หา NEO...") })
                Spacer(Modifier.width(8.dp)); Button(onClick = onMic) { Text("🎙") }
            }
            Spacer(Modifier.height(8.dp)); Button(Modifier.fillMaxWidth(), onClick = { if (input.isNotBlank()) { val t = input; input = ""; onSend(t) } }) { Text("ส่ง") }
        }
    }
}
