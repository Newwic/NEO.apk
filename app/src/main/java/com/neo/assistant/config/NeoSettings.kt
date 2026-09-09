package com.neo.assistant.config

import android.content.Context

class NeoSettings(context: Context) {
    private val prefs = context.getSharedPreferences("neo_settings", Context.MODE_PRIVATE)

    var brainUrl: String
        get() = prefs.getString("brain_url", "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080"
        set(value) = prefs.edit().putString("brain_url", value.trim().removeSuffix("/")).apply()

    var pcWorkerUrl: String
        get() = prefs.getString("pc_worker_url", "http://192.168.1.10:8765") ?: "http://192.168.1.10:8765"
        set(value) = prefs.edit().putString("pc_worker_url", value.trim().removeSuffix("/")).apply()
}
