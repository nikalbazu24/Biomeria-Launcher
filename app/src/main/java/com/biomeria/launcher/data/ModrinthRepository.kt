package com.biomeria.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class WorkshopCategory(
    val label: String,
    val modrinthType: String,
    val folderName: String
) {
    MODS("Mods", "mod", "mods"),
    SHADERS("Shaders", "shader", "shaderpacks"),
    RESOURCE_PACKS("Paquetes de recursos", "resourcepack", "resourcepacks")
}

data class ModrinthItem(
    val projectId: String,
    val title: String,
    val author: String,
    val description: String,
    val iconUrl: String?,
    val downloads: Int,
    val category: WorkshopCategory,
    val gallery: List<String> = emptyList()
)

data class InstalledFileItem(
    val fileName: String,
    val categoryLabel: String,
    val sizeKb: Long,
    val file: File,
    val compatibility: String? = null
)

class ModrinthRepository internal constructor(
    private val filesDir: File,
    private val openConnection: (String) -> HttpURLConnection
) {
    constructor(context: Context) : this(context.filesDir, { URL(it).openConnection() as HttpURLConnection })

    companion object {
        private val installationLock = Mutex()
    }

    private val userAgent = "BIOMERIA-Launcher/0.3 (github.com/nikalbazu24/Biomeria-Launcher)"

    suspend fun searchProjects(
        query: String,
        category: WorkshopCategory,
        gameVersion: String,
        loader: String?,
        onlyCompatible: Boolean = true
    ): Result<List<ModrinthItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val facets = JSONArray().put(JSONArray().put("project_type:${category.modrinthType}"))
            if (onlyCompatible || category != WorkshopCategory.MODS) facets.put(JSONArray().put("versions:$gameVersion"))
            if (category == WorkshopCategory.MODS) {
                if (onlyCompatible && loader != null) facets.put(JSONArray().put("categories:$loader"))
                facets.put(JSONArray().put("client_side:required").put("client_side:optional"))
            }
            val encodedFacets = URLEncoder.encode(facets.toString(), "UTF-8")

            val urlStr = "https://api.modrinth.com/v2/search" +
                "?query=$encodedQuery&facets=$encodedFacets&limit=20&index=downloads"

            val responseText = httpGetText(urlStr)
            val root = JSONObject(responseText)
            val hits = root.getJSONArray("hits")

            val results = mutableListOf<ModrinthItem>()
            for (i in 0 until hits.length()) {
                val obj = hits.getJSONObject(i)
                results.add(
                    ModrinthItem(
                        projectId = obj.getString("project_id"),
                        title = obj.getString("title"),
                        author = obj.optString("author", "Comunidad"),
                        description = obj.optString("description", ""),
                        iconUrl = obj.optString("icon_url").takeIf { it.isNotBlank() && it != "null" },
                        downloads = obj.optInt("downloads", 0),
                        category = category,
                        gallery = obj.optJSONArray("gallery")?.let { array ->
                            (0 until array.length()).mapNotNull { array.optString(it).takeIf { url -> url.startsWith("https://") } }
                        }.orEmpty()
                    )
                )
            }
            results
        }
    }

    internal suspend fun checkCompatibility(
        projectId: String,
        category: WorkshopCategory,
        gameVersion: String,
        loader: String?
    ): WorkshopInstallPlan = withContext(Dispatchers.IO) {
        plan(projectId, "", category, gameVersion, loader)
    }

    private fun plan(projectId: String, pins: String, category: WorkshopCategory, gameVersion: String, loader: String?) =
        ModrinthInstallPlanner(category, gameVersion, loader, pins) { path ->
            try {
                httpGetText("https://api.modrinth.com/v2$path")
            } catch (failure: ModrinthHttpFailure) {
                if (failure.statusCode != 404 && failure.statusCode != 410) throw failure
                throw WorkshopIncompatibility("Un proyecto o versión requeridos ya no están disponibles en Modrinth.")
            }
        }.plan(projectId)

    internal suspend fun install(
        projectId: String,
        instanceId: String,
        category: WorkshopCategory,
        gameVersion: String,
        loader: String?,
        pins: String = "",
        validateInstance: () -> Unit = {},
        onProgress: (WorkshopInstallProgress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        installationLock.withLock {
            require(instanceId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            validateInstance()
            onProgress(WorkshopInstallProgress(WorkshopInstallStage.CHECKING))
            val artifacts = plan(projectId, pins, category, gameVersion, loader).artifacts.asReversed()

            val instanceRoot = File(filesDir, "instances/$instanceId")
            val targetDir = File(instanceRoot, category.folderName).apply { mkdirs() }
            val manifestDir = File(instanceRoot, "workshop/" + category.name).apply { mkdirs() }
            val staging = File(instanceRoot, "workshop/.staging")
            check(!staging.exists() || staging.deleteRecursively()) { "No se pudo limpiar la descarga anterior." }
            check(staging.mkdirs()) { "No se pudo preparar la carpeta de instalación." }
            val cacheRoot = File(instanceRoot, "workshop/.downloads")
            val cache = File(cacheRoot, "${category.name}-$projectId")
            try {
                // Only complete, verified archives survive a failed attempt.
                val usedHashes = artifacts.mapTo(mutableSetOf()) { it.sha512 }
                cacheRoot.listFiles()?.filter {
                    it != cache && it.lastModified() < System.currentTimeMillis() - VERIFIED_CACHE_AGE_MS
                }?.forEach { it.deleteRecursively() }
                check(cache.isDirectory || cache.mkdirs()) { "No se pudo preparar la carpeta de descarga." }
                cache.listFiles()?.filter { it.name !in usedHashes }?.forEach { it.delete() }
                cache.setLastModified(System.currentTimeMillis())

                val changes = linkedMapOf<File, File>()
                val installing = mutableMapOf<File, WorkshopInstallProgress>()
                val obsolete = mutableSetOf<File>()
                val destinations = mutableSetOf<File>()
                for ((index, artifact) in artifacts.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val name = artifact.filename
                    val destination = File(targetDir, name)
                    destinations.add(destination)
                    val report = WorkshopInstallProgress(WorkshopInstallStage.VERIFYING, name, index + 1, artifacts.size)
                    if (destination.isFile) onProgress(report)
                    if (!destination.isFile || sha512(destination) != artifact.sha512) {
                        val verified = File(cache, artifact.sha512)
                        if (verified.isFile) onProgress(report)
                        if (!verified.isFile || sha512(verified) != artifact.sha512) {
                            val partial = File(staging, "download.part")
                            downloadBinary(artifact.url, partial, artifact.sha512, artifact.size, report, onProgress)
                            Files.move(partial.toPath(), verified.toPath(), StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING)
                        }
                        val archive = File(staging, "archive-$index")
                        verified.copyTo(archive)
                        changes[destination] = archive
                        installing[destination] = report.copy(stage = WorkshopInstallStage.INSTALLING)
                    }

                    val manifest = File(manifestDir, "${artifact.projectId}.json")
                    val previous = runCatching { JSONObject(manifest.readText()).getString("file") }.getOrNull()
                    val stagedManifest = File(staging, "manifest-$index")
                    stagedManifest.writeText(artifact.metadata.toString())
                    changes[manifest] = stagedManifest
                    if (previous != null && previous != name && safeWorkshopFilename(previous)) {
                        obsolete.add(File(targetDir, previous))
                    }
                }
                currentCoroutineContext().ensureActive()
                validateInstance()
                onProgress(WorkshopInstallProgress(WorkshopInstallStage.INSTALLING, fileCount = artifacts.size))
                publishWorkshopFiles(changes, staging) { destination -> installing[destination]?.let(onProgress) }
                (obsolete - destinations).forEach { it.delete() }
                cache.deleteRecursively()
                artifacts.size
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    fun getInstalledFiles(instanceId: String, gameVersion: String, loader: String?): List<InstalledFileItem> {
        val instanceRoot = File(filesDir, "instances/$instanceId")
        val loaderLabel = workshopModLoaderName(loader)
        if (!instanceRoot.exists()) return emptyList()

        val list = mutableListOf<InstalledFileItem>()
        WorkshopCategory.entries.forEach { cat ->
            val folder = File(instanceRoot, cat.folderName)
            val metadata = File(instanceRoot, "workshop/" + cat.name).listFiles().orEmpty().mapNotNull { file ->
                if (file.extension != "json") null else runCatching { JSONObject(file.readText()) }.getOrNull()
            }.associateBy { it.optString("file") }
            folder.listFiles()?.filter { it.isFile && !it.name.endsWith(".new") && !it.name.endsWith(".bak") }?.forEach { file ->
                list.add(
                    InstalledFileItem(
                        fileName = file.name,
                        categoryLabel = cat.label,
                        sizeKb = (file.length() / 1024L).coerceAtLeast(1L),
                        file = file,
                        compatibility = when {
                            cat == WorkshopCategory.MODS && loaderLabel == null -> "Requiere Fabric o NeoForge"
                            cat != WorkshopCategory.MODS -> null
                            metadata[file.name] == null -> "Compatibilidad sin verificar"
                            metadata.getValue(file.name).matchesGame(gameVersion, cat, loader) -> "Compatible con $gameVersion · $loaderLabel"
                            else -> "Incompatible con la versión o el cargador de la instancia"
                        }
                    )
                )
            }
        }
        return list.sortedBy { it.fileName.lowercase() }
    }

    fun deleteInstalledFile(item: InstalledFileItem): Boolean {
        return item.file.delete()
    }

    private fun httpGetText(urlString: String): String {
        val conn = openConnection(urlString).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ModrinthHttpFailure(code, "No se pudo consultar Modrinth (HTTP $code).")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadBinary(
        fileUrl: String,
        partial: File,
        expectedSha512: String,
        declaredSize: Long,
        file: WorkshopInstallProgress,
        onProgress: (WorkshopInstallProgress) -> Unit
    ) {
        val conn = openConnection(fileUrl).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val download = file.copy(stage = WorkshopInstallStage.DOWNLOADING)
            onProgress(download.copy(totalBytes = declaredSize))
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ModrinthHttpFailure(code, "No se pudo descargar ${file.fileName} (HTTP $code).")
            }
            val total = declaredSize.takeIf { it > 0 } ?: conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-512")
            val job = currentCoroutineContext()
            var written = 0L
            var lastProgress = -1L
            onProgress(download.copy(totalBytes = total))
            java.security.DigestInputStream(conn.inputStream, digest).use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        job.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        val update = download.copy(downloadedBytes = written, totalBytes = total)
                        val milestone = update.percent?.toLong() ?: written / (256 * 1024)
                        if (milestone != lastProgress) {
                            lastProgress = milestone
                            onProgress(update)
                        }
                    }
                }
            }
            onProgress(file)
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expectedSha512) throw IOException("La descarga de ${file.fileName} está incompleta o dañada.")
        } finally {
            conn.disconnect()
        }
    }
}

internal fun publishWorkshopFiles(changes: Map<File, File>, staging: File, onFile: (File) -> Unit = {}) {
    val backups = linkedMapOf<File, File?>()
    try {
        for ((destination, staged) in changes) {
            onFile(destination)
            val backup = if (destination.isFile) {
                File(staging, "backup-" + backups.size).also { destination.copyTo(it) }
            } else null
            Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            backups[destination] = backup
        }
    } catch (failure: Exception) {
        for ((destination, backup) in backups.entries.reversed()) {
            try {
                if (backup == null) Files.deleteIfExists(destination.toPath())
                else Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
        }
        throw failure
    }
}

private const val VERIFIED_CACHE_AGE_MS = 3 * 24 * 60 * 60 * 1000L

private class ModrinthHttpFailure(val statusCode: Int, message: String) : IOException(message)

internal fun shouldRetryWorkshopDownload(failure: Exception, attemptCount: Int): Boolean =
    attemptCount < 2 && failure is IOException &&
        (failure !is ModrinthHttpFailure || failure.statusCode in 500..599 || failure.statusCode in listOf(408, 429))

private fun sha512(file: File): String {
    val digest = MessageDigest.getInstance("SHA-512")
    java.security.DigestInputStream(file.inputStream(), digest).use { input ->
        val buffer = ByteArray(32 * 1024)
        while (input.read(buffer) >= 0) Unit
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
