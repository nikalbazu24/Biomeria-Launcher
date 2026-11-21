package com.prissma.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

internal fun betaBuildNumber(tag: String): Long? = Regex("""1\.0\.0-beta\.(\d+)""")
    .matchEntire(tag.trim().removePrefix("v"))?.groupValues?.get(1)?.toLongOrNull()

internal fun newestPreview(releases: Sequence<UpdateInfo>, installedBuild: Long): UpdateInfo? =
    releases.mapNotNull { release -> betaBuildNumber(release.versionTag)?.let { it to release } }
        .filter { (build, _) -> build > installedBuild }
        .maxByOrNull { (build, _) -> build }?.second

class AppUpdater(private val context: Context) {

    companion object {
        private const val REPO = "nikalbazu24/Prissma-Launcher"
        private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases"
    }

    fun getCurrentVersionName(): String {
        return runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    suspend fun checkForUpdate(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val release = JSONObject(fetchReleases("/latest"))
            if (release.optBoolean("prerelease")) return@runCatching null
            val update = parseRelease(release) ?: return@runCatching null
            val remoteBuild = betaBuildNumber(update.versionTag) ?: return@runCatching null
            update.takeIf { remoteBuild > installedBuild() }
        }
    }

    suspend fun checkForPreview(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val releases = JSONArray(fetchReleases("?per_page=100"))
            val previews = (0 until releases.length()).asSequence()
                .map(releases::getJSONObject)
                .filter { it.optBoolean("prerelease") }
                .mapNotNull(::parseRelease)
            newestPreview(previews, installedBuild())
        }
    }

    private fun installedBuild() = PackageInfoCompat.getLongVersionCode(
        context.packageManager.getPackageInfo(context.packageName, 0)
    )

    private fun fetchReleases(path: String): String {
        val connection = (URL(RELEASES_API + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PRISSMA-Launcher")
        }
        return try {
            val status = connection.responseCode
            check(status in 200..299) { "GitHub respondió HTTP $status." }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): UpdateInfo? {
        if (json.optBoolean("draft")) return null
        val tag = json.optString("tag_name").trim().removePrefix("v")
        if (betaBuildNumber(tag) == null) return null
        val assets = json.optJSONArray("assets") ?: return null
        val apkUrl = (0 until assets.length()).asSequence()
            .map(assets::getJSONObject)
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            .map { it.optString("browser_download_url") }
            .firstOrNull { it.startsWith("https://github.com/$REPO/releases/download/") }
            ?: return null
        return UpdateInfo(tag, json.optString("body", "Nueva actualización disponible."), apkUrl)
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

            try {
                verifyUpdate(apkFile)
            } catch (error: Exception) {
                apkFile.delete()
                throw error
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

    @Suppress("DEPRECATION")
    private fun verifyUpdate(apkFile: File) {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        val candidate = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: error("El archivo descargado no es un APK válido.")
        check(candidate.packageName == installed.packageName) {
            "El APK descargado pertenece a otra aplicación."
        }
        check(PackageInfoCompat.getLongVersionCode(candidate) > PackageInfoCompat.getLongVersionCode(installed)) {
            "El APK descargado no es más reciente que la versión instalada."
        }
        val installedSigners = signingCertificates(installed)
        check(installedSigners.isNotEmpty() && installedSigners == signingCertificates(candidate)) {
            "El certificado del APK es distinto al de esta instalación. Se requiere una versión con la misma firma."
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty().toSet()
        } else {
            info.signatures.orEmpty().toSet()
        }
}
