package com.prissma.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class LaunchReport(val instanceId: String, val modifiedAt: Long, val text: String)

internal class LaunchReports(context: Context) {
    private val instances = File(context.filesDir, "instances")

    suspend fun latest(): Result<LaunchReport?> = withContext(Dispatchers.IO) {
        runCatching {
            val log = instances.listFiles()?.asSequence()
                ?.map { File(it, "prissma_last.log") }?.filter { it.isFile && it.length() > 0 }
                ?.maxByOrNull { it.lastModified() } ?: return@runCatching null
            val content = RandomAccessFile(log, "r").use {
                val length = it.length()
                if (length <= 128 * 1024) {
                    ByteArray(length.toInt()).also(it::readFully).toString(Charsets.UTF_8)
                } else {
                    val start = ByteArray(32 * 1024).also(it::readFully).toString(Charsets.UTF_8)
                    val tail = ByteArray(96 * 1024)
                    it.seek(length - tail.size)
                    it.readFully(tail)
                    start.substringBeforeLast('\n') + "\n[PRISSMA] Parte intermedia omitida.\n" +
                        tail.toString(Charsets.UTF_8).substringAfter('\n')
                }
            }
            LaunchReport(log.parentFile?.name.orEmpty(), log.lastModified(), content)
        }
    }

    suspend fun upload(content: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("https://api.mclo.gs/1/log").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("User-Agent", "PRISSMA-Launcher/1.0")
            }
            try {
                val body = "content=" + URLEncoder.encode(content, "UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                check(connection.responseCode in 200..299) { "mclo.gs respondió HTTP " + connection.responseCode }
                val reply = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                check(reply.optBoolean("success")) { "mclo.gs rechazó el reporte." }
                reply.getString("url")
            } finally {
                connection.disconnect()
            }
        }
    }
}
