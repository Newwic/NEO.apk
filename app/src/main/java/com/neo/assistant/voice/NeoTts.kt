package com.neo.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class NeoTts(context: Context) {
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("th", "TH")
                tts?.setSpeechRate(1.0f)
            }
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "neo_reply")
    }

    fun shutdown() = tts?.shutdown()
}
