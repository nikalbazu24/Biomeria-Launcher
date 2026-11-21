package com.prissma.launcher.engine.java

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal val experimentalVersions = setOf("1.20.4", "1.20.1")
internal val supportedVersions =
    (11 downTo 1).map { "1.21.$it" } + listOf("1.21", "1.20.6", "1.20.5") + experimentalVersions

internal fun supportsFabric(version: String) = version in supportedVersions && version !in experimentalVersions

internal data class MinecraftCatalogEntry(val version: String, val channel: String) {
    val playable: Boolean get() = channel == "release" && version in supportedVersions
}

internal fun minecraftCatalog(manifest: JSONObject): List<MinecraftCatalogEntry> =
    manifest.getJSONArray("versions").objects()
        .map { MinecraftCatalogEntry(it.getString("id"), it.getString("type")) }
        .distinctBy { it.version }
internal const val DEFAULT_GAME_VERSION = "1.21.4"
private val safeId = Regex("[A-Za-z0-9_-]{1,100}")
private val safeCoordinate = Regex("[A-Za-z0-9._+-]+")

internal data class PreparedJavaGame(
    val mainClass: String,
    val clientJar: File,
    val libraries: List<File>,
    val assetsDir: File,
    val assetIndexId: String
)

internal class MojangDownloader(context: Context) {
    private val filesDir = context.filesDir
    private val shared = File(filesDir, "minecraft")
    private val assetsDir = File(shared, "assets")
    private val cachedManifest = File(shared, "version-manifest.json")
    private val mojangManifest = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private val fabricMeta = "https://meta.fabricmc.net/v2/versions/loader"

    suspend fun catalog(refresh: Boolean = false): List<MinecraftCatalogEntry> = withContext(Dispatchers.IO) {
        val manifest = runCatching { versionManifest(refresh) }.getOrElse { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            return@withContext supportedVersions.map { MinecraftCatalogEntry(it, "release") }
        }
        minecraftCatalog(manifest)
    }

    private fun versionManifest(refresh: Boolean = false): JSONObject {
        val cached = runCatching { JSONObject(cachedManifest.readText()) }.getOrNull()
        if (!refresh && cached != null && System.currentTimeMillis() - cachedManifest.lastModified() < 21_600_000L) {
            return cached
        }
        return try {
            val text = httpText(mojangManifest)
            val fresh = JSONObject(text)
            fresh.getJSONArray("versions")
            shared.mkdirs()
            val temporary = File.createTempFile("versions-", ".json", shared)
            try {
                temporary.writeText(text)
                Files.move(temporary.toPath(), cachedManifest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
            fresh
        } catch (failure: Exception) {
            cached ?: throw failure
        }
    }

    fun prepared(instanceId: String, version: String, loader: String): PreparedJavaGame? = runCatching {
        val ready = JSONObject(descriptor(instanceId, version, loader).readText())
        val client = File(ready.getString("client"))
        val librariesJson = ready.getJSONArray("libraries")
        val libraries = (0 until librariesJson.length()).map { File(librariesJson.getString(it)) }
        val assetIndexId = ready.getString("assetIndexId")
        check(client.isFile && libraries.isNotEmpty() && libraries.all { it.isFile })
        check(File(assetsDir, "indexes/$assetIndexId.json").isFile)
        PreparedJavaGame(ready.getString("mainClass"), client, libraries, assetsDir, assetIndexId)
    }.getOrNull()

    suspend fun prepare(
        instanceId: String,
        version: String,
        loader: String,
        progress: (Float, String) -> Unit
    ): PreparedJavaGame = withContext(Dispatchers.IO) {
        prepared(instanceId, version, loader)?.let { return@withContext it }
        val marker = descriptor(instanceId, version, loader)
        progress(.04f, "Consultando la versión")
        val releases = versionManifest().getJSONArray("versions").objects()
        val entry = releases.first { it.getString("id") == version && it.getString("type") == "release" }
        val base = File(shared, "versions/$version")
        val metadata = File(base, "$version.json")
        verifiedDownload(entry.getString("url"), metadata, entry.getString("sha1"))
        val versionMeta = JSONObject(metadata.readText())

        val instanceRoot = File(filesDir, "instances/$instanceId")
        val clientInfo = versionMeta.getJSONObject("downloads").getJSONObject("client")
        val client = File(base, "$version.jar")
        val artifacts = libraries(versionMeta, instanceRoot).toMutableList()
        if (version in experimentalVersions) {
            val reference = releases.first { it.getString("id") == DEFAULT_GAME_VERSION }
            val referenceFile = File(shared, "versions/$DEFAULT_GAME_VERSION/$DEFAULT_GAME_VERSION.json")
            verifiedDownload(reference.getString("url"), referenceFile, reference.getString("sha1"))
            val lwjgl = libraries(JSONObject(referenceFile.readText()), instanceRoot)
                .filter { it.coordinate.startsWith("org.lwjgl:") }
            artifacts.removeAll { it.coordinate.startsWith("org.lwjgl:") }
            artifacts.addAll(lwjgl)
        }
        val mainClass = if (loader.startsWith("Fabric")) {
            val pinned = loader.substringAfter(' ', "").takeIf { it.isNotBlank() }
            val fabricVersion = pinned ?: JSONArray(httpText("$fabricMeta/$version")).objects()
                .map { it.getJSONObject("loader") }.first { it.optBoolean("stable") }.getString("version")
            require(safeCoordinate.matches(fabricVersion) && !fabricVersion.startsWith('.'))
            val profile = JSONObject(httpText("$fabricMeta/$version/$fabricVersion/profile/json"))
            check(profile.getString("inheritsFrom") == version)
            val fabricLibraries = profile.getJSONArray("libraries").objects().map { library ->
                val coordinate = library.getString("name")
                val path = mavenPath(coordinate)
                val repository = library.getString("url")
                require(repository in setOf("https://maven.fabricmc.net/", "https://libraries.minecraft.net/"))
                val url = repository + path
                val sha1 = library.optString("sha1").ifEmpty { httpText("$url.sha1").trim().substringBefore(' ') }
                Artifact(url, File(shared, "fabric/$path"), sha1, coordinate = coordinate)
            }
            // Fabric rejects duplicate class definitions, even if its newer ASM JAR comes first.
            val fabricModules = fabricLibraries.map { mavenModule(it.coordinate) }.toSet()
            artifacts.removeAll { mavenModule(it.coordinate) in fabricModules }
            artifacts.addAll(0, fabricLibraries)
            check(profile.getString("mainClass") == "net.fabricmc.loader.impl.launch.knot.KnotClient")
            profile.getString("mainClass")
        } else versionMeta.getString("mainClass")

        val downloads = artifacts + Artifact(clientInfo.getString("url"), client, clientInfo.getString("sha1"),
            File(instanceRoot, "versions/$version/$version.jar"))
        progress(.10f, "Descargando cliente y bibliotecas")
        var completed = 0
        for (batch in downloads.chunked(8)) {
            coroutineScope {
                batch.map { artifact ->
                    async { verifiedDownload(artifact.url, artifact.file, artifact.sha1, artifact.previous) }
                }.awaitAll()
            }
            completed += batch.size
            progress(.10f + .42f * completed / downloads.size, "Cliente y bibliotecas · $completed/" + downloads.size)
        }

        val index = MojangAssets(assetsDir, ::download).prepare(versionMeta.getJSONObject("assetIndex")) { done, total ->
            progress(.52f + .47f * done / total.coerceAtLeast(1), "Recursos · $done/$total")
        }
        val libraries = artifacts.map { it.file }
        val ready = JSONObject()
            .put("mainClass", mainClass)
            .put("client", client.absolutePath)
            .put("libraries", JSONArray(libraries.map { it.absolutePath }))
            .put("assetIndexId", index)
        val temporary = File.createTempFile("ready-", ".json", checkNotNull(marker.parentFile).apply { mkdirs() })
        try {
            temporary.writeText(ready.toString())
            Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
        progress(1f, "Minecraft listo")
        PreparedJavaGame(mainClass, client, libraries, assetsDir, index)
    }

    private fun libraries(metadata: JSONObject, instanceRoot: File): List<Artifact> =
        metadata.getJSONArray("libraries").objects().mapNotNull { library ->
            if (!isLibraryAllowedOnLinux(library) || library.optString("name").contains(":natives-")) return@mapNotNull null
            val artifact = library.optJSONObject("downloads")?.optJSONObject("artifact") ?: return@mapNotNull null
            val path = artifact.getString("path")
            Artifact(
                artifact.getString("url"), File(shared, "libraries/$path"), artifact.getString("sha1"),
                File(instanceRoot, "libraries/$path"), library.getString("name")
            )
        }

    private fun descriptor(instanceId: String, version: String, loader: String): File {
        require(safeId.matches(instanceId))
        require(version in supportedVersions) { "Elige una versión compatible con el runtime de Android." }
        require(loader == "Vanilla" || loader.matches(Regex("Fabric(?: [0-9][A-Za-z0-9._+-]{0,49})?"))) {
            "PRISSMA admite Vanilla y Fabric para estas versiones."
        }
        require(loader == "Vanilla" || supportsFabric(version)) { "Esta versión está en pruebas con Vanilla." }
        // Key the cache by both launcher settings, even for older instances with a pinned loader.
        return File(shared, "prepared/$instanceId/$version-" + loader.replace(' ', '-') + ".json")
    }

    private suspend fun verifiedDownload(url: String, file: File, sha1: String, previous: File? = null) {
        require(sha1.matches(Regex("(?i)[a-f0-9]{40}"))) { "La fuente devolvió una suma inválida." }
        if (sameSha1(file, sha1)) return
        val parent = checkNotNull(file.parentFile).apply { mkdirs() }
        repeat(3) { attempt ->
            val partial = File.createTempFile("download-", ".part", parent)
            try {
                if (previous != null && sameSha1(previous, sha1)) previous.copyTo(partial, overwrite = true)
                else download(url, partial)
                if (!sameSha1(partial, sha1)) throw IOException("La descarga de " + file.name + " está dañada.")
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (failure: IOException) {
                if (attempt == 2) throw failure
                delay(400L * (attempt + 1))
            } finally {
                partial.delete()
            }
        }
    }

    private fun httpText(url: String): String = connect(url) { it.bufferedReader().readText() }

    private suspend fun download(url: String, file: File) {
        val current = currentCoroutineContext()
        connect(url) { input ->
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    current.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun <T> connect(url: String, read: (java.io.InputStream) -> T): T {
        require(url.startsWith("https://"))
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "PRISSMA-Launcher")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP " + connection.responseCode + " al descargar " + URL(url).host)
            }
            return connection.inputStream.use(read)
        } finally {
            connection.disconnect()
        }
    }
}

private data class Artifact(
    val url: String, val file: File, val sha1: String, val previous: File? = null, val coordinate: String = ""
)

internal fun mavenModule(coordinate: String) = coordinate.split(':', limit = 3).take(2).joinToString(":")

internal fun mavenPath(coordinate: String): String {
    val parts = coordinate.split(':')
    require(parts.size == 3 && parts.all { safeCoordinate.matches(it) && !it.startsWith('.') })
    val (group, artifact, version) = parts
    require(group.split('.').all { it.isNotEmpty() })
    return group.replace('.', '/') + "/$artifact/$version/$artifact-$version.jar"
}

private fun isLibraryAllowedOnLinux(library: JSONObject): Boolean {
    val rules = library.optJSONArray("rules") ?: return true
    return rules.objects().lastOrNull { rule ->
        val os = rule.optJSONObject("os")
        (os == null || os.optString("name") == "linux") && rule.optJSONObject("features") == null
    }?.getString("action") == "allow"
}

internal fun sameSha1(file: File, sha1: String): Boolean {
    if (!file.isFile) return false
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.equals(sha1, ignoreCase = true)
}

private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
