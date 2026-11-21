package com.prissma.launcher.engine.java

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

internal const val ANDROID_LWJGL_VERSION = "3.3.3"

data class InstalledJavaRuntime(
    val javaHomeDir: File,
    val libJvmFile: File
)

class JavaRuntimeManager(private val context: Context) {

    companion object {
        const val DEFAULT_JRE21_URL = "AUTO"

        // Enlace directo verificado de OpenJDK 21 ARM64 para Android (39.4 MB)
        private const val DIRECT_JRE21_ARM64_URL =
            "https://github.com/QuestCraftPlusPlus/android-openjdk-build-multiarch/releases/download/jre22-6.0.0/JRE-21.zip"

        private val GITHUB_RELEASE_REPOS = listOf(
            "QuestCraftPlusPlus/android-openjdk-build-multiarch",
            "PojavLauncherTeam/android-openjdk-build-multiarch"
        )
    }

    private val runtimeRootDir: File
        get() = File(context.filesDir, "runtimes/jre21")

    private val runtimeStagingDir: File
        get() = File(context.filesDir, "runtimes/jre21_tmp")

    fun getInstalledRuntime(): InstalledJavaRuntime? {
        val root = runtimeRootDir
        if (!root.exists()) return null
        return findRuntimeInDir(root)
    }

    private fun findRuntimeInDir(dir: File): InstalledJavaRuntime? {
        if (!dir.exists()) return null

        val libJvm = dir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "libjvm.so" }
            ?: return null

        val serverDir = libJvm.parentFile ?: return null
        val libDir = serverDir.parentFile ?: return null
        val javaHome = libDir.parentFile ?: dir
        return InstalledJavaRuntime(javaHomeDir = javaHome, libJvmFile = libJvm)
    }

    /**
     * Respaldo vía API de GitHub si el enlace directo dejara de estar disponible.
     */
    private fun resolveFallbackJre21AssetUrl(): Pair<String, String> {
        for (repo in GITHUB_RELEASE_REPOS) {
            val apiUrl = "https://api.github.com/repos/$repo/releases?per_page=30"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PRISSMA-Launcher/1.0")
            }
            try {
                if (conn.responseCode !in 200..299) continue
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val releases = JSONArray(body)

                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    val assets = release.optJSONArray("assets") ?: continue

                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val name = asset.optString("name", "")
                        val lower = name.lowercase()
                        val downloadUrl = asset.optString("browser_download_url", "")

                        val isArchive = lower.endsWith(".zip") ||
                            lower.endsWith(".tar.xz") ||
                            lower.endsWith(".tar.gz")
                        if (!isArchive || downloadUrl.isBlank()) continue

                        val isJre21Name = lower == "jre-21.zip" ||
                            (lower.contains("21") && (lower.contains("arm64") || lower.contains("aarch64")))

                        if (isJre21Name) {
                            return name to downloadUrl
                        }
                    }
                }
            } catch (_: Exception) {
                // Intenta con el siguiente repositorio
            } finally {
                conn.disconnect()
            }
        }
        error("No se encontró un paquete JRE 21 ARM64 disponible en GitHub.")
    }

    suspend fun installFromUrl(
        downloadUrl: String,
        onProgress: (Float, String) -> Unit
    ): Result<InstalledJavaRuntime> = withContext(Dispatchers.IO) {
        runCatching {
            val (fileName, finalUrl) = if (downloadUrl == DEFAULT_JRE21_URL) {
                onProgress(0.05f, "Conectando para descargar JRE 21 ARM64...")
                "JRE-21.zip" to DIRECT_JRE21_ARM64_URL
            } else {
                downloadUrl.substringAfterLast('/') to downloadUrl.trim()
            }

            try {
                downloadAndExtract(fileName, finalUrl, onProgress)
            } catch (e: Exception) {
                if (downloadUrl == DEFAULT_JRE21_URL) {
                    onProgress(0.08f, "Buscando espejo alternativo de JRE 21 en GitHub...")
                    val (fbName, fbUrl) = resolveFallbackJre21AssetUrl()
                    downloadAndExtract(fbName, fbUrl, onProgress)
                } else {
                    throw e
                }
            }

            getInstalledRuntime()
                ?: error("El paquete descargado no contiene libjvm.so para ARM64.")
        }
    }

    private fun downloadAndExtract(
        fileName: String,
        finalUrl: String,
        onProgress: (Float, String) -> Unit
    ) {
        onProgress(0.10f, "Descargando $fileName...")
        val conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "PRISSMA-Launcher/1.0")
        }

        try {
            if (conn.responseCode !in 200..299) {
                error("Error al descargar JRE 21 (HTTP ${conn.responseCode}).")
            }
            val totalBytes = conn.contentLengthLong
            conn.inputStream.use { rawIn ->
                extractArchiveSafely(rawIn, fileName, totalBytes, onProgress)
            }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun installFromLocalUri(
        uri: Uri,
        onProgress: (Float, String) -> Unit
    ): Result<InstalledJavaRuntime> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(0.05f, "Abriendo paquete JRE 21 seleccionado...")
            val fileName = uri.lastPathSegment ?: "jre21.zip"
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("No se pudo leer el archivo seleccionado.")
            stream.use { rawIn ->
                extractArchiveSafely(rawIn, fileName, -1L, onProgress)
            }

            getInstalledRuntime()
                ?: error("El archivo seleccionado no contiene libjvm.so.")
        }
    }

    private fun extractArchiveSafely(
        inputStream: InputStream,
        fileName: String,
        totalBytes: Long,
        onProgress: (Float, String) -> Unit
    ) {
        val staging = runtimeStagingDir
        staging.deleteRecursively()
        staging.mkdirs()

        val canonicalStaging = staging.canonicalPath
        val lowerName = fileName.lowercase()
        var extractedFiles = 0

        try {
            val buffered = BufferedInputStream(inputStream)

            when {
                lowerName.endsWith(".tar.xz") -> {
                    XZCompressorInputStream(buffered).use { xzIn ->
                        TarArchiveInputStream(xzIn).use { tarIn ->
                            extractedFiles = extractTarEntries(tarIn, staging, canonicalStaging, totalBytes, onProgress)
                        }
                    }
                }
                lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz") -> {
                    GZIPInputStream(buffered).use { gzIn ->
                        TarArchiveInputStream(gzIn).use { tarIn ->
                            extractedFiles = extractTarEntries(tarIn, staging, canonicalStaging, totalBytes, onProgress)
                        }
                    }
                }
                else -> {
                    ZipInputStream(buffered).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(staging, entry.name)
                            if (!outFile.canonicalPath.startsWith(canonicalStaging)) {
                                error("Entrada inválida dentro del archivo ZIP.")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                                outFile.setReadable(true, false)
                                outFile.setExecutable(true, false)
                                extractedFiles++
                                if (extractedFiles % 25 == 0) {
                                    val pct = (0.15f + (extractedFiles % 75) / 100f).coerceIn(0.15f, 0.92f)
                                    onProgress(pct, "Extrayendo JRE 21 ($extractedFiles archivos)...")
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            }

            val stagedRuntime = findRuntimeInDir(staging)
                ?: error("El paquete extraído no contiene libjvm.so para ARM64.")

            stagedRuntime.libJvmFile.setReadable(true, false)
            stagedRuntime.libJvmFile.setExecutable(true, false)

            val finalDir = runtimeRootDir
            finalDir.deleteRecursively()
            if (!staging.renameTo(finalDir)) {
                staging.copyRecursively(finalDir, overwrite = true)
                staging.deleteRecursively()
            }

            onProgress(1.0f, "Runtime JRE 21 ARM64 listo ($extractedFiles archivos).")
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    private fun extractTarEntries(
        tarIn: TarArchiveInputStream,
        staging: File,
        canonicalStaging: String,
        totalBytes: Long,
        onProgress: (Float, String) -> Unit
    ): Int {
        var count = 0
        var entry = tarIn.nextEntry
        while (entry != null) {
            val outFile = File(staging, entry.name)
            if (!outFile.canonicalPath.startsWith(canonicalStaging)) {
                error("Entrada inválida dentro del archivo TAR.")
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else if (!entry.isSymbolicLink && !entry.isLink) {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos -> tarIn.copyTo(fos) }
                outFile.setReadable(true, false)
                outFile.setExecutable(true, false)
                count++
                if (count % 25 == 0) {
                    val pct = if (totalBytes > 0) 0.55f else (0.15f + (count % 75) / 100f)
                    onProgress(pct.coerceIn(0.15f, 0.92f), "Extrayendo JRE 21 ($count archivos)...")
                }
            }
            entry = tarIn.nextEntry
        }
        return count
    }

    private fun bundledLwjglJar(name: String): File {
        val relativePath = "lwjgl/$ANDROID_LWJGL_VERSION/$name"
        // Android vacía codeCacheDir al actualizar la app.
        val target = File(context.codeCacheDir, relativePath)
        if (target.isFile && target.length() > 0) return target

        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            context.assets.open(relativePath).use { it.copyTo(output) }
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        return target
    }

    suspend fun buildClasspath(instanceId: String, clientJarFile: File): String = withContext(Dispatchers.IO) {
        val instanceRoot = File(context.filesDir, "instances/$instanceId")
        val librariesDir = File(instanceRoot, "libraries")
        val jars = mutableListOf<String>()

        if (librariesDir.exists()) {
            val lwjglDir = File(librariesDir, "org/lwjgl")
            val androidModules = setOf("lwjgl", "lwjgl-opengl", "lwjgl-stb", "lwjgl-freetype", "lwjgl-tinyfd")
                .map { "$it-$ANDROID_LWJGL_VERSION.jar" }.toSet()
            librariesDir.walkTopDown()
                .filter { it.isFile && it.extension == "jar" }
                .filterNot { it.startsWith(lwjglDir) && it.name.contains("-$ANDROID_LWJGL_VERSION-natives-") }
                .sortedBy { it.absolutePath }
                .map { if (it.startsWith(lwjglDir) && it.name in androidModules) bundledLwjglJar(it.name) else it }
                .forEach { jars.add(it.absolutePath) }
        }
        if (clientJarFile.exists()) {
            jars.add(clientJarFile.absolutePath)
        }

        jars
            .distinctBy { File(it).name }
            .joinToString(File.pathSeparator)
    }

    fun buildMinecraftArgs(
        instanceId: String,
        resolvedVersion: String,
        playerName: String,
        playerUuid: String,
        accountType: String = "Offline"
    ): Array<String> {
        val instanceRoot = File(context.filesDir, "instances/$instanceId")
        val assetsDir = File(instanceRoot, "assets").apply { mkdirs() }
        val assetIndexId = File(assetsDir, "indexes")
            .listFiles()
            ?.firstOrNull { it.extension == "json" }
            ?.nameWithoutExtension
            ?: resolvedVersion

        val userType = when (accountType.lowercase()) {
            "microsoft" -> "msa"
            "ely.by" -> "mojang"
            else -> "legacy"
        }

        return arrayOf(
            "--username", playerName,
            "--version", resolvedVersion,
            "--gameDir", instanceRoot.absolutePath,
            "--assetsDir", assetsDir.absolutePath,
            "--assetIndex", assetIndexId,
            "--uuid", playerUuid,
            "--accessToken", "0",
            "--userType", userType,
            "--versionType", "PRISSMA"
        )
    }
}
