package com.biomeria.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstanceContentTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun readsFabricFactsWithoutLoaderDependencies() {
        val facts = fabricModFacts(JSONObject("""{"id":"Iris","name":"Iris Shaders","version":"1.8.1",
            "depends":{"minecraft":"1.21.4","fabricloader":">=0.16","sodium":"*","fabric-api":"*"},"icon":"assets/iris/icon.png"}"""))
        assertEquals("iris", facts.id)
        assertEquals(setOf("sodium", "fabric-api"), facts.depends)
        assertEquals("assets/iris/icon.png", facts.iconPath)
        assertNull(fabricModFacts(JSONObject("""{"id":"a","version":"${'$'}{version}"}""")).version)
    }

    @Test
    fun readsNeoForgeToml() {
        val facts = neoForgeModFacts("""
            modLoader="javafml"
            [[mods]]
            modId="jei"
            version="19.21.0"
            displayName="Just Enough Items"
            [[dependencies.jei]]
            modId="neoforge"
            type="required"
            [[dependencies.jei]]
            modId="architectury"
            type="required"
        """.trimIndent())!!
        assertEquals("jei", facts.id)
        assertEquals("Just Enough Items", facts.name)
        assertEquals(setOf("architectury"), facts.depends)
    }

    @Test
    fun blocksTurningOffWhatOthersNeed() {
        val api = mod("fabric-api", emptySet())
        val sodium = mod("sodium", setOf("fabric-api"))
        val off = mod("iris", setOf("fabric-api"), enabled = false)
        assertEquals(listOf("sodium"), requiredBy(api, listOf(api, sodium, off)))
        assertTrue(requiredBy(sodium, listOf(api, sodium, off)).isEmpty())
    }

    @Test
    fun recognizesWhatAFileIs() {
        assertEquals(ImportKind.MOD, classifyImport("sodium.jar", listOf("fabric.mod.json")))
        assertEquals(ImportKind.MOD, classifyImport("raro.zip", listOf("META-INF/neoforge.mods.toml")))
        assertEquals(ImportKind.WORLD, classifyImport("Casa.zip", listOf("Casa/level.dat", "Casa/region/r.0.0.mca")))
        assertEquals("Casa/", worldRoot(listOf("Casa/level.dat")))
        assertEquals("", worldRoot(listOf("level.dat", "region/r.0.0.mca")))
        assertEquals(ImportKind.SHADER, classifyImport("BSL.zip", listOf("shaders/composite.fsh")))
        assertEquals(ImportKind.SHADER, classifyImport("BSL.zip", listOf("BSL/shaders/final.fsh")))
        assertEquals(ImportKind.RESOURCE_PACK, classifyImport("Faithful.zip", listOf("pack.mcmeta", "assets/x.png")))
        assertNull(classifyImport("fotos.zip", listOf("foto.jpg")))
    }

    @Test
    fun onlyOffersRealModrinthUpdates() {
        val version = JSONObject().put("version_number", "0.6.0").put("files", JSONArray().put(JSONObject()
            .put("primary", true).put("filename", "sodium-0.6.0.jar").put("size", 10)
            .put("url", "https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium-0.6.0.jar")
            .put("hashes", JSONObject().put("sha512", "b".repeat(128)))))
        assertEquals("0.6.0", parseModUpdate(version, "a".repeat(128))?.versionNumber)
        assertNull(parseModUpdate(version, "b".repeat(128)))
        version.getJSONArray("files").getJSONObject(0).put("url", "https://evil.example/sodium.jar")
        assertThrows(IllegalArgumentException::class.java) { parseModUpdate(version, "a".repeat(128)) }
    }

    @Test
    fun keepsTheNewestTenLogs() {
        val game = temp.newFolder("game")
        repeat(12) { i ->
            File(game, "biomeria_last.log").apply { writeText("partida $i"); setLastModified(1_000_000L + i * 1000) }
            archiveLastLog(game)
        }
        val kept = File(game, LOG_FOLDER).listFiles()!!.map { it.readText() }.toSet()
        assertEquals(10, kept.size)
        assertTrue("partida 11" in kept && "partida 0" !in kept)
    }

    private fun mod(id: String, depends: Set<String>, enabled: Boolean = true) =
        ManagedMod(File("/mods/$id.jar"), id, id, null, null, enabled, depends, null)
}
