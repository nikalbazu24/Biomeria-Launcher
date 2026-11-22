package com.prissma.launcher.engine.rust

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StarlingReportTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun importsDeclaredLogsAndSessionDataInDisplayOrder() {
        val entries = mapOf(
            "android-exits.json" to "[]", "latest.log" to "Starling started\nRenderer ready",
            "launcher-latest.log" to "Launcher ready", "previous.log" to "Previous session",
            "crash-2026-11-22.txt" to "Crash report", "undocumented.txt" to "Ignore this"
        )
        val zip = zip("starling-diagnostics-1729.zip", entries, entries.keys - "undocumented.txt")

        val result = readStarlingReport(zip, 1729L)
        assertEquals("0.9.0", result.version)
        assertEquals(1729L, result.importedAt)
        assertEquals(listOf("latest.log", "launcher-latest.log", "previous.log", "android-exits.json", "crash-2026-11-22.txt"),
            result.logs.keys.toList())
        assertEquals(entries["latest.log"], result.logs["latest.log"])
        assertNull(result.logs["undocumented.txt"])
        assertEquals(result, StarlingReport.fromJson(JSONObject(result.json().toString())))
    }

    @Test
    fun onlyReadsRecognizedEntriesEvenWhenManifestListsOthers() {
        val entries = mapOf("../latest.log" to "Outside archive path", "account.json" to "Cannot be shown",
            "current-session.json" to "{\"phase\":\"native_start\"}")
        val zip = zip("diagnostic.zip", entries, entries.keys)
        assertEquals(mapOf("current-session.json" to entries.getValue("current-session.json")), readStarlingReport(zip, 0).logs)
    }

    @Test
    fun rejectsUnrelatedZipAndATruncatedArchive() {
        val unrelated = zip("world.zip", mapOf("latest.log" to "Other source"), declared = emptySet())
        assertThrows(IOException::class.java) { readStarlingReport(unrelated, 0) }

        val valid = zip("complete.zip", mapOf("latest.log" to "Hello"), setOf("latest.log")).readBytes()
        val truncated = folder.newFile("truncated.zip").apply { writeBytes(valid.copyOf(valid.size - 10)) }
        assertThrows(IOException::class.java) { readStarlingReport(truncated, 0) }
    }

    @Test
    fun rejectsMissingAndOversizedLogEntries() {
        val missing = zip("missing.zip", emptyMap(), setOf("latest.log"))
        assertThrows(IOException::class.java) { readStarlingReport(missing, 0) }

        val oversized = zip("very-small-zip.zip", mapOf("latest.log" to "x".repeat(513 * 1024)), setOf("latest.log"))
        assertTrue(oversized.length() < STARLING_REPORT_LIMIT)
        assertThrows(IOException::class.java) { readStarlingReport(oversized, 0) }
    }

    @Test
    fun reportsIncludedRustEngineAndMissingRuntime() {
        val bundled = StarlingInstallation("0.9.0", canOpen = true)
        val missing = StarlingInstallation("0.9.0", canOpen = false)
        assertEquals("Rust integrado · Starling 0.9.0", bundled.label)
        assertNull(bundled.launchProblem())
        assertEquals("Falta el motor Rust en esta compilación de PRISSMA.", missing.launchProblem())
        assertEquals(missing.launchProblem(), (null as StarlingInstallation?).launchProblem())
    }

    private fun zip(name: String, entries: Map<String, String>, declared: Set<String>): File =
        folder.newFile(name).apply {
            ZipOutputStream(outputStream()).use { output ->
                val manifest = JSONObject().put("schema", 1).put("version", "0.9.0").put("included", JSONArray(declared))
                for ((path, body) in entries + ("manifest.json" to manifest.toString())) {
                    output.putNextEntry(ZipEntry(path))
                    output.write(body.toByteArray(Charsets.UTF_8))
                    output.closeEntry()
                }
            }
        }
}
