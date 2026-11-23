package com.biomeria.launcher.engine.java

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException

internal const val ACHIEVEMENT_MAGIC = 0x50414331

internal enum class AchievementKind(val banner: String, val color: Color, val sparkle: Color, val pieces: Int) {
    TASK("LOGRO DESBLOQUEADO", Color(0xFF7BC74D), Color(0xFFA6E07E), 10),
    GOAL("META ALCANZADA", Color(0xFF4FB3D9), Color(0xFF9EDCF2), 16),
    CHALLENGE("¡DESAFÍO SUPERADO!", Color(0xFFF5E663), Color(0xFFB56CF0), 28);

    companion object {
        fun fromFrame(frame: String) = when (frame) {
            "goal" -> GOAL
            "challenge" -> CHALLENGE
            else -> TASK
        }
    }
}

/** One advancement Minecraft just granted, as sent by the Biomeria mod. */
internal data class Achievement(
    val id: String,
    val kind: AchievementKind,
    val title: String,
    val description: String,
    val icon: String,
    val done: Int,
    val total: Int,
    val at: Long = System.currentTimeMillis(),
    /** Done and total per namespace: "minecraft" and one entry per mod. */
    val byMod: Map<String, Pair<Int, Int>> = emptyMap()
) {
    val namespace: String get() = id.substringBefore(':', "minecraft")
    val fromMod: Boolean get() = namespace != "minecraft"
}

/** "farmersdelight" → "Farmersdelight", "create_new_age" → "Create New Age". */
internal fun modLabel(namespace: String): String =
    namespace.split('_', '-').filter(String::isNotEmpty).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }.ifBlank { namespace }

internal fun decodeAchievement(bytes: ByteArray): Achievement? = try {
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != ACHIEVEMENT_MAGIC) return null
        val id = input.readUTF()
        val kind = AchievementKind.fromFrame(input.readUTF())
        val title = input.readUTF()
        val description = input.readUTF()
        val icon = input.readUTF()
        val done = input.readUnsignedShort()
        val total = input.readUnsignedShort()
        val byMod = LinkedHashMap<String, Pair<Int, Int>>()
        if (input.available() > 0) {
            val count = input.readUnsignedShort()
            if (count > 64) return null
            repeat(count) {
                val namespace = input.readUTF()
                val modDone = input.readUnsignedShort()
                val modTotal = input.readUnsignedShort()
                if (namespace.isBlank() || modDone > modTotal) return null
                byMod[namespace] = modDone to modTotal
            }
        }
        if (id.isBlank() || title.isBlank() || done > total) null
        else Achievement(id, kind, title, description, icon, done, total, byMod = byMod)
    }
} catch (_: IOException) {
    null
}

/** Takes the advancements waiting in the session folder, oldest first. */
internal fun DualScreenBridge.takeAchievements(): List<Achievement> {
    val files = directory.listFiles { file -> file.name.matches(Regex("achievement-\\d{13}-\\d{4}")) }
        ?.sortedBy { it.name }.orEmpty().take(8)
    return files.mapNotNull { file ->
        val bytes = runCatching { if (file.length() in 8..2048) file.readBytes() else null }.getOrNull()
        file.delete()
        bytes?.let(::decodeAchievement)
    }
}

/** Every advancement Biomeria has seen unlocked, per world, for the achievements page. */
internal object AchievementLog {
    private fun file(context: Context, instanceId: String): File {
        val safe = instanceId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "java" }
        return File(context.filesDir, "biomeria-achievements/$safe.tsv")
    }

    private fun clean(value: String) = value.replace('\t', ' ').replace('\n', ' ')

    private fun namesFile(context: Context, instanceId: String) =
        File(file(context, instanceId).path.removeSuffix(".tsv") + ".names")

    /** Saves the mod names of this world; called once per game session. */
    fun saveModNames(context: Context, instanceId: String, names: Map<String, String>) {
        runCatching {
            val target = namesFile(context, instanceId)
            target.parentFile?.mkdirs()
            target.writeText(names.entries.joinToString("\n") { "${clean(it.key)}\t${clean(it.value)}" } + "\n")
        }
    }

    fun modNames(context: Context, instanceId: String): Map<String, String> = runCatching {
        namesFile(context, instanceId).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
        }.toMap()
    }.getOrDefault(emptyMap())

    fun iconFile(context: Context, icon: String): File =
        File(context.filesDir, "biomeria-achievements/icons/" + icon.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100) + ".png")

    private fun progressFile(context: Context, instanceId: String) =
        File(file(context, instanceId).path.removeSuffix(".tsv") + ".progress")

    /** The latest counts the game reported: the total first, then one line per namespace. */
    fun progress(context: Context, instanceId: String): Pair<Pair<Int, Int>, Map<String, Pair<Int, Int>>>? = runCatching {
        val lines = progressFile(context, instanceId).takeIf { it.isFile }?.readLines() ?: return null
        val rows = lines.mapNotNull { line ->
            val parts = line.split('\t')
            val done = parts.getOrNull(1)?.toIntOrNull()
            val total = parts.getOrNull(2)?.toIntOrNull()
            if (done == null || total == null) null else parts[0] to (done to total)
        }
        val all = rows.firstOrNull { it.first == "*" }?.second ?: return null
        all to rows.filter { it.first != "*" }.toMap()
    }.getOrNull()

    private const val SEEN = "biomeria_achievements_seen"

    fun seen(context: Context, instanceId: String): Set<String> =
        context.getSharedPreferences(SEEN, Context.MODE_PRIVATE).getStringSet(instanceId, emptySet()).orEmpty()

    fun markSeen(context: Context, instanceId: String, ids: Collection<String>) {
        val prefs = context.getSharedPreferences(SEEN, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(instanceId, seen(context, instanceId) + ids).apply()
    }

    /** Runs in the game process: also saves the icon, since the app can't read the game's files. */
    fun record(context: Context, instanceId: String, achievement: Achievement) {
        runCatching {
            val target = file(context, instanceId)
            target.parentFile?.mkdirs()
            if (achievement.total > 0) {
                val rows = listOf("*\t${achievement.done}\t${achievement.total}") +
                    achievement.byMod.map { (namespace, counts) -> "${clean(namespace)}\t${counts.first}\t${counts.second}" }
                progressFile(context, instanceId).writeText(rows.joinToString("\n") + "\n")
            }
            val icon = iconFile(context, achievement.icon)
            if (achievement.icon.isNotBlank() && !icon.isFile) ItemIcons.current?.icon(achievement.icon)?.let { bitmap ->
                icon.parentFile?.mkdirs()
                icon.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            if (target.isFile && target.readLines().any { it.split('\t').getOrNull(1) == achievement.id }) return
            target.appendText(listOf(achievement.at.toString(), achievement.id, achievement.kind.name, clean(achievement.title),
                clean(achievement.description), achievement.icon, achievement.done.toString(), achievement.total.toString())
                .joinToString("\t") + "\n")
        }
    }

    fun read(context: Context, instanceId: String): List<Achievement> = runCatching {
        file(context, instanceId).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 8) return@mapNotNull null
            Achievement(parts[1], runCatching { AchievementKind.valueOf(parts[2]) }.getOrDefault(AchievementKind.TASK),
                parts[3], parts[4], parts[5], parts[6].toIntOrNull() ?: 0, parts[7].toIntOrNull() ?: 0,
                parts[0].toLongOrNull() ?: 0L)
        }
    }.getOrDefault(emptyList())
}

/** The game's icon while playing; outside the game, the copy saved when the achievement was earned. */
@androidx.compose.runtime.Composable
internal fun rememberAchievementIcon(id: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (id.isBlank()) return null
    val live = rememberItemIcon(id)
    if (live != null) return live
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(AchievementLog.iconFile(context, id).path)?.asImageBitmap()
            }.getOrNull()
        }
    }
    return saved
}

/**
 * Mod id → display name, read from each mod jar's own metadata (Fabric, Quilt or NeoForge/Forge).
 * Achievement namespaces are mod ids, so this turns "farmersdelight" into "Farmer's Delight".
 */
internal fun readModNames(gameDir: File): Map<String, String> {
    val names = LinkedHashMap<String, String>()
    val jars = File(gameDir, "mods").listFiles { file -> file.name.endsWith(".jar") }.orEmpty()
    for (jar in jars) runCatching {
        java.util.zip.ZipFile(jar).use { zip ->
            fun text(path: String) = zip.getEntry(path)?.takeIf { it.size in 1..262_144 }
                ?.let { entry -> zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) } }
            text("fabric.mod.json")?.let { json ->
                val root = org.json.JSONObject(json)
                val id = root.optString("id")
                if (id.isNotBlank()) names[id] = root.optString("name").ifBlank { id }
            }
            text("quilt.mod.json")?.let { json ->
                val loader = org.json.JSONObject(json).optJSONObject("quilt_loader")
                val id = loader?.optString("id").orEmpty()
                if (id.isNotBlank()) names[id] = loader?.optJSONObject("metadata")?.optString("name")?.ifBlank { id } ?: id
            }
            (text("META-INF/neoforge.mods.toml") ?: text("META-INF/mods.toml"))?.let { toml ->
                var id: String? = null
                for (line in toml.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("[[mods]]")) id = null
                    val value = trimmed.substringAfter('=', "").trim().trim('"', '\'')
                    if (trimmed.startsWith("modId")) id = value
                    if (trimmed.startsWith("displayName") && id != null && value.isNotBlank()) names[id!!] = value
                }
            }
        }
    }
    return names
}
