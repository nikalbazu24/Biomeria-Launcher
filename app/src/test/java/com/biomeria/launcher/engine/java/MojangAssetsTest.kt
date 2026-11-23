package com.biomeria.launcher.engine.java

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class MojangAssetsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val panorama = "panorama data"
    private val panoramaHash = sha1(panorama)
    private val indexUrl = "https://piston-meta.mojang.com/19.json"
    private val objectUrl = "https://resources.download.minecraft.net/" + panoramaHash.take(2) + "/" + panoramaHash
    private val assetsJson = JSONObject().put("objects", JSONObject()
        .put("minecraft/textures/gui/title/background/panorama_5.png", assetRecord())
        .put("minecraft/another-name.png", assetRecord())).toString()

    @Test
    fun downloadsTheExactIndexAndDeduplicatesObjectsAcrossLaunches() = runBlocking<Unit> {
        val root = temp.newFolder()
        val requested = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val assets = MojangAssets(root) { url, file ->
            requested += url
            file.writeText(if (url == indexUrl) assetsJson else panorama)
        }
        val staleIndex = File(root, "indexes/old.json")
        staleIndex.parentFile?.mkdirs()
        staleIndex.writeText("{}")

        assertEquals("19", assets.prepare(index()) { done, total -> progress += done to total })
        assertEquals(listOf(indexUrl, objectUrl), requested)
        assertEquals(listOf(0 to 1, 1 to 1), progress)
        assertEquals(panorama, panoramaFile(root).readText())

        assets.prepare(index()) { _, _ -> }
        assertEquals(listOf(indexUrl, objectUrl), requested)
        assertTrue(staleIndex.isFile)
    }

    @Test
    fun repairsACorruptCachedObjectEvenIfItsSizeMatches() = runBlocking<Unit> {
        val root = temp.newFolder()
        File(root, "indexes").mkdirs()
        File(root, "indexes/19.json").writeText(assetsJson)
        val target = panoramaFile(root)
        target.parentFile?.mkdirs()
        target.writeText("x".repeat(panorama.length))
        val requested = mutableListOf<String>()
        val assets = MojangAssets(root) { url, file ->
            requested += url
            file.writeText(panorama)
        }

        assets.prepare(index()) { _, _ -> }
        assertEquals(listOf(objectUrl), requested)
        assertEquals(panorama, target.readText())
    }

    @Test
    fun invalidDownloadsNeverBecomeCachedResources() {
        val root = temp.newFolder()
        val assets = MojangAssets(root) { url, file ->
            file.writeText(if (url == indexUrl) assetsJson else "x".repeat(panorama.length))
        }

        assertThrows(IOException::class.java) { runBlocking { assets.prepare(index()) { _, _ -> } } }
        assertTrue(!panoramaFile(root).exists())
        assertTrue(root.walk().none { it.extension == "part" })
    }

    @Test
    fun invalidHashesAreRejectedBeforeTheyBecomePaths() {
        val index = JSONObject().put("objects", JSONObject().put("unsafe",
            JSONObject().put("hash", "../unexpected").put("size", 3)))
        assertThrows(IllegalArgumentException::class.java) { indexedAssets(index) }
    }

    private fun panoramaFile(root: File) = File(root, "objects/" + panoramaHash.take(2) + "/" + panoramaHash)

    private fun assetRecord() = JSONObject().put("hash", panoramaHash).put("size", panorama.toByteArray().size)

    private fun index() = JSONObject().put("id", "19").put("url", indexUrl)
        .put("size", assetsJson.toByteArray().size).put("sha1", sha1(assetsJson))

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
