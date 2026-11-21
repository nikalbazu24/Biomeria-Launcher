package com.prissma.launcher.data

import android.content.Context
import android.util.AtomicFile
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
    val category: WorkshopCategory
)

data class InstalledFileItem(
    val fileName: String,
    val categoryLabel: String,
    val sizeKb: Long,
    val file: File,
    val compatibility: String? = null
)

class ModrinthRepository(private val context: Context) {

    companion object {
        private val installationLock = Mutex()
        private val idPattern = Regex("[A-Za-z0-9]{1,64}")
    }

    private val userAgent = "PRISSMA-Launcher/0.3 (github.com/nikalbazu24/Prissma-Launcher)"

    suspend fun searchProjects(
        query: String,
        category: WorkshopCategory,
        gameVersion: String,
        loader: String?
    ): Result<List<ModrinthItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val facets = JSONArray().put(JSONArray().put("project_type:${category.modrinthType}"))
                .put(JSONArray().put("versions:$gameVersion"))
            if (category == WorkshopCategory.MODS && loader != null) {
                facets.put(JSONArray().put("categories:$loader"))
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
                        category = category
                    )
                )
            }
            results
        }
    }

    internal suspend fun install(
        projectId: String,
        instanceId: String,
        category: WorkshopCategory,
        gameVersion: String,
        loader: String?
    ): Int = withContext(Dispatchers.IO) {
        installationLock.withLock {
            require(instanceId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            require(category != WorkshopCategory.MODS || loader == "fabric") {
                "Elige Fabric como cargador para instalar mods."
            }
            val resolved = linkedMapOf<String, JSONObject>()

            fun resolve(id: String?, versionId: String?) {
                require(id != null || versionId != null)
                require(listOfNotNull(id, versionId).all(idPattern::matches))
                if (id in resolved && versionId == null) return
                val version = if (versionId != null) {
                    JSONObject(httpGetText("https://api.modrinth.com/v2/version/$versionId"))
                } else {
                    val versions = JSONArray(httpGetText("https://api.modrinth.com/v2/project/$id/version"))
                    (0 until versions.length()).asSequence().map(versions::getJSONObject)
                        .firstOrNull { it.matchesGame(gameVersion, category, loader) }
                        ?: error("No hay una versión compatible de $id para Minecraft $gameVersion.")
                }
                val project = version.getString("project_id")
                val resolvedId = version.getString("id")
                require(idPattern.matches(project) && idPattern.matches(resolvedId))
                require(id == null || id == project)
                check(version.matchesGame(gameVersion, category, loader)) {
                    "Una dependencia exige una versión incompatible con Minecraft $gameVersion y $loader."
                }
                val previous = resolved[project]
                check(previous == null || previous.getString("id") == resolvedId) {
                    "Los mods requieren versiones distintas de la dependencia $project."
                }
                if (previous != null) return
                check(resolved.size < 60) { "El proyecto tiene demasiadas dependencias." }
                resolved[project] = version
                if (category != WorkshopCategory.MODS) return
                val dependencies = version.optJSONArray("dependencies") ?: return
                repeat(dependencies.length()) { index ->
                    val dependency = dependencies.getJSONObject(index)
                    if (dependency.optString("dependency_type") == "required") {
                        resolve(dependency.optionalId("project_id"), dependency.optionalId("version_id"))
                    }
                }
            }
            resolve(projectId, null)

            val instanceRoot = File(context.filesDir, "instances/$instanceId")
            val targetDir = File(instanceRoot, category.folderName).apply { mkdirs() }
            val manifestDir = File(instanceRoot, "workshop/" + category.name).apply { mkdirs() }
            val installed = resolved.entries.reversed().map { (project, version) ->
                val files = version.getJSONArray("files")
                val primary = (0 until files.length()).asSequence().map(files::getJSONObject)
                    .firstOrNull { it.optBoolean("primary") } ?: files.getJSONObject(0)
                val name = primary.getString("filename")
                require(safeFilename(name)) { "Modrinth devolvió un nombre de archivo inválido." }
                require(category != WorkshopCategory.MODS || name.endsWith(".jar", ignoreCase = true))
                val url = primary.getString("url")
                require(url.startsWith("https://cdn.modrinth.com/")) { "Origen del archivo inválido." }
                val sha512 = primary.getJSONObject("hashes").getString("sha512")
                require(sha512.matches(Regex("(?i)[a-f0-9]{128}")))
                val destination = File(targetDir, name)
                if (!destination.isFile || sha512(destination) != sha512.lowercase()) {
                    downloadBinary(url, destination, sha512)
                }
                Triple(project, name, version)
            }
            for ((project, name, version) in installed) {
                val metadata = AtomicFile(File(manifestDir, "$project.json"))
                val previous = runCatching { JSONObject(metadata.readFully().toString(Charsets.UTF_8)).getString("file") }
                    .getOrNull()
                val info = JSONObject().put("file", name).put("game_versions", version.getJSONArray("game_versions"))
                    .put("loaders", version.getJSONArray("loaders"))
                val output = metadata.startWrite()
                try {
                    output.write(info.toString().toByteArray(Charsets.UTF_8))
                    metadata.finishWrite(output)
                } catch (error: Exception) {
                    metadata.failWrite(output)
                    throw error
                }
                if (previous != null && previous != name && safeFilename(previous)) File(targetDir, previous).delete()
            }
            installed.size
        }
    }

    fun getInstalledFiles(instanceId: String, gameVersion: String, loader: String?): List<InstalledFileItem> {
        val instanceRoot = File(context.filesDir, "instances/$instanceId")
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
                            cat == WorkshopCategory.MODS && loader != "fabric" -> "Requiere Fabric"
                            cat != WorkshopCategory.MODS -> null
                            metadata[file.name] == null -> "Compatibilidad sin verificar"
                            metadata.getValue(file.name).matchesGame(gameVersion, cat, loader) -> "Compatible con $gameVersion · Fabric"
                            else -> "Incompatible con esta versión de Minecraft"
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
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("Error de red Modrinth (HTTP $code)")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadBinary(fileUrl: String, destination: File, expectedSha512: String) {
        val conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("Fallo al descargar archivo (HTTP " + conn.responseCode + ")")
            }
            val atomic = AtomicFile(destination)
            val output = atomic.startWrite()
            try {
                val digest = MessageDigest.getInstance("SHA-512")
                java.security.DigestInputStream(conn.inputStream, digest).use { input ->
                    val job = currentCoroutineContext()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        job.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                val actualSha512 = digest.digest().joinToString("") { "%02x".format(it) }
                check(actualSha512.equals(expectedSha512, ignoreCase = true)) { "La descarga está incompleta o dañada." }
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        } finally {
            conn.disconnect()
        }
    }
}

private fun JSONObject.optionalId(name: String) = optString(name).takeUnless { it.isBlank() || it == "null" }

internal fun JSONObject.matchesGame(version: String, category: WorkshopCategory, loader: String?): Boolean =
    optJSONArray("game_versions").containsValue(version) &&
        (category != WorkshopCategory.MODS || optJSONArray("loaders").containsValue(loader))

private fun JSONArray?.containsValue(value: String?): Boolean =
    this != null && value != null && (0 until length()).any { optString(it) == value }

private fun safeFilename(name: String) =
    name.isNotBlank() && File(name).name == name && name !in listOf(".", "..")

private fun sha512(file: File): String {
    val digest = MessageDigest.getInstance("SHA-512")
    java.security.DigestInputStream(file.inputStream(), digest).use { input ->
        val buffer = ByteArray(32 * 1024)
        while (input.read(buffer) >= 0) Unit
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
