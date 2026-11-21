package com.prissma.launcher.engine.java

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class PreparedJavaSession(
    val instanceId: String,
    val instanceTitle: String,
    val resolvedVersion: String,
    val playerName: String,
    val playerUuid: String,
    val accountType: String,
    val mainClass: String,
    val clientJarFile: File,
    val librariesDownloaded: Int,
    val installedModsCount: Int
)

class MojangDownloader(private val context: Context) {

    private val manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    suspend fun prepareJavaInstance(
        instanceId: String,
        instanceTitle: String,
        requestedVersion: String,
        playerName: String,
        accountType: String,
        onProgress: (Float, String) -> Unit
    ): Result<PreparedJavaSession> = withContext(Dispatchers.IO) {
        runCatching {
            val instanceRoot = File(context.filesDir, "instances/$instanceId").apply { mkdirs() }
            val versionsDir = File(instanceRoot, "versions").apply { mkdirs() }
            val librariesDir = File(instanceRoot, "libraries").apply { mkdirs() }
            val assetIndexesDir = File(instanceRoot, "assets/indexes").apply { mkdirs() }
            val modsDir = File(instanceRoot, "mods").apply { mkdirs() }

            onProgress(0.05f, "Conectando con servidores oficiales de Mojang...")
            val manifestJson = JSONObject(httpGetText(manifestUrl))
            val latestRelease = manifestJson.getJSONObject("latest").getString("release")
            val versionsArray = manifestJson.getJSONArray("versions")

            // Busca la versión pedida; si no existe en Mojang (ej. 26.3), usa la última estable
            var matchedVersionObj: JSONObject? = null
            var fallbackLatestObj: JSONObject? = null

            for (i in 0 until versionsArray.length()) {
                val entry = versionsArray.getJSONObject(i)
                val id = entry.getString("id")
                if (id.equals(requestedVersion.trim(), ignoreCase = true)) {
                    matchedVersionObj = entry
                    break
                }
                if (id == latestRelease) {
                    fallbackLatestObj = entry
                }
            }

            val targetEntry = matchedVersionObj ?: fallbackLatestObj
                ?: error("No se encontró ninguna versión disponible en Mojang.")

            val resolvedVersion = targetEntry.getString("id")
            val versionMetaUrl = targetEntry.getString("url")

            onProgress(0.12f, "Descargando ficha técnica de Minecraft $resolvedVersion...")
            val versionFolder = File(versionsDir, resolvedVersion).apply { mkdirs() }
            val versionJsonFile = File(versionFolder, "$resolvedVersion.json")
            val versionJsonText = httpGetText(versionMetaUrl)
            versionJsonFile.writeText(versionJsonText, Charsets.UTF_8)

            val versionMeta = JSONObject(versionJsonText)
            val mainClass = versionMeta.optString("mainClass", "net.minecraft.client.main.Main")

            // 1. Descargar el archivo principal del juego (client.jar)
            val clientInfo = versionMeta.getJSONObject("downloads").getJSONObject("client")
            val clientUrl = clientInfo.getString("url")
            val clientExpectedSize = clientInfo.optLong("size", 0L)
            val clientJarFile = File(versionFolder, "$resolvedVersion.jar")

            if (!clientJarFile.exists() || (clientExpectedSize > 0 && clientJarFile.length() != clientExpectedSize)) {
                onProgress(0.20f, "Descargando Minecraft $resolvedVersion (client.jar)...")
                downloadBinary(clientUrl, clientJarFile)
            }

            // 2. Descargar índice de sonidos y texturas (assets)
            val assetIndexObj = versionMeta.getJSONObject("assetIndex")
            val assetId = assetIndexObj.getString("id")
            val assetUrl = assetIndexObj.getString("url")
            val assetIndexFile = File(assetIndexesDir, "$assetId.json")
            if (!assetIndexFile.exists()) {
                onProgress(0.35f, "Descargando mapa de recursos ($assetId)...")
                assetIndexFile.writeText(httpGetText(assetUrl), Charsets.UTF_8)
            }

            // 3. Descargar librerías Java necesarias
            val librariesArray = versionMeta.getJSONArray("libraries")
            val artifactsToDownload = mutableListOf<Pair<String, File>>()

            for (i in 0 until librariesArray.length()) {
                val lib = librariesArray.getJSONObject(i)
                if (!isLibraryAllowedOnLinuxArm(lib)) continue

                val downloads = lib.optJSONObject("downloads") ?: continue
                val artifact = downloads.optJSONObject("artifact") ?: continue
                val libUrl = artifact.getString("url")
                val libPath = artifact.getString("path")
                val libSize = artifact.optLong("size", 0L)
                val destFile = File(librariesDir, libPath)

                if (!destFile.exists() || (libSize > 0 && destFile.length() != libSize)) {
                    artifactsToDownload.add(libUrl to destFile)
                }
            }

            val totalLibs = artifactsToDownload.size
            artifactsToDownload.forEachIndexed { index, (url, dest) ->
                val fraction = 0.40f + (0.55f * ((index + 1).toFloat() / totalLibs.coerceAtLeast(1)))
                onProgress(fraction, "Descargando librería ${index + 1}/$totalLibs: ${dest.name}")
                dest.parentFile?.mkdirs()
                downloadBinary(url, dest)
            }

            // 4. Generar UUID estándar de Mojang para modo Offline / No Premium
            val offlineUuid = UUID.nameUUIDFromBytes(
                "OfflinePlayer:$playerName".toByteArray(Charsets.UTF_8)
            ).toString()

            val modsCount = modsDir.listFiles()?.count { it.isFile && it.extension == "jar" } ?: 0

            onProgress(1.0f, "¡Archivos listos! Preparando ventana de juego...")

            PreparedJavaSession(
                instanceId = instanceId,
                instanceTitle = instanceTitle,
                resolvedVersion = resolvedVersion,
                playerName = playerName,
                playerUuid = offlineUuid,
                accountType = accountType,
                mainClass = mainClass,
                clientJarFile = clientJarFile,
                librariesDownloaded = totalLibs,
                installedModsCount = modsCount
            )
        }
    }

    private fun isLibraryAllowedOnLinuxArm(libObj: JSONObject): Boolean {
        val rules = libObj.optJSONArray("rules") ?: return true
        var allowed = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val action = rule.getString("action")
            val osObj = rule.optJSONObject("os")
            if (osObj == null) {
                allowed = (action == "allow")
            } else {
                val osName = osObj.optString("name", "")
                if (osName == "linux") {
                    allowed = (action == "allow")
                }
            }
        }
        return allowed
    }

    private fun httpGetText(urlString: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "PRISSMA-Launcher/0.5")
        }
        return try {
            if (conn.responseCode !in 200..299) {
                error("Error conectando con Mojang (HTTP ${conn.responseCode})")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadBinary(urlString: String, destination: File) {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "PRISSMA-Launcher/0.5")
        }
        try {
            if (conn.responseCode !in 200..299) {
                error("Error descargando ${destination.name} (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
