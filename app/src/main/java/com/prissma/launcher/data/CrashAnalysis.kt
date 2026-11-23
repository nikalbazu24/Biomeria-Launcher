package com.prissma.launcher.data

internal enum class LogLevel { INFO, WARN, ERROR }

/** Level of one line of the game log, for colouring and filtering. */
internal fun classifyLogLine(line: String): LogLevel {
    val upper = line.uppercase()
    return when {
        "/ERROR]" in upper || "[ERROR]" in upper || "/FATAL]" in upper || "[FATAL]" in upper ||
            line.startsWith("Exception in thread") || line.trimStart().startsWith("Caused by:") ||
            "OutOfMemoryError" in line -> LogLevel.ERROR
        "/WARN]" in upper || "[WARN]" in upper || "[AVISO]" in upper -> LogLevel.WARN
        else -> LogLevel.INFO
    }
}

/**
 * What a finished game session left in its log: whether it crashed, a cause in plain
 * words when the error is a known one, and the last lines to show the player.
 */
internal data class CrashReading(val crashed: Boolean, val cause: String?, val tail: List<String>)

private val crashMarkers = listOf(
    "---- Minecraft Crash Report ----", "#@!@# Game crashed!", "Incompatible mods found", "Incompatible mod set",
    "Exception in thread \"main\"", "Exception in thread \"Render thread\"", "OutOfMemoryError",
    "Could not reserve enough space", "Mixin apply failed", "MixinApplyError", "Could not find or load main class",
    "Failed to create window", "[ERROR] No se pudo iniciar"
)

private val missingDependency = Regex(
    """Mod '([^']+)' \(([^)]+)\) [^\n]*?requires (?:version |any version )?([^\n]*?) of (?:mod )?'?([A-Za-z0-9_\-. ]+?)'?(?:,|!|\.|$| \()""",
    RegexOption.MULTILINE
)

internal fun readCrash(log: String, tailLines: Int = 40): CrashReading {
    val lines = log.lines().filter { it.isNotBlank() }
    val tail = lines.takeLast(tailLines)
    val ending = lines.takeLast(80).joinToString("\n")
    val crashed = crashMarkers.any { it in log } && !("Stopping!" in ending && "Crash Report" !in log)
    if (!crashed) return CrashReading(false, null, tail)
    return CrashReading(true, crashCause(log), tail)
}

/** Known errors explained for players who never read a log. Unknown errors return null. */
internal fun crashCause(log: String): String? {
    missingDependency.find(log)?.let { match ->
        val mod = match.groupValues[1].trim()
        val version = match.groupValues[3].trim().removePrefix("version ").trim().replace(" or later", " o posterior")
            .let { if (it.startsWith("any version")) "" else it }
        val dependency = match.groupValues[4].trim().let { if (it == "fabricloader") "Fabric" else it }
        return if (version.isBlank()) "El mod $mod necesita $dependency y no está instalado. Agrégalo desde el Taller."
        else "El mod $mod necesita $dependency $version, y este mundo tiene otra versión. Actualiza el cargador o cambia el mod en el Taller."
    }
    return when {
        "OutOfMemoryError" in log || "Java heap space" in log || "Could not reserve enough space" in log ->
            "Minecraft se quedó sin memoria. Sube la RAM en Rendimiento o quita mods pesados."
        "Incompatible mods found" in log || "Incompatible mod set" in log ->
            "Hay mods que no son compatibles entre sí o con esta versión de Minecraft. Revísalos en el Taller."
        "Mixin apply failed" in log || "MixinApplyError" in log || "InvalidInjectionException" in log ->
            "Un mod no pudo modificar el juego. Suele pasar con mods hechos para otra versión de Minecraft."
        "Failed to create window" in log || "GLFW error" in log ->
            "Hubo un problema al abrir los gráficos. Prueba bajar la escala de resolución en Rendimiento."
        "Could not find or load main class" in log || "ClassNotFoundException: net.minecraft" in log ->
            "Faltan archivos del juego. Vuelve a descargarlos desde la pantalla de preparación."
        else -> null
    }
}
