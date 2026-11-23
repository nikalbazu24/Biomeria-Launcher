package com.biomeria.launcher.engine.java

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val sha1Pattern = Regex("[0-9a-f]{40}")
private val indexIdPattern = Regex("[A-Za-z0-9_-]+")

internal data class IndexedAsset(val hash: String, val size: Long) {
    init {
        require(sha1Pattern.matches(hash)) { "El índice contiene un SHA-1 inválido." }
        require(size >= 0) { "El índice contiene un tamaño inválido." }
    }

    val objectPath: String get() = hash.take(2) + "/" + hash
}

internal fun indexedAssets(index: JSONObject): List<IndexedAsset> {
    val objects = index.getJSONObject("objects")
    val byHash = mutableMapOf<String, IndexedAsset>()
    for (name in objects.keys()) {
        val entry = objects.getJSONObject(name)
        val asset = IndexedAsset(entry.getString("hash"), entry.getLong("size"))
        val previous = byHash.putIfAbsent(asset.hash, asset)
        require(previous == null || previous == asset) { "El índice contiene tamaños incompatibles." }
    }
    return byHash.values.toList()
}

internal class MojangAssets(
    private val assetsDir: File,
    private val download: suspend (String, File) -> Unit
) {
    suspend fun prepare(index: JSONObject, onProgress: (Int, Int) -> Unit): String {
        val id = index.getString("id")
        require(indexIdPattern.matches(id)) { "El ID del índice de recursos es inválido." }
        val indexFile = File(assetsDir, "indexes/" + id + ".json")
        ensureVerifiedFile(index.getString("url"), indexFile, IndexedAsset(index.getString("sha1"), index.getLong("size")))
        val assets = indexedAssets(JSONObject(indexFile.readText(Charsets.UTF_8)))
        onProgress(0, assets.size)

        val workerCount = minOf(8, assets.size)
        val progressLock = Any()
        var completed = 0
        coroutineScope {
            repeat(workerCount) { worker ->
                launch {
                    for (position in worker until assets.size step workerCount) {
                        currentCoroutineContext().ensureActive()
                        val asset = assets[position]
                        val path = asset.objectPath
                        ensureVerifiedFile(
                            "https://resources.download.minecraft.net/" + path,
                            File(assetsDir, "objects/" + path),
                            asset
                        )
                        synchronized(progressLock) {
                            completed += 1
                            if (completed % 25 == 0 || completed == assets.size) onProgress(completed, assets.size)
                        }
                    }
                }
            }
        }
        return id
    }

    private suspend fun ensureVerifiedFile(url: String, file: File, expected: IndexedAsset) {
        if (matches(file, expected)) return
        val directory = checkNotNull(file.parentFile)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("No se pudo preparar la carpeta de recursos de Minecraft.")
        }
        repeat(3) { attempt ->
            val partial = File.createTempFile("mojang-", ".part", directory)
            try {
                download(url, partial)
                if (!matches(partial, expected)) throw IOException("SHA-1 o tamaño incorrecto para " + file.name)
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (error: IOException) {
                if (attempt == 2) throw IOException("No se pudo verificar el recurso " + file.name, error)
                delay(300L * (attempt + 1))
            } finally {
                partial.delete()
            }
        }
    }

    private fun matches(file: File, expected: IndexedAsset): Boolean {
        if (!file.isFile || file.length() != expected.size) return false
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected.hash
    }
}
