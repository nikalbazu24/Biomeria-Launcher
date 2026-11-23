package com.biomeria.launcher.engine.bedrock

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest

internal data class BedrockLibraryItem(val id: String, val type: BedrockArchive, val file: File, val size: Long) {
    val name: String get() = file.name
}

internal data class BedrockLibraryImport(val item: BedrockLibraryItem, val added: Boolean)

internal class BedrockLibrary(files: File, private val cache: File, instanceId: String) {
    private val directory = File(files, sha256().digest(instanceId.toByteArray(Charsets.UTF_8)).hex())

    fun list(): List<BedrockLibraryItem> = synchronized(lock) {
        if (!directory.exists()) return@synchronized emptyList()
        val folders = directory.listFiles() ?: throw IOException("No se pudo abrir la biblioteca de la instancia.")
        folders.mapNotNull { find(it.name) }.sortedWith(compareBy({ it.type }, { it.name.lowercase() }, { it.id }))
    }

    fun importArchive(
        name: String,
        input: InputStream,
        checkCancelled: () -> Unit = {},
        onVerify: () -> Unit = {}
    ): BedrockLibraryImport {
        val type = BedrockArchive.fromName(name)
            ?: throw InvalidBedrockArchive("Elige un archivo .mcpack, .mcaddon o .mcworld.")
        val folder = stagingFolder()
        try {
            val stem = name.substringBeforeLast('.').take(64).replace(unsafeFilename, "_").trim(' ', '.')
            val file = File(folder, "${stem.ifBlank { "Minecraft" }}.${type.extension}")
            val digest = sha256()
            DigestOutputStream(file.outputStream(), digest).use { output ->
                input.copyArchiveTo(output, checkCancelled)
            }
            onVerify()
            validateBedrockArchive(file, type, checkCancelled)
            val id = "${digest.digest().hex()}.${type.extension}"
            return synchronized(lock) {
                checkCancelled()
                val existing = find(id)
                if (existing != null) return@synchronized BedrockLibraryImport(existing, added = false)
                Files.createDirectories(directory.toPath())
                val target = File(directory, id)
                val size = file.length()
                Files.move(folder.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                BedrockLibraryImport(BedrockLibraryItem(id, type, File(target, file.name), size), added = true)
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    fun item(id: String): BedrockLibraryItem? = synchronized(lock) { find(id) }

    fun remove(id: String): Boolean = synchronized(lock) {
        val item = find(id) ?: return@synchronized false
        val trash = stagingFolder()
        try {
            Files.move(item.file.parentFile.toPath(), File(trash, "removed").toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } finally {
            trash.deleteRecursively()
        }
    }

    private fun find(id: String): BedrockLibraryItem? {
        if (!entryId.matches(id)) return null
        val type = BedrockArchive.fromName(id) ?: return null
        val file = File(directory, id).listFiles()?.singleOrNull() ?: return null
        return if (file.isFile && BedrockArchive.fromName(file.name) == type) {
            BedrockLibraryItem(id, type, file, file.length())
        } else null
    }

    private fun stagingFolder(): File {
        Files.createDirectories(cache.toPath())
        return Files.createTempDirectory(cache.toPath(), "archive-").toFile()
    }

    private companion object {
        val lock = Any()
        val unsafeFilename = Regex("[^\\p{L}\\p{M}\\p{N} _().-]")
        val entryId = Regex("[a-f0-9]{64}\\.(mcpack|mcaddon|mcworld)")
    }
}

internal fun InputStream.copyArchiveTo(output: OutputStream, checkCancelled: () -> Unit) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        checkCancelled()
        val count = read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}

private fun sha256() = MessageDigest.getInstance("SHA-256")

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
