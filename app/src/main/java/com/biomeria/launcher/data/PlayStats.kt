package com.biomeria.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/** One stretch of play in one world. */
data class PlaySession(val instanceId: String, val startMs: Long, val seconds: Long)

/** Totals for a period (7 days, 30 days or everything). */
data class PlaySummary(
    val seconds: Long,
    val sessions: Int,
    val perDay: List<Pair<Long, Long>>,      // day start (ms) to seconds, oldest first
    val perWorld: Map<String, Long>,
    val bestDayStartMs: Long?,
    val bestDaySeconds: Long
)

/**
 * Play time per world. The Java game runs in its own process, so everything goes through plain
 * files in filesDir/biomeria-stats: sessions.tsv holds finished sessions and active.tsv the one
 * being played (rewritten every few seconds, so a crash loses almost nothing).
 */
object PlayStats {
    private const val ORPHAN_AFTER_MS = 60_000L

    private fun dir(context: Context) = File(context.filesDir, "biomeria-stats").apply { mkdirs() }
    private fun sessionsFile(context: Context) = File(dir(context), "sessions.tsv")
    private fun activeFile(context: Context) = File(dir(context), "active.tsv")

    private fun clean(value: String) = value.replace('\t', ' ').replace('\n', ' ')

    @Synchronized
    fun begin(context: Context, instanceId: String, now: Long = System.currentTimeMillis()) {
        runCatching { activeFile(context).writeText("${clean(instanceId)}\t$now\t0\t$now\n") }
    }

    /** Saves the seconds played so far in the current session. */
    @Synchronized
    fun beat(context: Context, instanceId: String, startMs: Long, seconds: Long, now: Long = System.currentTimeMillis()) {
        runCatching { activeFile(context).writeText("${clean(instanceId)}\t$startMs\t$seconds\t$now\n") }
    }

    /** Moves the current session to the finished ones. Sessions under 10 seconds are dropped. */
    @Synchronized
    fun end(context: Context) {
        runCatching {
            val active = activeFile(context)
            val parts = active.takeIf { it.isFile }?.readText()?.trim()?.split('\t') ?: return
            val seconds = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            if (parts.size >= 3 && seconds >= 10) {
                sessionsFile(context).appendText("${parts[0]}\t${parts[1]}\t$seconds\n")
            }
            active.delete()
        }
        export(context)
    }

    /** Called when the app opens: a session whose game died without saying goodbye still counts. */
    fun recoverOrphan(context: Context, now: Long = System.currentTimeMillis()) {
        val last = runCatching {
            activeFile(context).takeIf { it.isFile }?.readText()?.trim()?.split('\t')?.getOrNull(3)?.toLongOrNull()
        }.getOrNull() ?: return
        if (now - last > ORPHAN_AFTER_MS) end(context)
    }

    fun sessions(context: Context): List<PlaySession> = runCatching {
        val list = sessionsFile(context).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val p = line.split('\t')
            val start = p.getOrNull(1)?.toLongOrNull()
            val secs = p.getOrNull(2)?.toLongOrNull()
            if (p.size < 3 || p[0].isBlank() || start == null || secs == null) null else PlaySession(p[0], start, secs)
        }
        // The session being played right now is part of the numbers too.
        val live = activeFile(context).takeIf { it.isFile }?.readText()?.trim()?.split('\t')
        val current = if (live != null && live.size >= 3) {
            val start = live[1].toLongOrNull()
            val secs = live[2].toLongOrNull()
            if (start != null && secs != null && secs > 0) PlaySession(live[0], start, secs) else null
        } else null
        if (current != null) list + current else list
    }.getOrDefault(emptyList())

    private fun dayStart(ms: Long): Long = Calendar.getInstance().run {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /** [days] null means everything. [instanceId] null means all worlds. */
    fun summary(all: List<PlaySession>, days: Int?, instanceId: String? = null, now: Long = System.currentTimeMillis()): PlaySummary {
        val from = if (days == null) Long.MIN_VALUE else dayStart(now) - (days - 1) * 86_400_000L
        val picked = all.filter { it.startMs >= from && (instanceId == null || it.instanceId == instanceId) }
        val byDay = picked.groupBy { dayStart(it.startMs) }.mapValues { e -> e.value.sumOf { it.seconds } }
        val perDay = if (days != null) {
            val cal = Calendar.getInstance().apply { timeInMillis = from }
            (0 until days).map { _ ->
                val d = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                d to (byDay[d] ?: 0L)
            }
        } else byDay.toList().sortedBy { it.first }
        val best = byDay.maxByOrNull { it.value }
        return PlaySummary(
            seconds = picked.sumOf { it.seconds },
            sessions = picked.size,
            perDay = perDay,
            perWorld = picked.groupBy { it.instanceId }.mapValues { e -> e.value.sumOf { it.seconds } },
            bestDayStartMs = best?.key,
            bestDaySeconds = best?.value ?: 0L
        )
    }

    fun totalSeconds(context: Context, instanceId: String): Long =
        summary(sessions(context), null, instanceId).seconds

    fun json(context: Context, titles: Map<String, String> = emptyMap(), now: Long = System.currentTimeMillis()): JSONObject {
        val all = sessions(context)
        fun block(s: PlaySummary) = JSONObject().put("seconds", s.seconds).put("sessions", s.sessions)
            .put("best_day_seconds", s.bestDaySeconds)
            .put("best_day_ms", s.bestDayStartMs ?: JSONObject.NULL)
        val ids = all.map { it.instanceId }.distinct()
        val worlds = JSONArray()
        ids.forEach { id ->
            worlds.put(JSONObject().put("instance_id", id).put("title", titles[id] ?: id)
                .put("total", block(summary(all, null, id, now)))
                .put("last_7_days", block(summary(all, 7, id, now)))
                .put("last_30_days", block(summary(all, 30, id, now)))
                .put("last_played_ms", all.filter { it.instanceId == id }.maxOf { it.startMs }))
        }
        return JSONObject().put("version", 1).put("generated_ms", now)
            .put("total", block(summary(all, null, null, now)))
            .put("last_7_days", block(summary(all, 7, null, now)))
            .put("last_30_days", block(summary(all, 30, null, now)))
            .put("worlds", worlds)
    }

    /** Writes BIOMERIA/stats.json where frontends and file managers can read it without permissions. */
    fun export(context: Context) {
        runCatching {
            val titles = InstanceStorage(context).loadInstances().associate { it.id to it.title }
            val out = File(context.getExternalFilesDir(null) ?: context.filesDir, "BIOMERIA").apply { mkdirs() }
            File(out, "stats.json").writeText(json(context, titles).toString(2), Charsets.UTF_8)
        }
    }

    fun format(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> String.format(Locale.US, "%d h %02d min", h, m)
            m > 0 -> "$m min"
            else -> "${seconds} s"
        }
    }
}
