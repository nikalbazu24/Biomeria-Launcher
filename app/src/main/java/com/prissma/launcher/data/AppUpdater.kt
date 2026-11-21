package com.prissma.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

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

@Suppress("DEPRECATION")
internal fun archiveSigningFlags(sdk: Int): Int {
    val modern = if (sdk >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
    // Android 9 collects certificates from an APK only when GET_SIGNATURES is set.
    return modern or PackageManager.GET_SIGNATURES
}

class AppUpdater(private val context: Context) {

    companion object {
        private const val REPO = "nikalbazu24/Prissma-Launcher"
        private val installerLock = Mutex()
        private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
        private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases"
    }

    private val updatePreferences = context.getSharedPreferences("prissma_updates", Context.MODE_PRIVATE)

    suspend fun installedReleaseNotes(version: String): String? = withContext(Dispatchers.IO) {
        if (betaBuildNumber(version) == null) return@withContext null
        val tag = version.trim().removePrefix("v")
        runCatching {
            context.assets.open("release-notes/$tag.md").bufferedReader().use { it.readText().trim() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun hasSeenReleaseNotes(version: String) = updatePreferences.getString("seen_release_notes", null) == version

    fun markReleaseNotesSeen(version: String) {
        updatePreferences.edit().putString("seen_release_notes", version).apply()
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
            .firstOrNull { it.startsWith(DOWNLOAD_PREFIX) }
            ?: return null
        val changelog = if (json.isNull("body")) "" else json.optString("body").trim()
        return UpdateInfo(tag, changelog, apkUrl)
    }

    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent() = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)
    )

    suspend fun downloadAndInstall(
        apkUrl: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        installerLock.withLock {
            runCatching { download(apkUrl, onProgress) }
        }
    }

    private suspend fun download(apkUrl: String, onProgress: (Float) -> Unit) {
        require(apkUrl.startsWith(DOWNLOAD_PREFIX)) { "Origen de actualización inválido." }
        check(canInstall()) { "Permite instalar apps de PRISSMA para continuar." }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }
            ?.forEach { it.delete() }
        val apkFile = File.createTempFile("prissma-", ".apk", directory)
        var installOpened = false
        try {

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
                var lastPercentage = -1
                val current = currentCoroutineContext()
                val buffer = ByteArray(8192)

                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        var read = input.read(buffer)
                        while (read != -1) {
                            current.ensureActive()
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percentage = (downloaded * 100L / totalBytes).coerceIn(0, 100).toInt()
                            if (percentage != lastPercentage) {
                                lastPercentage = percentage
                                onProgress(percentage / 100f)
                            }
                            read = input.read(buffer)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            verifyUpdate(apkFile)

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
                installOpened = true
            }
        } finally {
            if (!installOpened) apkFile.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyUpdate(apkFile: File) {
        val packageManager = context.packageManager
        val flags = archiveSigningFlags(Build.VERSION.SDK_INT)
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
        val candidateSigners = signingCertificates(candidate)
        check(installedSigners.isNotEmpty()) {
            "Android no pudo leer el certificado de la versión instalada (${installed.versionName})."
        }
        check(candidateSigners.isNotEmpty()) {
            "Android no pudo leer el certificado de la APK descargada (${candidate.versionName})."
        }
        check(installedSigners == candidateSigners) {
            "Los certificados de firma son distintos. " +
                "Instalada ${installed.versionName} (${certificateFingerprints(installedSigners)}). " +
                "Descargada ${candidate.versionName} (${certificateFingerprints(candidateSigners)})."
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty().toSet()
        } else {
            info.signatures.orEmpty().toSet()
        }

    private fun certificateFingerprints(signatures: Set<Signature>) = signatures.map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .take(6).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }.sorted().joinToString(", ")
}
