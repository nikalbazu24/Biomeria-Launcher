package com.prissma.launcher.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.prissma.launcher.BuildConfig
import com.prissma.launcher.data.InstanceStorage
import java.util.Locale

class PrissmaCatalogProvider : ContentProvider() {

    companion object {
        // Equals "com.prissma.launcher.provider" in the real app, the address frontends already use.
        val AUTHORITY = BuildConfig.APPLICATION_ID + ".provider"
        private const val CODE_INSTANCES = 1

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "instances", CODE_INSTANCES)
        }

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
        if (uriMatcher.match(uri) != CODE_INSTANCES) return null

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
                    (inst.playtimeHours * 3600f).toLong(),
                    "prissma://launch?instance_id=${inst.id}"
                )
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String? =
        if (uriMatcher.match(uri) == CODE_INSTANCES) {
            "vnd.android.cursor.dir/vnd.$AUTHORITY.instances"
        } else {
            null
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
