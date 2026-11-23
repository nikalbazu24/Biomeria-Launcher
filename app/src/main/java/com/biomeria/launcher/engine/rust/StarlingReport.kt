package com.biomeria.launcher.engine.rust

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

internal const val STARLING_REPORT_LIMIT = 6L * 1024 * 1024
private const val TEXT_LIMIT = 512 * 1024

private val diagnosticFiles = listOf(
    "latest.log", "logs/latest.log", "launcher-latest.log", "previous.log", "logs/previous.log",
    "previous-run.log", "launcher-previous.log", "last-rust-panic.txt", "previous-error.txt",
    "current-session.json", "previous-session.json", "android-exits.json"
)
private val crashReportName = Regex("crash-[A-Za-z0-9_.-]{1,100}\\.(txt|log)")

private fun diagnosticEntries(listed: Set<String>) =
    diagnosticFiles.filter(listed::contains) + listed.filter(crashReportName::matches).sortedDescending().take(3)

internal data class StarlingReport(val version: String, val importedAt: Long, val logs: Map<String, String>) {
    fun json() = JSONObject().put("version", version).put("importedAt", importedAt).put("logs", JSONObject(logs))

    companion object {
        fun fromJson(json: JSONObject): StarlingReport {
            val entries = json.getJSONObject("logs")
            return StarlingReport(json.getString("version"), json.getLong("importedAt"),
                diagnosticEntries(entries.keys().asSequence().toSet()).associateWith(entries::getString))
        }
    }
}

internal fun readStarlingReport(file: File, now: Long): StarlingReport {
    if (file.length() > STARLING_REPORT_LIMIT) throw IOException("El ZIP de diagnóstico supera 6 MiB.")
    return try {
        ZipFile(file).use { zip ->
            val manifest = JSONObject(zip.diagnosticText("manifest.json"))
            val version = manifest.optString("version").trim().take(80)
            val included = manifest.optJSONArray("included")
            if (manifest.optInt("schema") != 1 || version.isBlank() || included == null || included.length() > 32) {
                throw IOException("El ZIP no contiene un manifiesto de diagnóstico de Starling compatible.")
            }
            val listed = (0 until included.length()).map(included::getString).toSet()
            val logs = diagnosticEntries(listed).associateWith(zip::diagnosticText)
            if (logs.isEmpty()) throw IOException("El informe de Starling todavía no contiene registros para mostrar.")
            StarlingReport(version, now, logs)
        }
    } catch (error: JSONException) {
        throw IOException("El manifiesto del diagnóstico de Starling está dañado.", error)
    } catch (error: ZipException) {
        throw IOException("El ZIP de diagnóstico está dañado o incompleto.", error)
    }
}

private fun ZipFile.diagnosticText(name: String): String {
    val entry = getEntry(name) ?: throw IOException("Falta $name en el ZIP de diagnóstico.")
    if (entry.isDirectory || entry.size !in 0L..TEXT_LIMIT.toLong()) {
        throw IOException("$name supera el tamaño admitido para un registro de Starling.")
    }
    val bytes = ByteArrayOutputStream(entry.size.toInt())
    val checksum = CRC32()
    getInputStream(entry).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > TEXT_LIMIT) throw IOException("$name supera el tamaño admitido.")
            bytes.write(buffer, 0, count)
            checksum.update(buffer, 0, count)
        }
    }
    if (bytes.size().toLong() != entry.size || checksum.value != entry.crc) throw IOException("$name está dañado.")
    return bytes.toString(Charsets.UTF_8.name())
}
