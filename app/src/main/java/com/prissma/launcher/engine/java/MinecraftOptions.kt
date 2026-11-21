package com.prissma.launcher.engine.java

import android.util.AtomicFile
import java.io.File

internal val gameLanguages = linkedMapOf(
    "" to "Del juego",
    "es_cl" to "Español (Chile)",
    "es_es" to "Español (España)",
    "es_mx" to "Español (México)",
    "en_us" to "English",
    "pt_br" to "Português (Brasil)",
    "fr_fr" to "Français",
    "de_de" to "Deutsch",
    "ja_jp" to "日本語"
)

internal fun minecraftOptions(previous: List<String>, language: String): List<String> {
    val updates = linkedMapOf("fullscreen" to "false", "overrideWidth" to "0", "overrideHeight" to "0")
    // Minecraft reduces discrete wheel events to their sign before applying sensitivity.
    if (optionValue(previous, "discrete_mouse_scroll") == "true") updates["mouseWheelSensitivity"] = "1.0"
    if (language.isNotBlank() && language in gameLanguages) updates["lang"] = language
    return previous.filterNot { it.substringBefore(':') in updates } + updates.map { (name, value) -> "$name:$value" }
}

private fun optionValue(lines: List<String>, key: String) =
    lines.lastOrNull { it.substringBefore(':') == key }?.substringAfter(':')

internal fun hotbarScrollScale(options: List<String>): Double {
    val sensitivity = optionValue(options, "mouseWheelSensitivity")?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in .01..10.0 } ?: 1.0
    return Math.nextUp(1.0 / sensitivity)
}

internal fun applyMinecraftOptions(gameDirectory: File, language: String): Double {
    check(gameDirectory.isDirectory || gameDirectory.mkdirs())
    val options = AtomicFile(File(gameDirectory, "options.txt"))
    val previous = try {
        options.openRead().bufferedReader().use { it.readLines() }
    } catch (error: java.io.FileNotFoundException) {
        if (options.baseFile.exists()) throw error
        emptyList()
    }
    val updated = minecraftOptions(previous, language)
    val output = options.startWrite()
    try {
        val data = updated.joinToString("\n", postfix = "\n")
        output.write(data.toByteArray(Charsets.UTF_8))
        options.finishWrite(output)
    } catch (error: Exception) {
        options.failWrite(output)
        throw error
    }
    return hotbarScrollScale(updated)
}
