package com.biomeria.launcher.engine.java

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs

/** A mod in the world's mods folder, with its picture and every setting file of its that Biomeria can read. */
internal class InstalledMod(
    val id: String,
    val name: String,
    val version: String?,
    val description: String?,
    val icon: ImageBitmap?,
    val color: Color,
    val configs: List<ModConfigFile>
) {
    val settingCount: Int get() = configs.sumOf { it.settings.size }
}

private val modColors = listOf(Kit.grass, Kit.sky, Kit.lilac, Kit.gold, Kit.ember, Kit.wood)

private fun squash(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

private class ModFacts(val id: String, val name: String, val version: String?, val description: String?, val iconPath: String?)

/** Reads fabric.mod.json or quilt.mod.json from a mod jar. */
private fun readFacts(zip: ZipFile): ModFacts? {
    zip.getEntry("fabric.mod.json")?.let { entry ->
        val json = JSONObject(zip.getInputStream(entry).bufferedReader().readText())
        val icon = when (val value = json.opt("icon")) {
            is String -> value
            is JSONObject -> value.keys().asSequence().maxByOrNull { it.toIntOrNull() ?: 0 }?.let { value.optString(it) }
            else -> null
        }
        return ModFacts(json.optString("id"), json.optString("name").ifBlank { json.optString("id") },
            json.optString("version").takeIf { it.isNotBlank() && !it.startsWith("$") },
            json.optString("description").takeIf { it.isNotBlank() }, icon)
    }
    zip.getEntry("quilt.mod.json")?.let { entry ->
        val loader = JSONObject(zip.getInputStream(entry).bufferedReader().readText()).optJSONObject("quilt_loader") ?: return null
        val meta = loader.optJSONObject("metadata")
        return ModFacts(loader.optString("id"), meta?.optString("name")?.takeIf { it.isNotBlank() } ?: loader.optString("id"),
            loader.optString("version").takeIf { it.isNotBlank() && !it.startsWith("$") },
            meta?.optString("description")?.takeIf { it.isNotBlank() }, meta?.optString("icon")?.takeIf { it.isNotBlank() })
    }
    return null
}

private fun readIcon(zip: ZipFile, path: String?): ImageBitmap? {
    val entry = path?.let { zip.getEntry(it.removePrefix("/")) } ?: return null
    if (entry.size > 2_000_000) return null
    val bytes = zip.getInputStream(entry).use { it.readBytes() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 96) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** True when [config] most likely belongs to the mod with this id or name. */
private fun belongs(config: ModConfigFile, id: String, name: String): Boolean {
    val file = squash(config.file.nameWithoutExtension)
    val folder = squash(config.file.parentFile?.name ?: "")
    val keys = listOf(squash(id), squash(name)).filter { it.length >= 3 }
    return keys.any { key -> file.startsWith(key) || folder == key || (file.length >= 4 && key.startsWith(file)) }
}

/** Every mod in the mods folder, sorted by name, plus the setting files no mod claimed. */
internal fun readInstalledMods(gameDirectory: File): List<InstalledMod> {
    val configs = runCatching { readModConfigs(gameDirectory) }.getOrDefault(emptyList()).toMutableList()
    val jars = File(gameDirectory, "mods").listFiles().orEmpty().filter { it.isFile && it.extension.equals("jar", true) }
    val mods = jars.mapNotNull { jar ->
        runCatching {
            ZipFile(jar).use { zip ->
                val facts = readFacts(zip) ?: return@use null
                if (facts.id.startsWith("biomeria") || facts.id == "fabricloader" || facts.id == "java") return@use null
                val mine = configs.filter { belongs(it, facts.id, facts.name) }
                configs.removeAll(mine)
                InstalledMod(facts.id, facts.name, facts.version, facts.description, runCatching { readIcon(zip, facts.iconPath) }.getOrNull(),
                    modColors[abs(facts.id.hashCode()) % modColors.size], mine)
            }
        }.getOrNull()
    }
    // Setting files whose mod was not found keep their own entry, so nothing that could be changed disappears.
    val loose = configs.groupBy { it.modName }.map { (name, files) ->
        InstalledMod(squash(name), name, null, null, null, modColors[abs(name.hashCode()) % modColors.size], files)
    }
    return (mods + loose).sortedBy { it.name.lowercase() }
}
