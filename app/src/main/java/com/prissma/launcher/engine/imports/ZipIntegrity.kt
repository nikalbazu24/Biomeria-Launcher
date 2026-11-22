package com.prissma.launcher.engine.imports

import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal fun ZipFile.verifyEntries(checkCancelled: () -> Unit, visit: (ZipEntry, String, InputStream) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    for (entry in entries().asSequence()) {
        checkCancelled()
        val name = safeArchivePath(entry.name)
        if (entry.isDirectory) continue
        val checksum = CRC32()
        CheckedInputStream(getInputStream(entry), checksum).use { input ->
            visit(entry, name, input)
            while (true) {
                checkCancelled()
                if (input.read(buffer) < 0) break
            }
        }
        if (entry.crc != checksum.value) throw IOException("El archivo contiene datos dañados.")
    }
}

internal fun safeArchivePath(path: String): String {
    val normalized = path.replace('\\', '/')
    val parts = normalized.split('/')
    if (normalized.startsWith('/') || ':' in parts.first() || '\u0000' in normalized || ".." in parts) {
        throw IOException("El archivo contiene una ruta inválida.")
    }
    return parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
}
