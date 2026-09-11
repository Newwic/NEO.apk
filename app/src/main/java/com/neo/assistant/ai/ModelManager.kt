package com.neo.assistant.ai

import android.content.Context
import com.neo.assistant.config.NeoSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelManager(private val context: Context) {
    private val settings = NeoSettings(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val profiles: List<ModelProfile> = ModelProfile.entries

    fun selected(): ModelProfile = ModelProfile.fromId(settings.selectedModelId)

    fun select(profile: ModelProfile) {
        settings.selectedModelId = profile.id
    }

    fun modelsDir(): File {
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun file(profile: ModelProfile): File = File(modelsDir(), profile.fileName)

    fun isInstalled(profile: ModelProfile): Boolean {
        val file = file(profile)
        return file.exists() && file.length() >= profile.minimumBytes
    }

    fun installedBytes(profile: ModelProfile): Long = file(profile).takeIf { it.exists() }?.length() ?: 0L

    fun delete(profile: ModelProfile): Boolean {
        val target = file(profile)
        val part = File(modelsDir(), "${profile.fileName}.part")
        if (part.exists()) part.delete()
        return !target.exists() || target.delete()
    }

    suspend fun download(
        profile: ModelProfile,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit = { _, _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = file(profile)
            if (isInstalled(profile)) return@runCatching target

            val part = File(modelsDir(), "${profile.fileName}.part")
            val existing = if (part.exists()) part.length() else 0L

            val requestBuilder = Request.Builder().url(profile.downloadUrl)
            if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    error("MODEL_DOWNLOAD_HTTP_${response.code}")
                }
                val body = response.body ?: error("MODEL_DOWNLOAD_EMPTY_BODY")
                val append = existing > 0L && response.code == 206
                if (!append && part.exists()) part.delete()
                val base = if (append) existing else 0L
                val contentLength = body.contentLength().coerceAtLeast(0L)
                val total = if (contentLength > 0L) base + contentLength else 0L

                body.byteStream().use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var downloaded = base
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent, downloaded, total)
                                }
                            }
                        }
                    }
                }
            }

            if (part.length() < profile.minimumBytes) {
                error("MODEL_FILE_TOO_SMALL")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            onProgress(100, target.length(), target.length())
            target
        }
    }
}
