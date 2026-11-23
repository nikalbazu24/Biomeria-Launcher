package com.biomeria.launcher.engine.rust

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal class StarlingReports(private val context: Context) {
    companion object {
        private val lock = Mutex()
    }

    private val file = AtomicFile(File(context.noBackupFilesDir, "starling-report.json"))

    suspend fun latest(): StarlingReport? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.baseFile.isFile) return@withLock null
            StarlingReport.fromJson(JSONObject(file.readFully().toString(Charsets.UTF_8)))
        }
    }

    suspend fun importZip(source: Uri): StarlingReport = withContext(Dispatchers.IO) {
        lock.withLock {
            require(source.scheme == "content") { "Elige el archivo con el selector de Android." }
            val staged = File.createTempFile("starling-diagnostic-", ".zip", context.cacheDir)
            try {
                val job = currentCoroutineContext()
                val stream = context.contentResolver.openInputStream(source) ?: throw IOException("Android no pudo leer ese archivo.")
                stream.use { input ->
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            job.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > STARLING_REPORT_LIMIT) throw IOException("El ZIP de diagnóstico supera 6 MiB.")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val report = readStarlingReport(staged, System.currentTimeMillis())
                job.ensureActive()
                val output = file.startWrite()
                try {
                    output.write(report.json().toString().toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
                report
            } finally {
                staged.delete()
            }
        }
    }
}
