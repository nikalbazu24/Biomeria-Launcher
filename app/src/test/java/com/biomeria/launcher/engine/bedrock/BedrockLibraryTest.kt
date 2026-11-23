package com.biomeria.launcher.engine.bedrock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BedrockLibraryTest {
    @get:Rule val folder = TemporaryFolder()

    private val files get() = File(folder.root, "files/bedrock-content")
    private val cache get() = File(folder.root, "cache/bedrock-staging")

    @Test
    fun persistsVerifiedContentAndKeepsInstancesSeparate() {
        val content = pack("Primer paquete")
        val saved = library("../instancia").importArchive("Páquete.MCPACK", content.inputStream())
        cache.deleteRecursively()

        assertTrue(saved.added)
        val reopened = library("../instancia").list().single()
        assertEquals("Páquete.mcpack", reopened.name)
        assertEquals(BedrockArchive.PACK, reopened.type)
        assertEquals(content.size.toLong(), reopened.size)
        assertArrayEquals(content, reopened.file.readBytes())
        assertTrue(reopened.file.canonicalPath.startsWith(files.canonicalPath + File.separator))
        assertTrue(library("another").list().isEmpty())
        assertNull(library("another").item(saved.item.id))
        assertNull(library("../instancia").item("../${saved.item.id}"))
    }

    @Test
    fun deduplicatesByContentAndKeepsDistinctPackagesWithTheSameName() {
        val library = library()
        val data = pack("A")
        val first = library.importArchive("resources.mcpack", data.inputStream())
        val duplicate = library.importArchive("renamed.mcpack", data.inputStream())
        val second = library.importArchive("resources.mcpack", pack("B").inputStream())

        assertFalse(duplicate.added)
        assertEquals(first.item, duplicate.item)
        assertTrue(second.added)
        assertEquals(2, library.list().size)
        assertEquals(library.list().first().name, library.list().last().name)
    }

    @Test
    fun cancelledAndDamagedImportsDoNotLeaveVisibleOrStagedCopies() {
        val library = library()
        assertThrows(InvalidBedrockArchive::class.java) {
            library.importArchive("broken.mcworld", byteArrayOf(10, 0, 0).inputStream())
        }
        var verifying = false
        assertThrows(CancellationException::class.java) {
            library.importArchive("canceled.mcpack", pack("A").inputStream(),
                checkCancelled = { if (verifying) throw CancellationException() }, onVerify = { verifying = true })
        }
        val brokenSource = object : java.io.InputStream() {
            override fun read(): Int = throw IOException("Source disconnected")
        }
        assertThrows(IOException::class.java) { library.importArchive("broken.mcpack", brokenSource) }
        assertTrue(library.list().isEmpty())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun copiesOriginalBytesAndRemovesOnlyTheRequestedInstanceCopy() {
        val content = pack("A")
        val first = library("first").importArchive("copy.mcpack", content.inputStream()).item
        val second = library("second").importArchive("copy.mcpack", content.inputStream()).item
        val exported = ByteArrayOutputStream()
        first.file.inputStream().use { input -> input.copyArchiveTo(exported) {} }

        assertArrayEquals(content, exported.toByteArray())
        assertTrue(library("first").remove(first.id))
        assertFalse(library("first").remove(first.id))
        assertTrue(library("first").list().isEmpty())
        assertEquals(second, library("second").list().single())
        assertTrue(library("first").importArchive("copy.mcpack", content.inputStream()).added)
    }

    @Test
    fun concurrentImportsPublishOneCompleteCopy() {
        val payload = pack("Concurrent")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(2) { index ->
                Callable { library().importArchive("pack-$index.mcpack", payload.inputStream()) }
            }, 5, TimeUnit.SECONDS).map { it.get() }
            assertEquals(1, results.count { it.added })
            assertEquals(results[0].item, results[1].item)
            assertArrayEquals(payload, library().list().single().file.readBytes())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun library(id: String = "bedrock") = BedrockLibrary(files, cache, id)

    private fun pack(name: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0L })
            zip.write("""{"header":{"name":"$name"},"modules":[{"type":"resources"}]}""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }
}
