package com.biomeria.launcher.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/** Mods that every pack needs and that other mods never list as a reason to keep them. */
private val loaderIds = setOf("minecraft", "java", "fabricloader", "fabric-loader", "neoforge", "forge")
private const val DISABLED = ".disabled"
private const val MAX_IMPORT = 512L * 1024 * 1024
private const val KEPT_LOGS = 10
internal const val LOG_FOLDER = "biomeria-logs"

/** One jar in the world's mods folder, on or off. */
internal data class ManagedMod(
    val file: File,
    val id: String,
    val name: String,
    val version: String?,
    val description: String?,
    val enabled: Boolean,
    val depends: Set<String>,
    val iconBytes: ByteArray?
) {
    override fun equals(other: Any?) = other is ManagedMod && other.file == file && other.enabled == enabled
    override fun hashCode() = file.hashCode()
}

internal data class ModFacts(val id: String, val name: String, val version: String?, val description: String?,
                             val depends: Set<String>, val iconPath: String?)

/** Reads fabric.mod.json. Version placeholders such as ${version} count as unknown. */
internal fun fabricModFacts(json: JSONObject): ModFacts {
    val icon = when (val value = json.opt("icon")) {
        is String -> value
        is JSONObject -> value.keys().asSequence().maxByOrNull { it.toIntOrNull() ?: 0 }?.let { value.optString(it) }
        else -> null
    }
    val depends = json.optJSONObject("depends")?.keys()?.asSequence()?.map { it.lowercase(Locale.ROOT) }
        ?.filter { it !in loaderIds }?.toSet().orEmpty()
    val id = json.optString("id").lowercase(Locale.ROOT)
    return ModFacts(id, json.optString("name").ifBlank { id },
        json.optString("version").takeIf { it.isNotBlank() && !it.startsWith("$") },
        json.optString("description").takeIf { it.isNotBlank() }, depends, icon)
}

/** Reads the first mod of a NeoForge mods.toml, without a full TOML parser. */
internal fun neoForgeModFacts(toml: String): ModFacts? {
    val body = toml.substringAfter("[[mods]]", "").ifBlank { return null }.substringBefore("[[")
    fun field(name: String) = Regex("""(?m)^\s*$name\s*=\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
    val id = field("modId")?.lowercase(Locale.ROOT) ?: return null
    val depends = Regex("""\[\[dependencies\.$id]][^\[]*?modId\s*=\s*"([^"]+)"[^\[]*?(?:type\s*=\s*"required"|mandatory\s*=\s*true)""")
        .findAll(toml).map { it.groupValues[1].lowercase(Locale.ROOT) }.filter { it !in loaderIds }.toSet()
    return ModFacts(id, field("displayName") ?: id, field("version")?.takeIf { !it.startsWith("$") },
        field("description")?.trim(), depends, field("logoFile"))
}

/** Names of the enabled mods that need [mod]; while there are any, it can't be turned off or removed. */
internal fun requiredBy(mod: ManagedMod, mods: List<ManagedMod>): List<String> =
    mods.filter { it.enabled && it.file != mod.file && mod.id in it.depends }.map { it.name }

/** Where an imported file goes, decided from its name and the names inside it. */
internal enum class ImportKind(val label: String, val folder: String) {
    MOD("Mod", "mods"), SHADER("Shader", "shaderpacks"), RESOURCE_PACK("Paquete de texturas", "resourcepacks"),
    WORLD("Mundo", "saves")
}

/** The folder inside the zip that holds the world (empty for the zip's root), or null when it has none. */
internal fun worldRoot(entries: List<String>): String? = entries
    .filter { it.substringAfterLast('/') == "level.dat" && it.count { c -> c == '/' } <= 1 }
    .minByOrNull { it.length }?.substringBeforeLast("level.dat")

internal fun classifyImport(fileName: String, entries: List<String>): ImportKind? {
    val tops = entries.map { it.trimStart('/') }
    return when {
        tops.any { it == "fabric.mod.json" || it == "quilt.mod.json" || it == "META-INF/neoforge.mods.toml" || it == "META-INF/mods.toml" } -> ImportKind.MOD
        fileName.lowercase(Locale.ROOT).endsWith(".jar") -> ImportKind.MOD
        worldRoot(tops) != null -> ImportKind.WORLD
        tops.any { it.startsWith("shaders/") || Regex("^[^/]+/shaders/").containsMatchIn(it) } -> ImportKind.SHADER
        tops.any { it == "pack.mcmeta" } -> ImportKind.RESOURCE_PACK
        else -> null
    }
}

/** A finished game's log. [current] is the newest one, still named biomeria_last.log. */
internal data class GameLog(val file: File, val playedAt: Long, val crashed: Boolean, val cause: String?, val current: Boolean)

/** Moves the previous game's log into the history before a new game overwrites it, keeping the newest ten. */
internal fun archiveLastLog(gameDirectory: File) {
    val last = File(gameDirectory, "biomeria_last.log")
    if (!last.isFile || last.length() == 0L) return
    val folder = File(gameDirectory, LOG_FOLDER).apply { mkdirs() }
    var target = File(folder, "${last.lastModified()}.log")
    if (target.exists()) target = File(folder, "${last.lastModified()}-${System.nanoTime()}.log")
    runCatching { last.copyTo(target) }.onSuccess { target.setLastModified(last.lastModified()) }
    folder.listFiles { f -> f.extension == "log" }.orEmpty().sortedByDescending { it.lastModified() }
        .drop(KEPT_LOGS).forEach { it.delete() }
}

internal class InstanceContent(private val context: Context, instanceId: String) {
    val gameDirectory = File(context.filesDir, "instances/$instanceId")
    private val mods get() = File(gameDirectory, "mods")

    suspend fun mods(): List<ManagedMod> = withContext(Dispatchers.IO) {
        mods.listFiles().orEmpty().filter {
            it.isFile && (it.name.endsWith(".jar", true) || it.name.endsWith(".jar$DISABLED", true))
        }.mapNotNull(::readMod).filter { !it.id.startsWith("biomeria") }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun readMod(jar: File): ManagedMod? = runCatching {
        ZipFile(jar).use { zip ->
            val facts = zip.getEntry("fabric.mod.json")?.let { fabricModFacts(JSONObject(zip.read(it))) }
                ?: zip.getEntry("META-INF/neoforge.mods.toml")?.let { neoForgeModFacts(zip.read(it)) }
                ?: zip.getEntry("META-INF/mods.toml")?.let { neoForgeModFacts(zip.read(it)) }
                ?: ModFacts(jar.name.removeSuffix(DISABLED).removeSuffix(".jar").lowercase(Locale.ROOT),
                    jar.name.removeSuffix(DISABLED).removeSuffix(".jar"), null, null, emptySet(), null)
            val icon = facts.iconPath?.let { zip.getEntry(it.removePrefix("/")) }?.takeIf { it.size in 1..1_000_000 }
                ?.let { entry -> zip.getInputStream(entry).use(InputStream::readBytes) }
            ManagedMod(jar, facts.id, facts.name, facts.version, facts.description, !jar.name.endsWith(DISABLED), facts.depends, icon)
        }
    }.getOrNull()

    /** Turns a mod on or off by renaming it, so Minecraft skips it without losing the file. */
    suspend fun setEnabled(mod: ManagedMod, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        if (mod.enabled == enabled) return@withContext
        val name = if (enabled) mod.file.name.removeSuffix(DISABLED) else mod.file.name + DISABLED
        val target = File(mod.file.parentFile, name)
        check(!target.exists()) { "Ya hay otro archivo llamado $name." }
        Files.move(mod.file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    suspend fun remove(file: File): Boolean = withContext(Dispatchers.IO) {
        require(file.canonicalPath.startsWith(gameDirectory.canonicalPath + File.separator))
        file.delete()
    }

    /** Asks Modrinth for the newest compatible version of each mod. Mods that Modrinth doesn't know are skipped. */
    suspend fun findUpdates(list: List<ManagedMod>, gameVersion: String, loader: String): Map<File, ModUpdate> =
        withContext(Dispatchers.IO) {
            if (list.isEmpty()) return@withContext emptyMap()
            val hashes = list.associateBy { sha512(it.file) }
            val body = JSONObject().put("hashes", JSONArray(hashes.keys.toList())).put("algorithm", "sha512")
                .put("loaders", JSONArray().put(loader)).put("game_versions", JSONArray().put(gameVersion))
            val reply = JSONObject(post("https://api.modrinth.com/v2/version_files/update", body))
            buildMap {
                for ((hash, mod) in hashes) {
                    val version = reply.optJSONObject(hash) ?: continue
                    parseModUpdate(version, hash)?.let { put(mod.file, it) }
                }
            }
        }

    /** Replaces the mod with its update, keeping it on or off as it was. */
    suspend fun applyUpdate(mod: ManagedMod, update: ModUpdate): Unit = withContext(Dispatchers.IO) {
        val name = update.fileName + if (mod.enabled) "" else DISABLED
        val target = File(mods, name)
        val partial = File(mods, ".$name.part")
        try {
            download(update.url, partial, update.sha512, update.size)
            if (target != mod.file) mod.file.delete()
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
    }

    suspend fun logs(): List<GameLog> = withContext(Dispatchers.IO) {
        val current = File(gameDirectory, "biomeria_last.log").takeIf { it.isFile && it.length() > 0 }
        val archived = File(gameDirectory, LOG_FOLDER).listFiles { f -> f.extension == "log" }.orEmpty()
            .filter { current == null || it.lastModified() != current.lastModified() }
        (listOfNotNull(current) + archived).sortedByDescending { it.lastModified() }.take(KEPT_LOGS).map { file ->
            val text = readTail(file)
            val crash = readCrash(text)
            GameLog(file, file.lastModified(), crash.crashed, crash.cause, file == current)
        }
    }

    /** Copies a file the player picked into the right folder; worlds are unpacked into saves/. */
    suspend fun import(uri: Uri): Pair<ImportKind, String> = withContext(Dispatchers.IO) {
        val name = displayName(uri)?.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")?.trim()?.take(120)
            ?.takeIf { it.isNotBlank() && !it.startsWith(".") } ?: "archivo.zip"
        val staged = File(context.cacheDir, "imports/${System.nanoTime()}.part").apply { parentFile?.mkdirs() }
        try {
            (context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el archivo.")).use { input ->
                staged.outputStream().use { output -> copyLimited(input, output, MAX_IMPORT) }
            }
            val entries = runCatching { ZipFile(staged).use { zip -> zip.entries().asSequence().map { it.name }.toList() } }
                .getOrElse { error("Ese archivo no es un mod, shader, paquete de texturas ni mundo de Minecraft.") }
            val kind = classifyImport(name, entries)
                ?: error("No reconozco qué es $name. Debe ser un mod (.jar), un shader, un paquete de texturas o un mundo (.zip).")
            val folder = File(gameDirectory, kind.folder).apply { mkdirs() }
            if (kind == ImportKind.WORLD) {
                val worldName = name.substringBeforeLast('.').ifBlank { "Mundo importado" }
                val target = File(folder, worldName)
                check(!target.exists()) { "Ya tienes un mundo llamado $worldName en esta instancia." }
                unpackWorld(staged, checkNotNull(worldRoot(entries)), target)
                kind to worldName
            } else {
                val fileName = if (kind == ImportKind.MOD && !name.endsWith(".jar", true)) "$name.jar" else name
                val target = File(folder, fileName)
                check(!target.exists() && !File(folder, fileName + DISABLED).exists()) { "Ya tienes $fileName en esta instancia." }
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                kind to fileName
            }
        } finally {
            staged.delete()
        }
    }

    private fun unpackWorld(zipFile: File, root: String, target: File) {
        val staging = File(target.parentFile, ".${target.name}.part").apply { deleteRecursively(); mkdirs() }
        try {
            var total = 0L
            ZipFile(zipFile).use { zip ->
                for (entry in zip.entries().asSequence()) {
                    if (entry.isDirectory || !entry.name.startsWith(root)) continue
                    val path = entry.name.removePrefix(root)
                    require(path.split('/').none { it.isEmpty() || it == "." || it == ".." } && '\\' !in path) {
                        "El mundo contiene una ruta de archivo inválida."
                    }
                    val out = File(staging, path)
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { total += copyLimited(input, it, MAX_IMPORT * 2 - total) }
                    }
                }
            }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}

internal data class ModUpdate(val versionNumber: String, val fileName: String, val url: String, val sha512: String, val size: Long)

/** The update in a Modrinth version, or null when the player already has that exact file. */
internal fun parseModUpdate(version: JSONObject, currentHash: String): ModUpdate? {
    val files = version.optJSONArray("files") ?: return null
    val all = (0 until files.length()).map { files.getJSONObject(it) }
    val file = all.firstOrNull { it.optBoolean("primary") } ?: all.firstOrNull() ?: return null
    val hash = file.optJSONObject("hashes")?.optString("sha512")?.lowercase(Locale.ROOT) ?: return null
    if (hash == currentHash) return null
    val name = file.getString("filename")
    val url = file.getString("url")
    require(url.startsWith("https://cdn.modrinth.com/") && '/' !in name && !name.startsWith(".") && name.endsWith(".jar", true)) {
        "Modrinth devolvió una actualización inválida."
    }
    return ModUpdate(version.optString("version_number").ifBlank { name }, name, url, hash, file.optLong("size", -1))
}

private fun ZipFile.read(entry: java.util.zip.ZipEntry): String {
    require(entry.size <= 1_000_000)
    return getInputStream(entry).bufferedReader().use { it.readText() }
}

private fun readTail(file: File, limit: Int = 256 * 1024): String = java.io.RandomAccessFile(file, "r").use {
    val start = (it.length() - limit).coerceAtLeast(0)
    it.seek(start)
    ByteArray((it.length() - start).toInt()).also(it::readFully).toString(Charsets.UTF_8)
}

private fun copyLimited(input: InputStream, output: java.io.OutputStream, limit: Long): Long {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return total
        total += count
        if (total > limit) throw IOException("El archivo es demasiado grande.")
        output.write(buffer, 0, count)
    }
}

private fun sha512(file: File): String {
    val digest = MessageDigest.getInstance("SHA-512")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val USER_AGENT = "BIOMERIA Launcher (github.com/nikalbazu24/Biomeria-Launcher)"

private fun post(url: String, body: JSONObject): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        if (connection.responseCode !in 200..299) throw IOException("Modrinth respondió HTTP ${connection.responseCode}.")
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun download(url: String, destination: File, sha512: String, size: Long) {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (connection.responseCode !in 200..299) throw IOException("No se pudo descargar la actualización (HTTP ${connection.responseCode}).")
        val digest = MessageDigest.getInstance("SHA-512")
        val written = java.security.DigestInputStream(connection.inputStream, digest).use { input ->
            destination.outputStream().use { copyLimited(input, it, 256L * 1024 * 1024) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha512 || (size >= 0 && written != size)) throw IOException("La actualización llegó dañada. Intenta otra vez.")
    } finally {
        connection.disconnect()
    }
}
