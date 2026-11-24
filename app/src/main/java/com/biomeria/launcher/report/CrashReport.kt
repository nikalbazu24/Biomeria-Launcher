package com.biomeria.launcher.report

import org.json.JSONObject
import java.security.MessageDigest

/** One error ready to travel: where it happened, what it was, and the text that explains it. */
internal class CrashReport(
    val kind: String,
    val headline: String,
    val fingerprint: String,
    val detail: String,
    val context: Map<String, String>,
    val createdAt: Long
) {
    fun toJson(): String = JSONObject().put("kind", kind).put("headline", headline).put("fingerprint", fingerprint)
        .put("detail", detail).put("context", JSONObject(context)).put("createdAt", createdAt).toString()

    /** The issue title: what failed, then the code that groups the same error from different devices. */
    val title: String get() = "[$kind] ${headline.take(110)} · $fingerprint"

    fun body(): String = buildString {
        appendLine("Reporte automático de Biomeria.")
        appendLine()
        context.forEach { (key, value) -> appendLine("- **$key:** $value") }
        appendLine()
        appendLine("```")
        append(detail.take(MAX_DETAIL))
        appendLine()
        appendLine("```")
    }.take(MAX_BODY)

    companion object {
        const val MAX_DETAIL = 48_000
        const val MAX_BODY = 60_000

        fun fromJson(text: String): CrashReport? = runCatching {
            val json = JSONObject(text)
            val context = json.optJSONObject("context")?.let { c -> c.keys().asSequence().associateWith { c.optString(it) } }.orEmpty()
            CrashReport(json.getString("kind"), json.getString("headline"), json.getString("fingerprint"), json.getString("detail"),
                context, json.optLong("createdAt"))
        }.getOrNull()
    }
}

private val secrets = listOf(
    Regex("""(?i)(bearer|authorization:?)\s+[A-Za-z0-9._\-]{12,}""") to "$1 [oculto]",
    Regex("""eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}""") to "[token oculto]",
    Regex("""(?i)(access[_-]?token|refresh[_-]?token|session[_-]?id|accessToken|--accessToken|--uuid|--username|password|secret)[=:\s\"']+[^\s\"',&]{4,}""") to "$1=[oculto]",
    Regex("""(?i)Setting user:\s*\S+""") to "Setting user: [oculto]",
    Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to "[correo oculto]",
    Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""") to "[id oculto]",
    Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}\b|\bgithub_pat_[A-Za-z0-9_]{20,}\b""") to "[token oculto]"
)

/** Removes what could identify the player or open their accounts before anything leaves the device. */
internal fun scrub(text: String): String = secrets.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

private val frameLine = Regex("""^\s*at\s+([\w.$<>/]+)\(""")
private val noisyFrames = listOf("java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.", "com.android.", "jdk.", "sun.", "dalvik.")

/** A short code that is the same for the same error on any device: the exception and the first frames of the code that failed. */
internal fun fingerprintOf(kind: String, detail: String): String {
    val lines = detail.lines()
    val exception = lines.firstOrNull { it.contains("Exception") || it.contains("Error") }?.substringBefore(':')?.trim().orEmpty()
    val frames = lines.mapNotNull { frameLine.find(it)?.groupValues?.get(1) }
    val own = frames.filter { frame -> noisyFrames.none { frame.startsWith(it) } }.take(3).ifEmpty { frames.take(2) }
    val source = listOf(kind, exception, own.joinToString("|")).joinToString("#")
        .ifBlank { kind + detail.take(80) }
    val digest = MessageDigest.getInstance("SHA-1").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
    return digest.take(8)
}

/** The line to put in a title: the exception and its message, or the first meaningful line. */
internal fun headlineOf(detail: String): String {
    val line = detail.lines().firstOrNull { it.isNotBlank() && !it.startsWith("---") && !it.startsWith("Time:") } ?: "Error sin detalle"
    return scrub(line.trim().replace(Regex("\\s+"), " "))
}

internal fun buildReport(kind: String, detail: String, context: Map<String, String>, now: Long = System.currentTimeMillis()): CrashReport {
    val clean = scrub(detail)
    return CrashReport(kind, headlineOf(detail), fingerprintOf(kind, clean), clean, context.mapValues { scrub(it.value) }, now)
}
