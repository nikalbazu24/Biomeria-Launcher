package com.biomeria.launcher.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.biomeria.launcher.BuildConfig
import com.biomeria.launcher.data.InstanceStorage
import com.biomeria.launcher.data.PlayStats
import java.util.Locale

class BiomeriaCatalogProvider : ContentProvider() {

    companion object {
        // Equals "com.biomeria.launcher.provider" in the real app, the address frontends already use.
        val AUTHORITY = BuildConfig.APPLICATION_ID + ".provider"
        private const val CODE_INSTANCES = 1
        private const val CODE_STATS = 2
        private const val CODE_STATS_SUMMARY = 3

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "instances", CODE_INSTANCES)
            addURI(AUTHORITY, "stats", CODE_STATS)
            addURI(AUTHORITY, "stats/summary", CODE_STATS_SUMMARY)
        }

        // One row per world: total, last 7 days, last 30 days, sessions and when it was last played.
        private val STATS_COLUMNS = arrayOf(
            "instance_id", "title", "seconds_total", "seconds_7d", "seconds_30d", "sessions", "last_played_ms"
        )

        // A single row with everything together.
        private val SUMMARY_COLUMNS = arrayOf(
            "seconds_total", "seconds_7d", "seconds_30d", "sessions", "best_day_seconds", "best_day_ms"
        )

        private val COLUMNS = arrayOf(
            "instance_id",
            "title",
            "engine",
            "mc_version",
            "loader",
            "playtime_seconds",
            "deep_link"
        )
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        when (uriMatcher.match(uri)) {
            CODE_STATS -> return statsCursor(ctx)
            CODE_STATS_SUMMARY -> return summaryCursor(ctx)
            CODE_INSTANCES -> Unit
            else -> return null
        }

        val storage = InstanceStorage(ctx)
        val instances = storage.loadInstances()
        val cursor = MatrixCursor(COLUMNS, instances.size)

        instances.forEach { inst ->
            cursor.addRow(
                arrayOf(
                    inst.id,
                    inst.title,
                    inst.engine.name.lowercase(Locale.US),
                    inst.version,
                    inst.loader,
                    maxOf(PlayStats.totalSeconds(ctx, inst.id), (inst.playtimeHours * 3600f).toLong()),
                    "biomeria://launch?instance_id=${inst.id}"
                )
            )
        }
        return cursor
    }

    private fun statsCursor(ctx: Context): Cursor {
        val all = PlayStats.sessions(ctx)
        val titles = InstanceStorage(ctx).loadInstances().associate { it.id to it.title }
        val cursor = MatrixCursor(STATS_COLUMNS)
        all.map { it.instanceId }.distinct().forEach { id ->
            val total = PlayStats.summary(all, null, id)
            cursor.addRow(arrayOf(
                id, titles[id] ?: id, total.seconds,
                PlayStats.summary(all, 7, id).seconds, PlayStats.summary(all, 30, id).seconds,
                total.sessions, all.filter { it.instanceId == id }.maxOf { it.startMs }
            ))
        }
        return cursor
    }

    private fun summaryCursor(ctx: Context): Cursor {
        val all = PlayStats.sessions(ctx)
        val total = PlayStats.summary(all, null)
        return MatrixCursor(SUMMARY_COLUMNS, 1).apply {
            addRow(arrayOf(
                total.seconds, PlayStats.summary(all, 7).seconds, PlayStats.summary(all, 30).seconds,
                total.sessions, total.bestDaySeconds, total.bestDayStartMs ?: 0L
            ))
        }
    }

    override fun getType(uri: Uri): String? =
        when (uriMatcher.match(uri)) {
            CODE_INSTANCES -> "vnd.android.cursor.dir/vnd.$AUTHORITY.instances"
            CODE_STATS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.stats"
            CODE_STATS_SUMMARY -> "vnd.android.cursor.item/vnd.$AUTHORITY.stats"
            else -> null
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
