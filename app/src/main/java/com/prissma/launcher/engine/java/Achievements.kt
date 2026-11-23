package com.prissma.launcher.engine.java

import android.content.Context
import androidx.compose.ui.graphics.Color
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
    val at: Long = System.currentTimeMillis()
)

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
        if (id.isBlank() || title.isBlank() || done > total) null
        else Achievement(id, kind, title, description, icon, done, total)
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

    fun record(context: Context, instanceId: String, achievement: Achievement) {
        runCatching {
            val target = file(context, instanceId)
            target.parentFile?.mkdirs()
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
