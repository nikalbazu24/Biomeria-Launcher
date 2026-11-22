package com.prissma.launcher.data

import android.content.Context
import android.net.Uri
import com.prissma.launcher.engine.java.supportsJavaLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

private const val MAX_ARCHIVE = 256L * 1024 * 1024
private const val MAX_CONTENT = 2L * 1024 * 1024 * 1024
private const val MAX_INDEX = 4L * 1024 * 1024
private val modpackId = Regex("[a-f0-9-]{36}")
private val packHash = Regex("[0-9a-f]{128}")
private val downloadHosts = setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "release-assets.githubusercontent.com", "gitlab.com")

internal data class ModpackFile(val path: String, val size: Long, val sha512: String, val urls: List<URI>)
internal data class ModpackMetadata(val name: String, val version: String, val loader: String, val files: List<ModpackFile>)

internal fun safeModpackPath(value: String): String {
    val parts = value.split('/')
    require(value.length in 1..512 && parts.size <= 36 && '\\' !in value &&
        parts.none { it.isEmpty() || it == "." || it == ".." || it.any { c -> c < ' ' || c in ":*?\"<>|" } } &&
        !parts.first().startsWith(".") && parts.first() !in setOf("prissma-map", "workshop", "prissma_last.log")
    ) { "El modpack contiene una ruta de archivo inválida." }
    return value
}

private fun modpackDownloadUri(raw: String): URI {
    val uri = URI(raw).normalize()
    require(uri.scheme == "https" && uri.host?.lowercase(Locale.ROOT) in downloadHosts && uri.userInfo == null &&
        uri.port in setOf(-1, 443) && uri.fragment == null && uri.path.isNotBlank()
    ) { "El modpack incluye una descarga de un origen no admitido." }
    return uri
}

internal fun decodeModpack(index: JSONObject): ModpackMetadata {
    require(index.getInt("formatVersion") == 1 && index.getString("game") == "minecraft") {
        "Formato de modpack incompatible."
    }
    val dependencies = index.getJSONObject("dependencies")
    val version = dependencies.getString("minecraft")
    val loader = when {
        dependencies.has("fabric-loader") && dependencies.length() == 2 -> "Fabric " + dependencies.getString("fabric-loader")
        dependencies.has("neoforge") && dependencies.length() == 2 -> "NeoForge " + dependencies.getString("neoforge")
        dependencies.length() == 1 -> "Vanilla"
        else -> error("El modpack requiere un cargador incompatible con esta versión de PRISSMA.")
    }
    require(supportsJavaLoader(version, loader)) { "Minecraft $version con $loader aún no está disponible en PRISSMA." }
    val files = index.getJSONArray("files")
    require(files.length() <= 4096) { "Demasiados archivos en el modpack." }
    val installed = buildList {
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            if (file.optJSONObject("env")?.optString("client") == "unsupported") continue
            val path = safeModpackPath(file.getString("path"))
            val size = file.getLong("fileSize")
            val hash = file.getJSONObject("hashes").getString("sha512").lowercase(Locale.ROOT)
            val urls = file.getJSONArray("downloads")
            require(size in 0..MAX_ARCHIVE && packHash.matches(hash) && urls.length() in 1..8) {
                "El índice del modpack contiene un archivo inválido."
            }
            add(ModpackFile(path, size, hash, List(urls.length()) { modpackDownloadUri(urls.getString(it)) }))
        }
    }
    require(installed.sumOf(ModpackFile::size) <= MAX_CONTENT &&
        installed.map { it.path.lowercase(Locale.ROOT) }.distinct().size == installed.size
    ) { "El modpack contiene archivos duplicados o supera el tamaño admitido." }
    return ModpackMetadata(index.getString("name").trim().take(72).ifBlank { "Modpack de Minecraft" }, version, loader, installed)
}

internal class ModpackImports(private val context: Context) {
    private fun archive(id: String): File {
        require(modpackId.matches(id))
        return File(context.filesDir, "modpack-imports/$id.mrpack")
    }

    suspend fun stage(uri: Uri): GameInstance = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val target = archive(id)
        target.parentFile?.mkdirs()
        try {
            val source = context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el archivo.")
            source.use { copyBounded(it, target, MAX_ARCHIVE) }
            val pack = ZipFile(target).use { readMetadata(it) }
            GameInstance(id, pack.name, EngineType.JAVA, pack.version, pack.loader)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    fun removeArchive(id: String) { archive(id).delete() }

    suspend fun install(instance: GameInstance, progress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        val source = archive(instance.id)
        val instances = File(context.filesDir, "instances").apply { mkdirs() }
        val destination = File(instances, instance.id)
        if (File(destination, ".prissma-modpack.ready").isFile) return@withContext
        check(!destination.exists()) { "Ya existe una carpeta para este modpack." }
        val staging = Files.createTempDirectory(instances.toPath(), ".modpack-").toFile()
        try {
            ZipFile(source).use { zip ->
                val pack = readMetadata(zip)
                check(pack.name == instance.title && pack.version == instance.version && pack.loader == instance.loader)
                pack.files.forEachIndexed { index, file ->
                    progress(.9f * index / pack.files.size, "Descargando ${index + 1} de ${pack.files.size} · ${File(file.path).name}")
                    downloadModpackFile(file, File(staging, file.path))
                }
                progress(.92f, "Aplicando la configuración del modpack")
                var budget = MAX_CONTENT - pack.files.sumOf(ModpackFile::size)
                for (prefix in listOf("overrides/", "client-overrides/")) {
                    val entries = zip.entries()
                    var count = 0
                    val installedPaths = hashSetOf<String>()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                        currentCoroutineContext().ensureActive()
                        val path = safeModpackPath(entry.name.removePrefix(prefix))
                        require(count++ < 4096 && installedPaths.add(path.lowercase(Locale.ROOT))) {
                            "Demasiados archivos o rutas duplicadas en la configuración."
                        }
                        require(entry.size in 0..minOf(MAX_ARCHIVE, budget)) { "El modpack supera el tamaño admitido." }
                        budget -= zip.getInputStream(entry).use { copyBounded(it, File(staging, path), entry.size) }
                    }
                }
            }
            File(staging, ".prissma-modpack.ready").writeText(instance.version)
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            progress(1f, "Modpack instalado")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun readMetadata(zip: ZipFile): ModpackMetadata {
        val entry = zip.getEntry("modrinth.index.json") ?: error("Selecciona un modpack .mrpack de Modrinth.")
        require(entry.size in 1..MAX_INDEX) { "Índice de modpack inválido." }
        val bytes = ByteArray(entry.size.toInt())
        DataInputStream(zip.getInputStream(entry)).use {
            it.readFully(bytes)
            require(it.read() == -1)
        }
        return decodeModpack(JSONObject(bytes.toString(Charsets.UTF_8)))
    }
}

private suspend fun downloadModpackFile(file: ModpackFile, destination: File) {
    var failure: IOException? = null
    for (source in file.urls) {
        try {
            var uri = source
            for (redirect in 0..5) {
                val connection = uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 12_000
                    connection.readTimeout = 25_000
                    connection.setRequestProperty("User-Agent", "PRISSMA Launcher (github.com/nikalbazu24/Prissma-Launcher)")
                    when (connection.responseCode) {
                        200 -> {
                            val sha = MessageDigest.getInstance("SHA-512")
                            val actualSize = DigestInputStream(connection.inputStream, sha).use { copyBounded(it, destination, file.size) }
                            val actualHash = sha.digest().joinToString("") { "%02x".format(it) }
                            if (actualSize != file.size || actualHash != file.sha512) throw IOException("El archivo no coincide con el índice.")
                            return
                        }
                        301, 302, 303, 307, 308 -> {
                            val location = connection.getHeaderField("Location") ?: throw IOException("Descarga sin destino.")
                            uri = modpackDownloadUri(uri.resolve(location).toString())
                        }
                        else -> throw IOException("HTTP ${connection.responseCode}")
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Demasiadas redirecciones.")
        } catch (error: IOException) {
            failure = error
            destination.delete()
        }
    }
    throw IOException("No se pudo verificar ${destination.name}.", failure)
}

private suspend fun copyBounded(input: InputStream, destination: File, limit: Long): Long {
    destination.parentFile?.mkdirs()
    var size = 0L
    val coroutine = currentCoroutineContext()
    destination.outputStream().buffered().use { output ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            coroutine.ensureActive()
            val count = input.read(buffer)
            if (count < 0) return size
            size += count
            if (size > limit) throw IOException("El archivo supera el tamaño declarado.")
            output.write(buffer, 0, count)
        }
    }
}
