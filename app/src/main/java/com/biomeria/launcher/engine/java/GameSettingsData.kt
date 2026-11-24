package com.biomeria.launcher.engine.java

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Minecraft's options.txt

internal sealed class GameOption(val key: String, val label: String) {
    class Toggle(key: String, label: String) : GameOption(key, label)
    /** A number stored as-is (or scaled), shown with [format]. */
    class Number(
        key: String, label: String, val min: Double, val max: Double, val step: Double,
        val format: (Double) -> String, val decimals: Boolean = false
    ) : GameOption(key, label)
    class Choice(key: String, label: String, val values: List<Pair<String, String>>) : GameOption(key, label)
}

internal enum class OptionGroup2(val label: String, val color: Long, val glyph: Glyph, val detail: String) {
    VIDEO("Imagen", 0xFF7BC74D, Glyph.EYE, "Campo de visión, shaders y gráficos"),
    SOUND("Sonido", 0xFF4FB3D9, Glyph.NOTE, "Volumen de cada cosa"),
    CONTROLS("Controles", 0xFFF5E663, Glyph.PAD, "Cámara y movimiento"),
    CHAT("Chat", 0xFFE0B48C, Glyph.CHAT, "Cómo se ve el chat"),
    ACCESS("Accesibilidad", 0xFFB48CE0, Glyph.HAND, "Efectos y contraste"),
    MODS("Mods", 0xFFF07D4A, Glyph.PUZZLE, "Opciones de tus mods")
}

private fun percent(value: Double) = "${(value * 100).roundToInt()} %"

internal val gameOptionGroups: Map<OptionGroup2, List<GameOption>> = linkedMapOf(
    OptionGroup2.VIDEO to listOf(
        GameOption.Number("renderDistance", "Distancia de renderizado", 2.0, 32.0, 1.0, { "${it.toInt()} chunks" }),
        GameOption.Number("simulationDistance", "Distancia de simulación", 5.0, 32.0, 1.0, { "${it.toInt()} chunks" }),
        GameOption.Choice("graphicsMode", "Gráficos", listOf("0" to "Rápidos", "1" to "Detallados", "2" to "Fabulosos")),
        GameOption.Number("maxFps", "FPS máximos", 10.0, 260.0, 10.0, { if (it >= 260) "Sin límite" else "${it.toInt()}" }),
        GameOption.Toggle("enableVsync", "Sincronización vertical"),
        GameOption.Number("gamma", "Brillo", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("guiScale", "Tamaño de la interfaz", 0.0, 6.0, 1.0, { if (it == 0.0) "Automático" else "${it.toInt()}" }),
        GameOption.Choice("renderClouds", "Nubes", listOf("\"false\"" to "No", "\"fast\"" to "Rápidas", "\"true\"" to "Detalladas")),
        GameOption.Choice("particles", "Partículas", listOf("0" to "Todas", "1" to "Menos", "2" to "Mínimas")),
        GameOption.Toggle("entityShadows", "Sombras de criaturas"),
        GameOption.Number("biomeBlendRadius", "Mezcla de biomas", 0.0, 7.0, 1.0, { if (it == 0.0) "No" else "${(it * 2 + 1).toInt()}×${(it * 2 + 1).toInt()}" }),
        GameOption.Number("mipmapLevels", "Niveles de mipmap", 0.0, 4.0, 1.0, { if (it == 0.0) "No" else "${it.toInt()}" }),
        GameOption.Number("entityDistanceScaling", "Distancia de criaturas", .5, 5.0, .25, ::percent, true),
        GameOption.Toggle("bobView", "Balanceo al caminar")
    ),
    OptionGroup2.SOUND to listOf(
        GameOption.Number("soundCategory_master", "Volumen general", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("soundCategory_music", "Música", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("soundCategory_record", "Tocadiscos y bloques musicales", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("soundCategory_weather", "Clima", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("soundCategory_block", "Bloques", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("soundCategory_hostile", "Criaturas hostiles", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("soundCategory_neutral", "Criaturas amistosas", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("soundCategory_player", "Jugadores", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("soundCategory_ambient", "Ambiente", 0.0, 1.0, .05, ::percent, true),
        GameOption.Toggle("showSubtitles", "Subtítulos")
    ),
    OptionGroup2.CONTROLS to listOf(
        GameOption.Number("mouseSensitivity", "Sensibilidad de la cámara", 0.0, 1.0, .05, { "${(it * 200).roundToInt()} %" }, true),
        GameOption.Toggle("invertYMouse", "Invertir cámara vertical"),
        GameOption.Toggle("toggleCrouch", "Agacharse: alternar (no mantener)"),
        GameOption.Toggle("toggleSprint", "Correr: alternar (no mantener)"),
        GameOption.Toggle("autoJump", "Salto automático")
    ),
    OptionGroup2.CHAT to listOf(
        GameOption.Choice("chatVisibility", "Chat", listOf("0" to "Visible", "1" to "Solo comandos", "2" to "Oculto")),
        GameOption.Number("chatOpacity", "Opacidad del texto", .1, 1.0, .05, ::percent, true),
        GameOption.Number("textBackgroundOpacity", "Fondo del texto", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("chatScale", "Tamaño del chat", 0.0, 1.0, .05, ::percent, true),
        GameOption.Toggle("chatColors", "Colores en el chat"),
        GameOption.Toggle("chatLinks", "Enlaces en el chat")
    ),
    OptionGroup2.ACCESS to listOf(
        GameOption.Number("screenEffectScale", "Efectos de distorsión", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("fovEffectScale", "Efectos de campo de visión", 0.0, 1.0, .05, ::percent, true),
        GameOption.Number("darknessEffectScale", "Pulso de oscuridad", 0.0, 1.0, .05, ::percent, true),
        GameOption.Toggle("hideLightningFlashes", "Ocultar destellos de rayos"),
        GameOption.Toggle("highContrast", "Alto contraste"),
        GameOption.Toggle("reducedDebugInfo", "Información de depuración reducida")
    ),
    OptionGroup2.MODS to emptyList()
)

internal class GameOptionsFile(gameDirectory: File) {
    private val file = AtomicFile(File(gameDirectory, "options.txt"))

    fun read(): Map<String, String> = runCatching {
        file.openRead().bufferedReader().use { reader ->
            reader.readLines().filter { ':' in it }.associate { it.substringBefore(':') to it.substringAfter(':') }
        }
    }.getOrDefault(emptyMap())

    /** Writes the changed keys, keeping every other line exactly as Minecraft wrote it. */
    fun write(changes: Map<String, String>) {
        val previous = runCatching { file.openRead().bufferedReader().use { it.readLines() } }.getOrDefault(emptyList())
        val seen = mutableSetOf<String>()
        val lines = previous.map { line ->
            val key = line.substringBefore(':')
            changes[key]?.let { seen += key; "$key:$it" } ?: line
        } + changes.filterKeys { it !in seen }.map { (key, value) -> "$key:$value" }
        val output = file.startWrite()
        try {
            output.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}

internal fun formatOptionNumber(value: Double, decimals: Boolean): String =
    if (decimals) String.format(Locale.ROOT, "%.4f", value).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
    else value.roundToInt().toString()

// ---------------------------------------------------------------- Mod configuration files

internal enum class ModValueType { BOOLEAN, NUMBER, TEXT }

/** One setting from a mod's file, with the author's note above it, its range and its allowed values when the file says them. */
internal data class ModSetting(
    val path: String, val label: String, val type: ModValueType, val value: String,
    val comment: String? = null, val min: Double? = null, val max: Double? = null, val choices: List<String>? = null
)

private val rangeNote = Regex("""(?i)range:?\s*\[?\s*(-?[\d.]+)\s*(?:~|to|,|\.\.)\s*(-?[\d.]+)""")
private val allowedNote = Regex("""(?i)allowed values:?\s*(.+)""")

/** Builds a setting, reading the range and the allowed values out of the comment above it. */
private fun noted(path: String, label: String, type: ModValueType, value: String, comment: List<String>): ModSetting {
    val text = comment.map { it.trim() }.filter { it.isNotEmpty() }
    val range = text.firstNotNullOfOrNull { rangeNote.find(it) }
    val allowed = text.firstNotNullOfOrNull { allowedNote.find(it) }?.groupValues?.get(1)
        ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }?.takeIf { it.size in 2..12 }
    val note = text.filterNot { rangeNote.containsMatchIn(it) || allowedNote.containsMatchIn(it) }
        .joinToString(" ").takeIf { it.isNotBlank() }
    return ModSetting(path, label, type, value, note,
        range?.groupValues?.get(1)?.toDoubleOrNull(), range?.groupValues?.get(2)?.toDoubleOrNull(), allowed)
}

internal data class ModConfigFile(val file: File, val modName: String, val settings: List<ModSetting>)

private val knownMods = mapOf(
    "sodium" to "Sodium", "lithium" to "Lithium", "iris" to "Iris", "modmenu" to "Mod Menu", "entityculling" to "Entity Culling",
    "ferritecore" to "FerriteCore", "immediatelyfast" to "ImmediatelyFast", "moreculling" to "More Culling", "c2me" to "C2ME",
    "distanthorizons" to "Distant Horizons", "xaero" to "Xaero", "jei" to "JEI", "rei" to "REI", "emi" to "EMI",
    "appleskin" to "AppleSkin", "krypton" to "Krypton", "dynamic_fps" to "Dynamic FPS", "dynamicfps" to "Dynamic FPS",
    "continuity" to "Continuity", "indium" to "Indium", "zoomify" to "Zoomify", "fabric" to "Fabric", "voxy" to "Voxy",
    "nvidium" to "Nvidium", "betterf3" to "BetterF3", "lambdynlights" to "LambDynamicLights", "puzzle" to "Puzzle",
    "sodium-extra" to "Sodium Extra", "sodium_extra" to "Sodium Extra", "reeses" to "Reese's Sodium Options"
)

private fun modNameOf(file: File): String {
    val base = file.nameWithoutExtension.lowercase()
    knownMods.entries.firstOrNull { base == it.key || base.startsWith(it.key + "-") || base.startsWith(it.key + "_") }?.let { return it.value }
    knownMods.entries.firstOrNull { base.startsWith(it.key) }?.let { return it.value }
    return base.substringBefore('-').substringBefore('_').replaceFirstChar { it.uppercase() }
}

private fun prettyKey(path: String): String {
    val last = path.substringAfterLast('.')
    val words = last.replace('_', ' ').replace('-', ' ')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().trim()
    return words.replaceFirstChar { it.uppercase() }
}

private fun typeOf(raw: String): ModValueType = when {
    raw == "true" || raw == "false" -> ModValueType.BOOLEAN
    raw.toDoubleOrNull() != null -> ModValueType.NUMBER
    else -> ModValueType.TEXT
}

/** Reads every mod config Biomeria understands: .properties, simple .json and .toml. */
internal fun readModConfigs(gameDirectory: File): List<ModConfigFile> {
    val config = File(gameDirectory, "config")
    val files = config.walkTopDown().maxDepth(2)
        .filter { it.isFile && it.length() in 1..262_144 && it.extension.lowercase() in setOf("json", "properties", "toml") }
        .toList()
    return files.mapNotNull { file ->
        val settings = runCatching {
            when (file.extension.lowercase()) {
                "properties" -> readProperties(file)
                "json" -> readJson(file)
                else -> readToml(file)
            }
        }.getOrDefault(emptyList()).filter { it.type != ModValueType.TEXT || it.value.length <= 64 }
        if (settings.isEmpty()) null else ModConfigFile(file, modNameOf(file), settings.take(120))
    }.sortedBy { it.modName.lowercase() }
}

private fun readProperties(file: File): List<ModSetting> {
    val comment = mutableListOf<String>()
    return file.readLines().mapNotNull { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith('#') || trimmed.startsWith('!') -> { comment += trimmed.trimStart('#', '!', ' '); null }
            trimmed.isEmpty() || '=' !in trimmed -> { comment.clear(); null }
            else -> {
                val key = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=').trim()
                noted(key, prettyKey(key), typeOf(value), value, comment.toList()).also { comment.clear() }
            }
        }
    }
}

private fun readJson(file: File): List<ModSetting> {
    val root = JSONObject(file.readText())
    val out = mutableListOf<ModSetting>()
    fun walk(node: JSONObject, prefix: String, depth: Int) {
        for (key in node.keys()) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when (val value = node.get(key)) {
                is JSONObject -> if (depth < 3) walk(value, path, depth + 1)
                is JSONArray -> Unit
                is Boolean -> out += ModSetting(path, prettyKey(path), ModValueType.BOOLEAN, value.toString())
                is Number -> out += ModSetting(path, prettyKey(path), ModValueType.NUMBER, value.toString())
                is String -> out += ModSetting(path, prettyKey(path), ModValueType.TEXT, value)
            }
        }
    }
    walk(root, "", 0)
    return out
}

private fun readToml(file: File): List<ModSetting> {
    var section = ""
    val comment = mutableListOf<String>()
    return file.readLines().mapNotNull { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith("[") -> { section = trimmed.trim('[', ']', ' '); comment.clear(); null }
            trimmed.startsWith('#') -> { comment += trimmed.trimStart('#', ' '); null }
            trimmed.isEmpty() || '=' !in trimmed -> { comment.clear(); null }
            else -> {
                val key = trimmed.substringBefore('=').trim()
                val raw = trimmed.substringAfter('=').substringBefore(" #").trim()
                val note = comment.toList().also { comment.clear() }
                if (raw.startsWith("[") || raw.startsWith("{")) return@mapNotNull null
                val value = raw.trim('"')
                val path = if (section.isEmpty()) key else "$section.$key"
                noted(path, prettyKey(key), if (raw.startsWith('"')) ModValueType.TEXT else typeOf(value), value, note)
            }
        }
    }
}

/** Saves changed settings back into the same file, keeping its format and everything else in it. */
internal fun saveModConfig(config: ModConfigFile, changes: Map<String, String>) {
    if (changes.isEmpty()) return
    val types = config.settings.associate { it.path to it.type }
    when (config.file.extension.lowercase()) {
        "properties" -> config.file.writeText(config.file.readLines().joinToString("\n", postfix = "\n") { line ->
            val key = line.substringBefore('=').trim()
            if (!line.trim().startsWith('#') && '=' in line && key in changes) "$key=${changes.getValue(key)}" else line
        })
        "json" -> {
            val root = JSONObject(config.file.readText())
            changes.forEach { (path, value) ->
                val parts = path.split('.')
                var node = root
                parts.dropLast(1).forEach { node = node.optJSONObject(it) ?: return@forEach }
                val key = parts.last()
                node.put(key, when (types[path]) {
                    ModValueType.BOOLEAN -> value == "true"
                    ModValueType.NUMBER -> value.toLongOrNull() ?: value.toDouble()
                    else -> value
                })
            }
            config.file.writeText(root.toString(2))
        }
        else -> {
            var section = ""
            config.file.writeText(config.file.readLines().joinToString("\n", postfix = "\n") { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("[")) { section = trimmed.trim('[', ']', ' '); return@joinToString line }
                if (trimmed.startsWith('#') || '=' !in trimmed) return@joinToString line
                val key = trimmed.substringBefore('=').trim()
                val path = if (section.isEmpty()) key else "$section.$key"
                val value = changes[path] ?: return@joinToString line
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                indent + key + " = " + if (types[path] == ModValueType.TEXT) "\"$value\"" else value
            })
        }
    }
}
