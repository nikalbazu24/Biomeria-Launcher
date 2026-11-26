package com.biomeria.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CurseForgeModpackTest {
    @Test
    fun readsFabricManifestAndSkipsOptionalFiles() {
        val pack = decodeCurseForgeManifest(manifest("fabric-0.16.9",
            ref(10, 100), ref(11, 101).put("required", false)))
        assertEquals("Pack CF", pack.name)
        assertEquals("1.21.4", pack.version)
        assertEquals("Fabric 0.16.9", pack.loader)
        assertEquals("overrides", pack.overrides)
        assertEquals(listOf(CurseForgeFileRef(10, 100)), pack.files)
    }

    @Test
    fun explainsUnsupportedLoaders() {
        val forge = assertThrows(IllegalStateException::class.java) { decodeCurseForgeManifest(manifest("forge-52.0.1")) }
        assertTrue(forge.message!!.contains("Forge"))
        assertEquals("Quilt 0.26.0", decodeCurseForgeManifest(manifest("quilt-0.26.0")).loader)
    }

    @Test
    fun rejectsUnsafeOverridesFolder() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeCurseForgeManifest(manifest("fabric-0.16.9").put("overrides", "../x"))
        }
    }

    @Test
    fun placesFilesByCategoryAndBuildsCdnAddress() {
        val files = JSONArray()
            .put(cfFile(1234567, 10, "sodium.jar", "https://edge.forgecdn.net/files/1234/567/sodium.jar"))
            .put(cfFile(7654321, 20, "Faithful 32x.zip", null))
            .put(cfFile(1111111, 30, "complementary.zip", ""))
        val projects = JSONArray()
            .put(JSONObject().put("id", 10).put("classId", 6))
            .put(JSONObject().put("id", 20).put("classId", 12))
            .put(JSONObject().put("id", 30).put("classId", 6552))
        val downloads = curseForgeDownloads(files, projects)
        assertEquals(listOf("mods/sodium.jar", "resourcepacks/Faithful 32x.zip", "shaderpacks/complementary.zip"),
            downloads.map(ModpackFile::path))
        assertEquals("https://edge.forgecdn.net/files/7654/321/Faithful%2032x.zip", downloads[1].urls.single().toString())
        assertTrue(downloads.all { it.algorithm == "SHA-1" && it.hash == "a".repeat(40) })
    }

    @Test
    fun rejectsFilesWithoutSha1OrWithPaths() {
        val noHash = cfFile(1, 10, "a.jar", null).put("hashes", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { curseForgeDownloads(JSONArray().put(noHash), JSONArray()) }
        val traversal = cfFile(1, 10, "../a.jar", null)
        assertThrows(IllegalArgumentException::class.java) { curseForgeDownloads(JSONArray().put(traversal), JSONArray()) }
    }

    private fun ref(project: Int, file: Int) = JSONObject().put("projectID", project).put("fileID", file).put("required", true)

    private fun manifest(loader: String, vararg files: JSONObject) = JSONObject()
        .put("manifestType", "minecraftModpack").put("manifestVersion", 1).put("name", "Pack CF").put("version", "1.0")
        .put("minecraft", JSONObject().put("version", "1.21.4")
            .put("modLoaders", JSONArray().put(JSONObject().put("id", loader).put("primary", true))))
        .put("files", JSONArray(files.toList())).put("overrides", "overrides")

    private fun cfFile(id: Int, mod: Int, name: String, url: String?) = JSONObject()
        .put("id", id).put("modId", mod).put("fileName", name).put("fileLength", 42L)
        .put("hashes", JSONArray().put(JSONObject().put("algo", 1).put("value", "A".repeat(40))))
        .apply { if (url != null) put("downloadUrl", url) }
}
