package com.neo.assistant.voice

import android.content.Intent
import android.speech.RecognizerIntent

object NeoSpeechRecognizer {
    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "พูดกับ NEO")
    }
}
