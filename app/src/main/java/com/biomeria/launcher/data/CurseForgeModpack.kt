package com.biomeria.launcher.data

import com.biomeria.launcher.BuildConfig
import com.biomeria.launcher.engine.java.supportsJavaLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val CURSEFORGE_API = "https://api.curseforge.com/v1"
private const val MAX_CURSEFORGE_FILE = 256L * 1024 * 1024
private val sha1Hash = Regex("[0-9a-f]{40}")
private val loaderId = Regex("(fabric|neoforge|forge|quilt)-([0-9][A-Za-z0-9._+-]{0,49})")

/** CurseForge class ids for content that lives in a folder other than mods/. */
private val curseForgeFolders = mapOf(12 to "resourcepacks", 6552 to "shaderpacks")

internal data class CurseForgeFileRef(val projectId: Int, val fileId: Int)
internal data class CurseForgeManifest(
    val name: String, val version: String, val loader: String, val overrides: String, val files: List<CurseForgeFileRef>
)

/** Reads the manifest.json of a CurseForge modpack .zip, without going online. */
internal fun decodeCurseForgeManifest(manifest: JSONObject): CurseForgeManifest {
    require(manifest.optString("manifestType") == "minecraftModpack" && manifest.optInt("manifestVersion") == 1) {
        "Formato de modpack de CurseForge incompatible."
    }
    val minecraft = manifest.getJSONObject("minecraft")
    val version = minecraft.getString("version")
    val loaders = minecraft.optJSONArray("modLoaders") ?: JSONArray()
    val primary = (0 until loaders.length()).map { loaders.getJSONObject(it) }
        .let { all -> all.firstOrNull { it.optBoolean("primary") } ?: all.firstOrNull() }
    val loader = if (primary == null) "Vanilla" else {
        val match = loaderId.matchEntire(primary.getString("id").lowercase(Locale.ROOT))
            ?: error("El modpack usa un cargador que Biomeria no reconoce.")
        when (match.groupValues[1]) {
            "fabric" -> "Fabric " + match.groupValues[2]
            "neoforge" -> "NeoForge " + match.groupValues[2]
            "quilt" -> "Quilt " + match.groupValues[2]
            else -> error("Este modpack usa Forge, que Biomeria aún no soporta. Busca una versión para Fabric, Quilt o NeoForge.")
        }
    }
    require(supportsJavaLoader(version, loader)) { "Minecraft $version con $loader aún no está disponible en Biomeria." }
    val overrides = manifest.optString("overrides", "overrides").ifBlank { "overrides" }
    safeModpackPath(overrides)
    require('/' !in overrides) { "El modpack indica una carpeta de configuración inválida." }
    val list = manifest.getJSONArray("files")
    require(list.length() <= 4096) { "Demasiados archivos en el modpack." }
    val files = (0 until list.length()).map { list.getJSONObject(it) }
        .filter { it.optBoolean("required", true) }
        .map { CurseForgeFileRef(it.getInt("projectID"), it.getInt("fileID")) }
    require(files.all { it.projectId > 0 && it.fileId > 0 } && files.distinctBy { it.fileId }.size == files.size) {
        "El modpack de CurseForge contiene archivos inválidos o repetidos."
    }
    val name = manifest.optString("name").trim().take(72).ifBlank { "Modpack de CurseForge" }
    return CurseForgeManifest(name, version, loader, overrides, files)
}

/**
 * Turns the API answers for the pack's files (POST /mods/files) and projects (POST /mods) into downloads.
 * Files whose authors turned off third-party downloads have no downloadUrl, so the public CDN address is built from the id.
 */
internal fun curseForgeDownloads(files: JSONArray, projects: JSONArray): List<ModpackFile> {
    val classes = (0 until projects.length()).associate {
        val project = projects.getJSONObject(it)
        project.getInt("id") to project.optInt("classId", 6)
    }
    return (0 until files.length()).map { index ->
        val file = files.getJSONObject(index)
        val id = file.getInt("id")
        val name = file.getString("fileName")
        val folder = curseForgeFolders[classes[file.getInt("modId")]] ?: "mods"
        val path = safeModpackPath("$folder/$name")
        require('/' !in name) { "CurseForge devolvió un nombre de archivo inválido." }
        val size = file.getLong("fileLength")
        val hashes = file.optJSONArray("hashes") ?: JSONArray()
        val sha1 = (0 until hashes.length()).map { hashes.getJSONObject(it) }
            .firstOrNull { it.optInt("algo") == 1 }?.optString("value")?.lowercase(Locale.ROOT).orEmpty()
        require(size in 0..MAX_CURSEFORGE_FILE && sha1Hash.matches(sha1)) {
            "CurseForge no entregó una firma válida para $name."
        }
        val url = file.optString("downloadUrl").takeIf { it.startsWith("https://") }
            ?: "https://edge.forgecdn.net/files/${id / 1000}/${id % 1000}/${java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"
        ModpackFile(path, size, sha1, listOf(modpackDownloadUri(url)), algorithm = "SHA-1")
    }
}

internal class CurseForgeApi(private val apiKey: String = BuildConfig.CURSEFORGE_API_KEY) {
    suspend fun resolve(refs: List<CurseForgeFileRef>): List<ModpackFile> = withContext(Dispatchers.IO) {
        if (refs.isEmpty()) return@withContext emptyList()
        check(apiKey.isNotBlank()) {
            "Esta versión de Biomeria aún no tiene acceso a CurseForge. Por ahora usa modpacks .mrpack de Modrinth."
        }
        val files = JSONArray()
        val projects = JSONArray()
        refs.chunked(200).forEach { chunk ->
            val found = post("/mods/files", JSONObject().put("fileIds", JSONArray(chunk.map { it.fileId })))
            for (i in 0 until found.length()) files.put(found.getJSONObject(i))
            val mods = post("/mods", JSONObject().put("modIds", JSONArray(chunk.map { it.projectId }.distinct())))
            for (i in 0 until mods.length()) projects.put(mods.getJSONObject(i))
        }
        val missing = refs.size - files.length()
        if (missing > 0) throw IOException("CurseForge no encontró $missing archivo(s) del modpack.")
        curseForgeDownloads(files, projects)
    }

    private fun post(path: String, body: JSONObject): JSONArray {
        val connection = URL(CURSEFORGE_API + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 12_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "BIOMERIA Launcher (github.com/nikalbazu24/Biomeria-Launcher)")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            when (connection.responseCode) {
                200 -> {}
                401, 403 -> throw IOException("CurseForge rechazó la llave de acceso de Biomeria.")
                else -> throw IOException("CurseForge respondió HTTP ${connection.responseCode}.")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text).getJSONArray("data")
        } finally {
            connection.disconnect()
        }
    }
}
