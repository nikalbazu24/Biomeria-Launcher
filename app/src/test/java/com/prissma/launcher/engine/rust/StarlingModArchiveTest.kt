package com.prissma.launcher.engine.rust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StarlingModArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    private val metadata = """
        id = "example_pack"
        name = "Ejemplo de contenido"
        version = '1.4.0'
        api = "0.2"
        capabilities = ["registries"]  # trailing TOML comment
    """.trimIndent()

    @Test
    fun readsContentMetadataFromARealZipWithToml() {
        val archive = zip("Example.HMOD", "horizon-mod.toml" to metadata, "assets/example_pack/info.txt" to "Example")
        assertEquals("hmod", starlingModExtension(archive.name))
        assertEquals(StarlingMod("example_pack", "Ejemplo de contenido", "1.4.0", StarlingModType.CONTENT), validateStarlingMod(archive))
    }

    @Test
    fun recognizesNativeAndWasmLayouts() {
        val native = "$metadata\n[native]\ngame = \"26.3\"\nhorizon = \"${"a".repeat(40)}\"\nmixins = \"${"b".repeat(40)}\"\n"
        val payload = "\u0000asm\u0001\u0000\u0000\u0000"
        val sha512 = MessageDigest.getInstance("SHA-512").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        val wasm = "$metadata\n[wasm]\ntoolchain = \"example\"\nsha512 = \"$sha512\"\n"
        val nativeMod = zip("native.hmod", "horizon-mod.toml" to native, "Cargo.toml" to "[package]", "src/lib.rs" to "// example")
        val wasmMod = zip("wasm.hmod", "mod.wasm" to payload, "horizon-mod.toml" to wasm)

        assertEquals(StarlingModType.NATIVE, validateStarlingMod(nativeMod).type)
        assertEquals(StarlingModType.WASM, validateStarlingMod(wasmMod).type)
        assertThrows(InvalidStarlingMod::class.java) { validateStarlingMod(zip("missing-src.hmod", "horizon-mod.toml" to native)) }
        assertThrows(InvalidStarlingMod::class.java) { validateStarlingMod(zip("missing-wasm.hmod", "horizon-mod.toml" to wasm)) }
        assertThrows(InvalidStarlingMod::class.java) {
            validateStarlingMod(zip("altered-wasm.hmod", "horizon-mod.toml" to wasm, "mod.wasm" to "$payload\u0000"))
        }
    }

    @Test
    fun rejectsIncompatibleApiOrNativeGameVersion() {
        val otherApi = zip("older.hmod", "horizon-mod.toml" to metadata.replace("0.2", "0.1"))
        val otherGame = zip("older-native.hmod", "horizon-mod.toml" to "$metadata\n[native]\ngame = \"26.2\"\n",
            "Cargo.toml" to "[package]")
        assertTrue(assertThrows(InvalidStarlingMod::class.java) { validateStarlingMod(otherApi) }.message.orEmpty().contains("API 0.1"))
        assertTrue(assertThrows(InvalidStarlingMod::class.java) { validateStarlingMod(otherGame) }.message.orEmpty().contains("26.3"))
    }

    @Test
    fun rejectsMisleadingExtensionMissingManifestAndOtherStarlingImportKinds() {
        assertThrows(InvalidStarlingMod::class.java) { starlingModExtension("legacy.hnmod") }
        assertThrows(InvalidStarlingMod::class.java) { starlingModExtension("fabric.jar") }
        for (archive in listOf(
            zip("generic.hmod", "pack.mcmeta" to "{}"),
            zip("wrong.hmod", "horizon-mod.toml" to "api = ["),
            zip("resources.hmod", "horizon-mod.toml" to metadata, "horizon-resources.json" to "{}"),
            zip("drivers.hmod", "horizon-mod.toml" to metadata, "driver/meta.json" to "{}")
        )) assertThrows(InvalidStarlingMod::class.java) { validateStarlingMod(archive) }
    }

    @Test
    fun rejectsTruncationAndUnsafePathsBeforeHandoff() {
        val valid = zip("complete.hmod", "horizon-mod.toml" to metadata).readBytes()
        val corrupt = folder.newFile("truncated.hmod").apply { writeBytes(valid.copyOf(valid.size - 10)) }
        val invalidPath = zip("invalid.hmod", "horizon-mod.toml" to metadata, "../escape" to "invalid")
        assertThrows(InvalidStarlingMod::class.java) { validateStarlingMod(corrupt) }
        assertThrows(InvalidStarlingMod::class.java) { validateStarlingMod(invalidPath) }
    }

    private fun zip(label: String, vararg entries: Pair<String, String>): File = folder.newFile(label).apply {
        ZipOutputStream(outputStream()).use { output ->
            for ((name, text) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(text.toByteArray(Charsets.UTF_8))
                output.closeEntry()
            }
        }
    }
}
