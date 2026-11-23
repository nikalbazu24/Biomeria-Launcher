package com.biomeria.launcher.engine.bedrock

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal enum class BedrockArchive(val extension: String, val label: String) {
    PACK("mcpack", "Paquete"), ADDON("mcaddon", "Complemento"), WORLD("mcworld", "Mundo");

    companion object {
        fun fromName(name: String) = entries.firstOrNull { name.endsWith(".${it.extension}", ignoreCase = true) }
    }
}

internal class InvalidBedrockArchive(message: String) : IOException(message)

internal fun validateBedrockArchive(file: File, type: BedrockArchive, checkCancelled: () -> Unit = {}) {
    val contents = BedrockContents(type, checkCancelled)
    try {
        ZipFile(file).use { zip ->
            for (entry in zip.entries().asSequence()) {
                checkCancelled()
                val name = archivePath(entry.name)
                if (entry.isDirectory) continue
                val checksum = CRC32()
                CheckedInputStream(zip.getInputStream(entry), checksum).use { input ->
                    contents.inspect(name, input)
                    contents.drain(input)
                }
                if (entry.crc != checksum.value) throw InvalidBedrockArchive("El archivo contiene datos dañados.")
            }
        }
        contents.requireContent()
    } catch (error: InvalidBedrockArchive) {
        throw error
    } catch (error: IOException) {
        throw InvalidBedrockArchive("El archivo está dañado o incompleto.")
    }
}

private class BedrockContents(private val type: BedrockArchive, private val checkCancelled: () -> Unit) {
    private val buffer = ByteArray(64 * 1024)
    private var found = false

    fun inspect(name: String, input: InputStream) {
        val filename = name.substringAfterLast('/')
        val topLevel = name.count { it == '/' } <= 1
        val nestedType = BedrockArchive.fromName(filename)
        when {
            type == BedrockArchive.ADDON && (nestedType == BedrockArchive.PACK || nestedType == BedrockArchive.WORLD) -> {
                validateNested(input, nestedType)
                found = true
            }
            (type == BedrockArchive.PACK || type == BedrockArchive.ADDON) && topLevel && filename.equals("manifest.json", true) -> {
                validateManifest(input)
                found = true
            }
            type == BedrockArchive.WORLD && topLevel && filename.equals("level.dat", true) -> {
                DataInputStream(input).readFully(ByteArray(9)) // Bedrock header followed by world data.
                found = true
            }
        }
    }

    private fun validateNested(input: InputStream, type: BedrockArchive) {
        val inner = BedrockContents(type, checkCancelled)
        val wrapper = object : FilterInputStream(input) { override fun close() = Unit }
        ZipInputStream(wrapper).use { zip ->
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                val name = archivePath(entry.name)
                if (!entry.isDirectory) inner.inspect(name, zip)
                inner.drain(zip)
                zip.closeEntry()
            }
        }
        inner.requireContent()
    }

    private fun validateManifest(input: InputStream) {
        val json = ByteArrayOutputStream()
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (json.size() + count > 1_048_576) throw InvalidBedrockArchive("El manifiesto del paquete supera 1 MiB.")
            json.write(buffer, 0, count)
        }
        val manifest = try {
            JSONObject(json.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF"))
        } catch (error: JSONException) {
            throw InvalidBedrockArchive("El manifiesto del paquete está dañado.")
        }
        if (manifest.optJSONObject("header") == null || (manifest.optJSONArray("modules")?.length() ?: 0) == 0) {
            throw InvalidBedrockArchive("El manifiesto no describe un paquete de Bedrock.")
        }
    }

    fun drain(input: InputStream) {
        while (true) {
            checkCancelled()
            if (input.read(buffer) < 0) return
        }
    }

    fun requireContent() {
        if (found) return
        val expected = when (type) {
            BedrockArchive.PACK -> "un manifiesto de paquete"
            BedrockArchive.ADDON -> "un paquete o mundo de Bedrock"
            BedrockArchive.WORLD -> "los datos del mundo (level.dat)"
        }
        throw InvalidBedrockArchive("El archivo .${type.extension} no contiene $expected.")
    }
}

private fun archivePath(path: String): String {
    val normalized = path.replace('\\', '/')
    val parts = normalized.split('/')
    if (normalized.startsWith('/') || ':' in parts.first() || '\u0000' in normalized || ".." in parts) {
        throw InvalidBedrockArchive("El archivo contiene una ruta inválida.")
    }
    return parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
}

internal enum class BedrockImportDelivery { SENT, NO_GAME, NO_IMPORTER }

internal fun deliverBedrockArchive(
    type: BedrockArchive,
    targetPackage: String?,
    open: (packageName: String, mimeType: String) -> Boolean
): BedrockImportDelivery {
    if (targetPackage == null) return BedrockImportDelivery.NO_GAME
    val mimeTypes = listOf("application/octet-stream", "application/x-${type.extension}",
        "application/${type.extension}", "application/zip")
    return if (mimeTypes.any { open(targetPackage, it) }) BedrockImportDelivery.SENT else BedrockImportDelivery.NO_IMPORTER
}
