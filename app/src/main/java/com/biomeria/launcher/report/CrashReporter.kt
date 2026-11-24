package com.biomeria.launcher.report

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.biomeria.launcher.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Sends Biomeria's errors straight to the developer's GitHub Issues: app crashes, and Minecraft sessions that ended in a crash.
 * Reports wait in a folder until they are sent, identical errors from different devices land in one issue, and
 * everything that could identify the player is removed first. Players can turn it off in the settings.
 */
internal object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val PREFS = "crash-reporter"
    private const val ENABLED = "enabled"
    private const val QUEUE_LIMIT = 20
    private const val DAILY_LIMIT = 6

    private val ioLock = Any()

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)
    fun setEnabled(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()

    /** True when this build knows where to send reports. */
    val configured: Boolean get() = BuildConfig.REPORT_TOKEN.isNotBlank()

    private fun queueDir(context: Context) = File(context.filesDir, "error-reports").apply { mkdirs() }

    /** Catches every crash of the app in any of its processes, saves the report and tries to send it before dying. */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                if (enabled(app)) {
                    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                    val report = buildReport("crash", "Exception in thread \"${thread.name}\"\n$trace", baseContext(app, processName(app)))
                    save(app, report)
                    // The network cannot be used on the thread that is dying, so a new one tries for a moment.
                    val sender = thread(name = "crash-send", isDaemon = true) { sendPending(app) }
                    sender.join(3500)
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Called when Minecraft's session ended in a crash, with its log. */
    fun reportGameCrash(context: Context, log: String, cause: String?, extra: Map<String, String>) {
        if (!enabled(context)) return
        runCatching {
            val detail = buildString {
                cause?.let { appendLine("Causa que explicó Biomeria: $it"); appendLine() }
                append(log.takeLast(CrashReport.MAX_DETAIL))
            }
            save(context, buildReport("juego", detail, baseContext(context, "juego") + extra))
            thread(name = "crash-send", isDaemon = true) { sendPending(context) }
        }
    }

    /** Sends whatever is waiting; called when the app starts and after each new report. */
    fun sendPendingAsync(context: Context) {
        if (!enabled(context) || !configured) return
        thread(name = "crash-send", isDaemon = true) { sendPending(context.applicationContext) }
    }

    private fun save(context: Context, report: CrashReport) = synchronized(ioLock) {
        val dir = queueDir(context)
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
        files.take((files.size - QUEUE_LIMIT + 1).coerceAtLeast(0)).forEach { it.delete() }
        File(dir, "${report.createdAt}-${report.fingerprint}.json").writeText(report.toJson())
    }

    private fun baseContext(context: Context, process: String): Map<String, String> = linkedMapOf(
        "Versión" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "Equipo" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Arquitectura" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
        "Proceso" to process
    )

    private fun processName(context: Context): String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName().substringAfter(':', "principal") else "principal"

    @Synchronized
    private fun sendPending(context: Context) {
        if (!configured) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = System.currentTimeMillis() / 86_400_000L
        var sentToday = if (prefs.getLong("day", 0) == today) prefs.getInt("sent", 0) else 0
        val files = queueDir(context).listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.lastModified() }
        for (file in files) {
            val report = CrashReport.fromJson(file.readText())
            if (report == null) { file.delete(); continue }
            if (sentToday >= DAILY_LIMIT) break
            when (deliver(context, report)) {
                Delivery.DONE -> { file.delete(); sentToday++ }
                Delivery.DROP -> file.delete()
                Delivery.LATER -> break
            }
        }
        prefs.edit().putLong("day", today).putInt("sent", sentToday).apply()
    }

    private enum class Delivery { DONE, DROP, LATER }

    private fun deliver(context: Context, report: CrashReport): Delivery {
        return runCatching {
            val repo = BuildConfig.REPORT_REPO
            val known = request("GET", "https://api.github.com/search/issues?q=" +
                URLEncoder.encode("repo:$repo is:issue in:title ${report.fingerprint}", "UTF-8") + "&per_page=1", null)
            if (known.first == 401 || known.first == 403) return Delivery.DROP.also { Log.w(TAG, "GitHub rechazó el token (${known.first})") }
            if (known.first !in 200..299) return Delivery.LATER
            val found = JSONObject(known.second).optJSONArray("items")?.optJSONObject(0)
            if (found != null) {
                val number = found.getInt("number")
                // The same error from this device and version is only counted once.
                val mark = "seen-$number-${BuildConfig.VERSION_CODE}"
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (prefs.getBoolean(mark, false)) return Delivery.DONE
                val summary = report.context.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
                val comment = request("POST", "https://api.github.com/repos/$repo/issues/$number/comments",
                    JSONObject().put("body", "Otra vez, desde otro equipo o versión.\n\n$summary").toString())
                if (comment.first !in 200..299) return Delivery.LATER
                // A closed issue that happens again is reopened: the fix did not hold.
                if (found.optString("state") == "closed") request("PATCH", "https://api.github.com/repos/$repo/issues/$number",
                    JSONObject().put("state", "open").toString())
                prefs.edit().putBoolean(mark, true).apply()
                Delivery.DONE
            } else {
                val created = request("POST", "https://api.github.com/repos/$repo/issues", JSONObject()
                    .put("title", report.title).put("body", report.body()).put("labels", JSONArray().put("error-automatico")).toString())
                if (created.first in 200..299) Delivery.DONE else Delivery.LATER
            }
        }.getOrElse { Delivery.LATER }
    }

    private fun request(method: String, url: String, body: String?): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Authorization", "Bearer ${BuildConfig.REPORT_TOKEN}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Biomeria-Launcher/${BuildConfig.VERSION_NAME}")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            connection.disconnect()
        }
    }
}
