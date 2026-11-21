package com.prissma.launcher.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionTag: String,
    val changelog: String,
    val apkDownloadUrl: String
)

class AppUpdater(private val context: Context) {

    companion object {
        private const val REPO = "nikalbazu24/Prissma-Launcher"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
    }

    fun getCurrentVersionName(): String {
        return runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PRISSMA-Launcher")
            }

            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val remoteTag = json.optString("tag_name", "").removePrefix("v").trim()
                val currentVersion = getCurrentVersionName().removePrefix("v").trim()

                if (remoteTag.isBlank() || remoteTag == currentVersion) {
                    return@runCatching null
                }

                val assets = json.optJSONArray("assets") ?: return@runCatching null
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "").lowercase()
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }

                if (apkUrl.isBlank()) return@runCatching null

                UpdateInfo(
                    versionTag = remoteTag,
                    changelog = json.optString("body", "Nueva actualización disponible."),
                    apkDownloadUrl = apkUrl
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    suspend fun downloadAndInstall(
        apkUrl: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val apkFile = File(context.cacheDir, "prissma_update.apk")
            if (apkFile.exists()) apkFile.delete()

            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "PRISSMA-Launcher")
            }

            try {
                if (conn.responseCode !in 200..299) {
                    error("Error descargando actualización (HTTP ${conn.responseCode})")
                }
                val totalBytes = conn.contentLengthLong.coerceAtLeast(1L)
                var downloaded = 0L
                val buffer = ByteArray(8192)

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        var read = input.read(buffer)
                        while (read != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                            read = input.read(buffer)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(installIntent)
            }
        }
    }
}
