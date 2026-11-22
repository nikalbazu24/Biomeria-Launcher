package com.prissma.launcher.engine.bedrock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BedrockArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    private val manifest = """{"format_version":2,"header":{"name":"Example"},"modules":[{"type":"resources"}]}""".toByteArray()
    private val level = byteArrayOf(10, 0, 0, 0, 4, 0, 0, 0, 10, 0, 0, 0)

    @Test
    fun recognizesAllThreeFormatsAndVerifiesNestedContent() {
        val pack = zip("Resource.MCPACK", "manifest.json" to manifest)
        val world = zip("map.mcworld", "level.dat" to level, "db/CURRENT" to "MANIFEST-000001".toByteArray())
        val addon = zip("bundle.mcaddon", "bundled/resources.mcpack" to pack.readBytes(), "map.mcworld" to world.readBytes())

        for ((file, type) in listOf(pack to BedrockArchive.PACK, world to BedrockArchive.WORLD, addon to BedrockArchive.ADDON)) {
            assertEquals(type, BedrockArchive.fromName(file.name))
            validateBedrockArchive(file, type)
        }
        assertNull(BedrockArchive.fromName("resource.mcpack.zip"))
    }

    @Test
    fun acceptsAnAddonContainingUnpackedBehaviorAndResourcePacks() {
        val addon = zip("unpacked.mcaddon", "behavior/manifest.json" to manifest, "resources/manifest.json" to manifest)
        validateBedrockArchive(addon, BedrockArchive.ADDON)
    }

    @Test
    fun rejectsNonZipTruncatedZipAndAnEntryWithABadChecksum() {
        val renamed = folder.newFile("text.mcpack").apply { writeText("Not a ZIP") }
        assertThrows(InvalidBedrockArchive::class.java) { validateBedrockArchive(renamed, BedrockArchive.PACK) }

        val complete = zip("original.mcpack", "manifest.json" to manifest).readBytes()
        val truncated = folder.newFile("truncated.mcpack").apply { writeBytes(complete.copyOf(complete.size - 10)) }
        assertThrows(InvalidBedrockArchive::class.java) { validateBedrockArchive(truncated, BedrockArchive.PACK) }

        val central = complete.indices.first { index ->
            complete.getOrNull(index) == 0x50.toByte() && complete.getOrNull(index + 1) == 0x4b.toByte() &&
                complete.getOrNull(index + 2) == 0x01.toByte() && complete.getOrNull(index + 3) == 0x02.toByte()
        }
        val crcByte = central + 16
        complete[crcByte] = (complete[crcByte].toInt() xor 1).toByte()
        val damaged = folder.newFile("damaged.mcpack").apply { writeBytes(complete) }
        val failure = assertThrows(InvalidBedrockArchive::class.java) { validateBedrockArchive(damaged, BedrockArchive.PACK) }
        assertTrue(failure.message.orEmpty().contains("dañado"))
    }

    @Test
    fun rejectsWrongContentBrokenNestedPacksAndUnsafePaths() {
        val wrongType = zip("wrong.mcworld", "manifest.json" to manifest)
        val nested = zip("nested.mcaddon", "broken.mcpack" to "Not a ZIP".toByteArray())
        val unsafe = zip("unsafe.mcpack", "../manifest.json" to manifest)
        val empty = zip("empty.mcpack", "manifest.json" to "{}".toByteArray())
        for ((file, type) in listOf(wrongType to BedrockArchive.WORLD, nested to BedrockArchive.ADDON,
            unsafe to BedrockArchive.PACK, empty to BedrockArchive.PACK)) {
            assertThrows(InvalidBedrockArchive::class.java) { validateBedrockArchive(file, type) }
        }
    }

    @Test
    fun deliveryUsesOnlyTheSelectedPackageAndStopsWhenItsImporterOpens() {
        val attempts = mutableListOf<Pair<String, String>>()
        val result = deliverBedrockArchive(BedrockArchive.WORLD, "selected.minecraft") { id, mime ->
            attempts.add(id to mime)
            mime == "application/x-mcworld"
        }
        assertEquals(BedrockImportDelivery.SENT, result)
        assertEquals(listOf("selected.minecraft" to "application/octet-stream", "selected.minecraft" to "application/x-mcworld"), attempts)
    }

    @Test
    fun distinguishesMissingGameFromAnInstalledGameWithoutAnImporter() {
        val missing = deliverBedrockArchive(BedrockArchive.ADDON, null) { _, _ -> error("There is no target package") }
        assertEquals(BedrockImportDelivery.NO_GAME, missing)
        val attempted = mutableSetOf<String>()
        val unsupported = deliverBedrockArchive(BedrockArchive.ADDON, "selected.minecraft") { id, _ ->
            attempted.add(id)
            false
        }
        assertEquals(BedrockImportDelivery.NO_IMPORTER, unsupported)
        assertEquals(setOf("selected.minecraft"), attempted)
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val file = folder.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
