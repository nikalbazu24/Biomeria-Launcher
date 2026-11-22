package com.prissma.launcher.engine.rust

import com.prissma.launcher.engine.imports.verifyEntries
import org.tomlj.Toml
import org.tomlj.TomlInvalidTypeException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

internal const val STARLING_MOD_API = "0.2"
private const val MANIFEST = "horizon-mod.toml"
private const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
private const val MAX_WASM_BYTES = 64L * 1024 * 1024
private const val MAX_EXPANDED_BYTES = 673L * 1024 * 1024
private val modId = Regex("[a-z0-9_]{1,64}")
private val sourcePin = Regex("[a-f0-9]{40}")
private val wasmHash = Regex("[a-f0-9]{128}")
private val alternateFormats = setOf("meta.json", "horizon-resources.json")

internal enum class StarlingModType(val label: String) {
    CONTENT("Contenido"), NATIVE("Nativo"), WASM("WASM")
}

internal data class StarlingMod(val id: String, val name: String, val version: String, val type: StarlingModType)
private data class ModManifest(val mod: StarlingMod, val wasmSha512: String?)

internal class InvalidStarlingMod(message: String) : IOException(message)

internal fun starlingModExtension(name: String): String = when {
    name.endsWith(".hmod", ignoreCase = true) -> "hmod"
    name.endsWith(".hnmod", ignoreCase = true) -> throw InvalidStarlingMod("Selecciona una versión .hmod del mod para Starling.")
    else -> throw InvalidStarlingMod("Elige un archivo .hmod de Starling o Horizon.")
}

internal fun validateStarlingMod(file: File, checkCancelled: () -> Unit = {}): StarlingMod {
    starlingModExtension(file.name)
    return try {
        ZipFile(file).use { zip ->
            if (zip.getEntry(MANIFEST)?.isDirectory != false) {
                throw InvalidStarlingMod("Falta $MANIFEST en la raíz del .hmod.")
            }
            val fileNames = mutableSetOf<String>()
            var totalSize = 0L
            var manifest: ModManifest? = null
            var payloadSha512: String? = null
            zip.verifyEntries(checkCancelled) { entry, name, input ->
                if (!fileNames.add(name)) throw InvalidStarlingMod("El .hmod contiene rutas de archivo repetidas.")
                if (entry.size < 0 || entry.size > MAX_EXPANDED_BYTES - totalSize) {
                    throw InvalidStarlingMod("El .hmod supera el tamaño de contenido admitido.")
                }
                totalSize += entry.size
                val alternateFormat = name.substringAfterLast('/') in alternateFormats && name.count { it == '/' } <= 1
                if (name == "horizon-native.json" || alternateFormat) {
                    throw InvalidStarlingMod("El archivo mezcla un mod con otro formato de importación de Starling.")
                }
                when {
                    entry.name == MANIFEST -> manifest = readManifest(input, checkCancelled)
                    entry.name == "mod.wasm" -> {
                        if (entry.size > MAX_WASM_BYTES) throw InvalidStarlingMod("El archivo mod.wasm supera 64 MiB.")
                        payloadSha512 = digestWasm(input, checkCancelled)
                    }
                    name.substringAfterLast('/') == "mod.wasm" -> throw InvalidStarlingMod("mod.wasm debe estar en la raíz del .hmod.")
                    else -> Unit
                }
            }
            val valid = manifest ?: throw InvalidStarlingMod("Falta $MANIFEST en la raíz del .hmod.")
            if (valid.mod.type == StarlingModType.NATIVE) {
                val hasSource = fileNames.any { it.startsWith("src/") && it.endsWith(".rs") }
                if ("Cargo.toml" !in fileNames || !hasSource) {
                    throw InvalidStarlingMod("El mod nativo debe incluir Cargo.toml y código Rust en src/.")
                }
            }
            if (valid.wasmSha512 != payloadSha512) {
                throw InvalidStarlingMod("El manifiesto y la huella SHA-512 de mod.wasm no coinciden.")
            }
            valid.mod
        }
    } catch (error: InvalidStarlingMod) {
        throw error
    } catch (error: IOException) {
        throw InvalidStarlingMod("El .hmod está dañado o contiene rutas inválidas.")
    }
}

private fun digestWasm(input: InputStream, checkCancelled: () -> Unit): String {
    val header = ByteArray(4) { input.read().toByte() }
    if (!header.contentEquals(byteArrayOf(0, 'a'.code.toByte(), 's'.code.toByte(), 'm'.code.toByte()))) {
        throw InvalidStarlingMod("El archivo mod.wasm no tiene una cabecera WebAssembly válida.")
    }
    val digest = MessageDigest.getInstance("SHA-512")
    digest.update(header)
    val buffer = ByteArray(64 * 1024)
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun readManifest(input: InputStream, checkCancelled: () -> Unit): ModManifest {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        if (bytes.size() + count > MAX_MANIFEST_BYTES) throw InvalidStarlingMod("El manifiesto del .hmod supera 32 MiB.")
        bytes.write(buffer, 0, count)
    }
    val manifest = Toml.parse(bytes.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF"))
    if (manifest.hasErrors()) throw InvalidStarlingMod("El manifiesto $MANIFEST contiene errores de formato.")
    try {
        val id = manifest.getString("id")?.takeIf(modId::matches)
        val name = manifest.getString("name")?.takeIf { it.isNotBlank() }
        val version = manifest.getString("version")?.takeIf { it.isNotBlank() }
        val api = manifest.getString("api")
        if (id == null || name == null || version == null || api.isNullOrBlank() || manifest.getArray("capabilities") == null) {
            throw InvalidStarlingMod("El manifiesto del .hmod no incluye la identificación y la API del mod.")
        }
        if (api != STARLING_MOD_API) throw InvalidStarlingMod("El mod usa la API $api. Este Taller verifica la API $STARLING_MOD_API.")
        val native = manifest.getTable("native")
        val wasm = manifest.getTable("wasm")
        val expectedHash = wasm?.getString("sha512")?.takeIf(wasmHash::matches)
        if (native != null && wasm != null) throw InvalidStarlingMod("El manifiesto declara dos tipos de código para el mismo mod.")
        val type = when {
            native != null -> {
                if (native.getString("game") != STARLING_MINECRAFT_VERSION) {
                    throw InvalidStarlingMod("Este mod nativo requiere otra versión de Minecraft. Starling usa $STARLING_MINECRAFT_VERSION.")
                }
                if (native.getString("horizon")?.matches(sourcePin) != true || native.getString("mixins")?.matches(sourcePin) != true) {
                    throw InvalidStarlingMod("El mod nativo debe indicar las revisiones de Horizon y de mixins.")
                }
                StarlingModType.NATIVE
            }
            wasm != null -> {
                if (expectedHash == null || wasm.getString("toolchain").isNullOrBlank()) {
                    throw InvalidStarlingMod("El manifiesto WASM debe incluir el toolchain y la huella SHA-512 de mod.wasm.")
                }
                StarlingModType.WASM
            }
            else -> StarlingModType.CONTENT
        }
        return ModManifest(StarlingMod(id, name, version, type), expectedHash)
    } catch (error: TomlInvalidTypeException) {
        throw InvalidStarlingMod("El manifiesto $MANIFEST usa tipos de datos inválidos.")
    }
}
