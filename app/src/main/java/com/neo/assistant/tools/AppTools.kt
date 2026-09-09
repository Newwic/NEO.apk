package com.neo.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri

object AppTools {
    fun openApp(context: Context, keyword: String): Boolean {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val target = apps.firstOrNull { app ->
            val label = pm.getApplicationLabel(app).toString()
            label.contains(keyword, ignoreCase = true) || app.packageName.contains(keyword, ignoreCase = true)
        } ?: return false
        val launch = pm.getLaunchIntentForPackage(target.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return true
    }

    fun openUrl(context: Context, url: String) {
        val normalized = if (url.startsWith("http")) url else "https://$url"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
