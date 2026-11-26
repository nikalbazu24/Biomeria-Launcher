package com.biomeria.launcher.engine.java

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NeoForgeInstallerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun onlyTheSupportedGameVersionCanSelectNeoForge() {
        assertEquals(listOf("Vanilla", "Fabric", "Quilt", "NeoForge"), javaLoaders("1.21.4"))
        assertEquals(listOf("Vanilla", "Fabric", "Quilt"), javaLoaders("1.21.5"))
        assertEquals(listOf("Vanilla", "Fabric", "Quilt"), javaLoaders("1.20.1"))
        assertEquals(listOf("Vanilla"), javaLoaders("1.16.5"))
        assertTrue(supportsJavaLoader("1.21.4", "NeoForge $NEOFORGE_VERSION"))
        assertFalse(supportsJavaLoader("1.21.4", "NeoForge 21.4.1"))
        assertFalse(supportsJavaLoader("1.21.5", "NeoForge"))
        assertTrue(supportsJavaLoader("1.21.4", "Fabric 0.16.9"))
    }

    @Test
    fun mavenPathsSupportClassifiersAndMappingFiles() {
        assertEquals("net/neoforged/installertools/binarypatcher/2.1.2/binarypatcher-2.1.2-fatjar.jar",
            mavenPath("net.neoforged.installertools:binarypatcher:2.1.2:fatjar"))
        assertEquals("net/minecraft/client/1.21.4/client-1.21.4-mappings.txt",
            mavenPath("net.minecraft:client:1.21.4:mappings@txt"))
        assertThrows(IllegalArgumentException::class.java) { mavenPath("net.minecraft:client:1.21.4@../jar") }
    }

    @Test
    fun processorPlanUsesClientArtifactsAndJniModuleSyntax() {
        val root = folder.newFolder("profile with spaces")
        val client = File(root, "vanilla.jar")
        val plan = File(root, "config/plan.properties")
        val installer = File(root, "installer.jar")
        val tool = "cpw.mods:bootstraplauncher:2.0.2"
        val jar = mavenPath(tool)
        val library = JSONObject("""{"name":"$tool","downloads":{"artifact":{
            "url":"https://maven.neoforged.net/releases/$jar","path":"$jar","sha1":"${"a".repeat(40)}"}}}""")
        val mappings = JSONObject("""{"downloads":{"client_mappings":{
            "url":"https://piston-data.mojang.com/example","sha1":"${"b".repeat(40)}"}}}""")
        val version = JSONObject().put("id", "neoforge-$NEOFORGE_VERSION").put("inheritsFrom", NEOFORGE_GAME_VERSION)
            .put("mainClass", "cpw.mods.bootstraplauncher.BootstrapLauncher").put("libraries", JSONArray().put(library))
            .put("arguments", JSONObject().put("game", JSONArray(listOf("--launchTarget", "neoforgeclient")))
                .put("jvm", JSONArray(listOf("-p", "\${library_directory}/$jar", "--add-modules", "ALL-MODULE-PATH",
                    "--add-opens", "java.base/java.util.jar=cpw.mods.securejarhandler"))))
        val data = JSONObject()
        listOf("MAPPINGS", "MOJMAPS", "MERGED_MAPPINGS", "MC_SLIM", "MC_EXTRA", "MC_SRG", "PATCHED").forEach { key ->
            data.put(key, JSONObject().put("client", "[net.minecraft:client:1.21.4:$key]"))
        }
        data.put("BINPATCH", JSONObject().put("client", "/data/client.lzma"))
        fun processor(args: List<String>) = JSONObject().put("jar", tool).put("classpath", JSONArray(listOf(tool)))
            .put("args", JSONArray(args))
        val profile = JSONObject().put("minecraft", NEOFORGE_GAME_VERSION).put("data", data)
            .put("libraries", JSONArray().put(library)).put("processors", JSONArray(listOf(
                processor(listOf("server task")).put("sides", JSONArray(listOf("server"))),
                processor(listOf("--task", "DOWNLOAD_MOJMAPS")),
                processor(listOf("--input", "{MINECRAFT_JAR}", "--apply", "{BINPATCH}"))
            )))
        ZipOutputStream(installer.outputStream()).use { zip ->
            mapOf("install_profile.json" to profile.toString(), "version.json" to version.toString(),
                "data/client.lzma" to "patch").forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }

        val prepared = neoForgeInstall(installer, root, client, plan, mappings)
        prepared.writePlan()
        val properties = Properties().apply { plan.inputStream().use { load(it) } }
        assertEquals("1", properties.getProperty("count"))
        assertEquals(client.absolutePath, properties.getProperty("0.arg.1"))
        assertEquals(prepared.patch.absolutePath, properties.getProperty("0.arg.3"))
        assertEquals("patch", prepared.patch.readText())
        assertTrue(properties.getProperty("runtime").endsWith("client-1.21.4-PATCHED.jar"))
        assertEquals(2, prepared.downloads.size)
        assertEquals(listOf("--launchTarget", "neoforgeclient"), prepared.gameArguments)
        assertTrue("--module-path=${File(root, "libraries/$jar").absolutePath}" in prepared.jvmArguments)
        assertTrue("--add-modules=ALL-MODULE-PATH" in prepared.jvmArguments)
        assertTrue("--add-opens=java.base/java.util.jar=cpw.mods.securejarhandler" in prepared.jvmArguments)
        assertThrows(IllegalStateException::class.java) { neoForgeJvmOptions(listOf("-javaagent:external.jar"), root, "neo") }
    }
}
