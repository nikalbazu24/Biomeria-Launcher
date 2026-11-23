package com.biomeria.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModpackImportTest {
    @Test
    fun preservesPinnedFabricAndInstallsClientFilesOnly() {
        val excluded = file("mods/server-only.jar").put("env", JSONObject().put("client", "unsupported"))
        val pack = decodeModpack(packIndex(file("mods/client.jar"), excluded))
        assertEquals("Prueba", pack.name)
        assertEquals("1.21.4", pack.version)
        assertEquals("Fabric 0.16.9", pack.loader)
        assertEquals(listOf("mods/client.jar"), pack.files.map(ModpackFile::path))
    }

    @Test
    fun rejectsUnsafeArchivePathsAndDuplicateDownloads() {
        listOf("../mods/file.jar", "/mods/f.jar", "mods/../outside.jar", "mods\\f.jar",
            "mods//f.jar", ".biomeria-modpack.ready").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { safeModpackPath(path) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeModpack(packIndex(file("mods/a.jar"), file("mods/A.jar")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeModpack(packIndex(file("mods/a.jar", "http://cdn.modrinth.com/a.jar")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeModpack(packIndex(file("mods/a.jar", "https://127.0.0.1/a.jar")))
        }
    }

    private fun file(path: String, url: String = "https://cdn.modrinth.com/data/a/versions/b/a.jar") =
        JSONObject().put("path", path).put("fileSize", 42L)
            .put("hashes", JSONObject().put("sha512", "b".repeat(128))).put("downloads", JSONArray().put(url))

    private fun packIndex(vararg files: JSONObject) = JSONObject()
        .put("formatVersion", 1).put("game", "minecraft").put("name", "Prueba")
        .put("dependencies", JSONObject().put("minecraft", "1.21.4").put("fabric-loader", "0.16.9"))
        .put("files", JSONArray(files.toList()))
}
