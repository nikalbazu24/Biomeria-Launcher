package com.prissma.launcher.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModrinthInstallationTest {
    @get:Rule val folder = TemporaryFolder()

    private val responses = mutableMapOf<String, ByteArray>()
    private val requests = mutableListOf<String>()
    private val gameVersion = "1.21.4"
    private val mods = WorkshopCategory.MODS

    @Test
    fun preflightResolvesTransitivePinsAndInstallsTheConfirmedVersions() = runBlocking {
        val coreNew = version("Core", "CoreNew")
        val coreOld = version("Core", "CoreOld")
        val addon = version("Addon", "AddonA", dependencies = listOf(dependency("Core", "CoreOld")))
        val root = version("Root", "RootA", dependencies = listOf(
            dependency("Core"), dependency("Addon"), dependency("Optional", type = "optional")
        ))
        publish(root)
        publish(addon)
        publish(coreNew, coreOld)
        val repository = repository()

        val plan = repository.checkCompatibility("Root", mods, gameVersion, "fabric")
        assertEquals(listOf("RootA", "CoreOld", "AddonA"), plan.artifacts.map { it.versionId })
        assertTrue(requests.none { it.endsWith(".jar") || it.contains("Optional") })

        // A later publication cannot change the files the player already confirmed.
        publish(version("Root", "RootB"), root)
        val progress = mutableListOf<WorkshopInstallProgress>()
        assertEquals(3, repository.install("Root", "save", mods, gameVersion, "fabric", plan.pins) { progress.add(it) })

        val jars = File(folder.root, "instances/save/mods").list().orEmpty().toSet()
        assertEquals(setOf("RootA.jar", "CoreOld.jar", "AddonA.jar"), jars)
        assertEquals(setOf("RootA.jar", "CoreOld.jar", "AddonA.jar"), progress
            .filter { it.stage == WorkshopInstallStage.INSTALLING }.mapNotNull { it.fileName }.toSet())
        assertEquals(3, repository.getInstalledFiles("save", gameVersion, "fabric").size)
    }

    @Test
    fun rejectsVersionsThatOnlyMatchMinecraftOrFabricAndDoesNotDownload() = runBlocking {
        publish(version("Root", "ForgeA", loader = "forge"), version("Root", "FabricB", minecraft = "1.21.5"))
        val failure = runCatching { repository().checkCompatibility("Root", mods, gameVersion, "fabric") }.exceptionOrNull()
        assertTrue(failure is WorkshopIncompatibility)
        assertTrue(failure?.message.orEmpty().contains("Fabric en Minecraft 1.21.4"))
        assertFalse(requests.any { it.endsWith(".jar") })
        assertFalse(File(folder.root, "instances").exists())
        assertFalse(shouldRetryWorkshopDownload(checkNotNull(failure as? Exception), attemptCount = 0))
    }

    @Test
    fun neoforgeSelectsAndInstallsOnlyItsOwnVersionsAndDependencies() = runBlocking {
        publish(version("Example", "FabricA"), version("Example", "NeoA", loader = "neoforge",
            dependencies = listOf(dependency("Library"))))
        publish(version("Library", "ForgeLib", loader = "forge"), version("Library", "NeoLib", loader = "neoforge"))
        val repository = repository()

        val plan = repository.checkCompatibility("Example", mods, gameVersion, "neoforge")
        assertEquals(listOf("NeoA", "NeoLib"), plan.artifacts.map { it.versionId })
        assertEquals(2, repository.install("Example", "neoforge", mods, gameVersion, "neoforge", plan.pins))
        val installed = repository.getInstalledFiles("neoforge", gameVersion, "neoforge")
        assertEquals(setOf("NeoA.jar", "NeoLib.jar"), installed.map { it.fileName }.toSet())
        assertTrue(installed.all { it.compatibility == "Compatible con 1.21.4 · NeoForge" })
        assertFalse(requests.any { it == "/mod/FabricA.jar" || it == "/mod/ForgeLib.jar" })
    }

    @Test
    fun interruptedDownloadDoesNotPublishAndRetryReusesVerifiedDependency() = runBlocking {
        val dependency = version("Core", "CoreA")
        val root = version("Root", "RootA", dependencies = listOf(dependency("Core")))
        publish(root)
        publish(dependency)
        val original = responses.getValue("/mod/RootA.jar")
        responses["/mod/RootA.jar"] = original.copyOf(4)
        val repository = repository()
        val plan = repository.checkCompatibility("Root", mods, gameVersion, "fabric")
        val gameDir = File(folder.root, "instances/save")
        val progress = mutableListOf<WorkshopInstallProgress>()

        val failure = runCatching {
            repository.install("Root", "save", mods, gameVersion, "fabric", plan.pins) { progress.add(it) }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("RootA.jar"))
        assertTrue(shouldRetryWorkshopDownload(checkNotNull(failure as? IOException), attemptCount = 0))
        assertFalse(shouldRetryWorkshopDownload(failure as IOException, attemptCount = 2))
        assertTrue(File(gameDir, "mods").listFiles().orEmpty().isEmpty())
        assertFalse(File(gameDir, "workshop/.staging").exists())
        assertFalse(progress.any { it.stage == WorkshopInstallStage.INSTALLING })

        responses["/mod/RootA.jar"] = original
        assertEquals(2, repository.install("Root", "save", mods, gameVersion, "fabric", plan.pins))
        assertEquals(1, requests.count { it == "/mod/CoreA.jar" })
        assertEquals(2, requests.count { it == "/mod/RootA.jar" })
        assertEquals(setOf("CoreA.jar", "RootA.jar"), File(gameDir, "mods").list().orEmpty().toSet())
        assertFalse(File(gameDir, "workshop/.downloads/MODS-Root").exists())
    }

    private fun repository() = ModrinthRepository(folder.root) { endpoint ->
        val url = URL(endpoint)
        requests.add(url.path)
        val body = responses[url.path]
        object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = if (body == null) 404 else 200
            override fun getInputStream() = ByteArrayInputStream(checkNotNull(body))
        }
    }

    private fun publish(vararg versions: JSONObject) {
        val project = versions.first().getString("project_id")
        responses["/v2/project/$project/version"] = JSONArray(versions.toList()).toString().toByteArray()
        versions.forEach { version ->
            val id = version.getString("id")
            responses["/v2/version/$id"] = version.toString().toByteArray()
            responses["/mod/$id.jar"] = archive(id)
        }
    }

    private fun version(
        project: String,
        id: String,
        loader: String = "fabric",
        minecraft: String = gameVersion,
        dependencies: List<JSONObject> = emptyList()
    ): JSONObject {
        val hash = MessageDigest.getInstance("SHA-512").digest(archive(id)).joinToString("") { "%02x".format(it) }
        return JSONObject().put("id", id).put("project_id", project).put("version_number", id)
            .put("game_versions", JSONArray().put(minecraft)).put("loaders", JSONArray().put(loader))
            .put("dependencies", JSONArray(dependencies))
            .put("files", JSONArray().put(JSONObject().put("filename", "$id.jar").put("primary", true)
                .put("url", "https://cdn.modrinth.com/mod/$id.jar").put("size", archive(id).size)
                .put("hashes", JSONObject().put("sha512", hash))))
    }

    private fun dependency(project: String, version: String? = null, type: String = "required") =
        JSONObject().put("project_id", project).put("version_id", version).put("dependency_type", type)

    private fun archive(id: String) = "fixture for $id.jar".toByteArray()
}
