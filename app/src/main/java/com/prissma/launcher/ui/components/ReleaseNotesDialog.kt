package com.prissma.launcher.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp

private val markdownHeading = Regex("^#{1,6} +")
private val markdownBullet = Regex("^[-*] +")

internal fun readableReleaseNotes(notes: String): String = notes.lineSequence()
    .joinToString("\n") { line ->
        when {
            markdownHeading.containsMatchIn(line) -> line.replaceFirst(markdownHeading, "")
            else -> line.replaceFirst(markdownBullet, "• ")
        }.trimEnd()
    }
    .trim()

@Composable
internal fun ReleaseNotesDialog(version: String, notes: String, onDismiss: () -> Unit) {
    val readableNotes = remember(notes) { readableReleaseNotes(notes) }
    val confirmFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyUp || it.key != Key.ButtonB) return@onPreviewKeyEvent false
            onDismiss()
            true
        },
        title = { Text("Novedades · $version") },
        text = {
            Text(
                text = readableNotes.ifEmpty { "Esta versión todavía no tiene novedades publicadas." },
                modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            LauncherButton("Entendido", onDismiss, Modifier.focusRequester(confirmFocus), primary = true)
            LaunchedEffect(Unit) { confirmFocus.requestFocus() }
        }
    )
}
