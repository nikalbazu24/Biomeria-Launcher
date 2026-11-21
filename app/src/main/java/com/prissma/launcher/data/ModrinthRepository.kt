package com.prissma.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class WorkshopCategory(
    val label: String,
    val modrinthType: String,
    val folderName: String
) {
    MODS("Mods", "mod", "mods"),
    SHADERS("Shaders", "shader", "shaderpacks"),
    RESOURCE_PACKS("Resource Packs", "resourcepack", "resourcepacks")
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
    val file: File
)

class ModrinthRepository(private val context: Context) {

    private val userAgent = "PRISSMA-Launcher/0.3 (github.com/nikalbazu24/Prissma-Launcher)"

    suspend fun searchProjects(
        query: String,
        category: WorkshopCategory
    ): Result<List<ModrinthItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val facetsJson = """[["project_type:${category.modrinthType}"]]"""
            val encodedFacets = URLEncoder.encode(facetsJson, "UTF-8")

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

    suspend fun downloadAndInstall(
        item: ModrinthItem,
        instanceId: String,
        preferredMcVersion: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val versionsUrl = "https://api.modrinth.com/v2/project/${item.projectId}/version"
            val versionsArray = JSONArray(httpGetText(versionsUrl))
            require(versionsArray.length() > 0) { "Este proyecto no tiene archivos disponibles." }

            // Busca una versión que coincida con la de la instancia, o usa la más reciente disponible
            var selectedVersion: JSONObject? = null
            for (i in 0 until versionsArray.length()) {
                val candidate = versionsArray.getJSONObject(i)
                val gameVersions = candidate.optJSONArray("game_versions")
                if (gameVersions != null) {
                    for (j in 0 until gameVersions.length()) {
                        if (gameVersions.getString(j) == preferredMcVersion) {
                            selectedVersion = candidate
                            break
                        }
                    }
                }
                if (selectedVersion != null) break
            }
            val finalVersion = selectedVersion ?: versionsArray.getJSONObject(0)

            val filesArray = finalVersion.getJSONArray("files")
            require(filesArray.length() > 0) { "No se encontró archivo descargable." }
            val primaryFile = filesArray.getJSONObject(0)

            val downloadUrl = primaryFile.getString("url")
            val fileName = primaryFile.getString("filename")

            val targetDir = File(
                context.filesDir,
                "instances/$instanceId/${item.category.folderName}"
            ).apply { mkdirs() }

            val destinationFile = File(targetDir, fileName)
            downloadBinary(downloadUrl, destinationFile)
            destinationFile
        }
    }

    fun getInstalledFiles(instanceId: String): List<InstalledFileItem> {
        val instanceRoot = File(context.filesDir, "instances/$instanceId")
        if (!instanceRoot.exists()) return emptyList()

        val list = mutableListOf<InstalledFileItem>()
        WorkshopCategory.entries.forEach { cat ->
            val folder = File(instanceRoot, cat.folderName)
            folder.listFiles()?.filter { it.isFile }?.forEach { file ->
                list.add(
                    InstalledFileItem(
                        fileName = file.name,
                        categoryLabel = cat.label,
                        sizeKb = (file.length() / 1024L).coerceAtLeast(1L),
                        file = file
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
                error("Error de red Modrinth (HTTP $code)")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadBinary(fileUrl: String, destination: File) {
        val conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            if (conn.responseCode !in 200..299) {
                error("Fallo al descargar archivo (HTTP ${conn.responseCode})")
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
