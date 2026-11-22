package com.prissma.launcher.ui.hub

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.prissma.launcher.engine.rust.StarlingInstallation
import com.prissma.launcher.engine.rust.StarlingLauncher
import com.prissma.launcher.engine.rust.StarlingReport
import com.prissma.launcher.engine.rust.StarlingReports
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

private const val MAX_CLIPBOARD_CHARS = 256 * 1024

@Composable
internal fun StarlingDiagnosticsDialog(
    installation: StarlingInstallation?,
    launcher: StarlingLauncher,
    sharedReport: Uri?,
    onConsume: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val storage = remember(context) { StarlingReports(context.applicationContext) }
    var document by remember { mutableStateOf<Uri?>(null) }
    var report by remember { mutableStateOf<StarlingReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) document = source
    }
    val source = sharedReport ?: document
    LaunchedEffect(source) {
        if (source == null && report != null) return@LaunchedEffect
        busy = true
        try {
            if (source != null) errorMessage = null
            report = if (source == null) storage.latest() else storage.importZip(source)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            errorMessage = "Android no pudo leer el ZIP. Vuelve a compartirlo desde Starling."
        } catch (error: Exception) {
            errorMessage = error.message ?: "No se pudo leer el diagnóstico de Starling."
        } finally {
            busy = false
            if (sharedReport != null) onConsume(sharedReport)
            if (source != null && document == source) document = null
        }
    }
    var selected by remember(report) { mutableStateOf(report?.logs?.keys?.firstOrNull()) }
    val log = selected?.let { report?.logs?.get(it) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Diagnóstico de Starling") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Desde el diagnóstico de Starling, exporta y comparte el ZIP. Elige PRISSMA en el menú de Android.")
                if (installation?.canOpen == true) {
                    LauncherButton("Abrir diagnóstico de Starling", { errorMessage = launcher.openDiagnostics() }, enabled = !busy)
                }
                LauncherButton("Importar ZIP", {
                    try {
                        picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    } catch (error: ActivityNotFoundException) {
                        errorMessage = "Android no pudo abrir el selector de archivos."
                    }
                }, enabled = !busy)
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                errorMessage?.let { Text(it, color = Prissma.colors.error) }
                report?.let { loaded ->
                    val date = remember(loaded.importedAt) {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(loaded.importedAt))
                    }
                    Text("${loaded.version} · Importado $date", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        loaded.logs.keys.forEach { name ->
                            val choose = { selected = name }
                            FilterChip(selected == name, choose, { Text(name) }, modifier = Modifier.gamepadClick(onClick = choose))
                        }
                    }
                    if (log != null) {
                        val preview = remember(log) { log.takeLast(12_000).lineSequence().toList().takeLast(60).joinToString("\n") }
                        Text(preview.ifBlank { "El registro está vacío." },
                            Modifier.fillMaxWidth().background(Prissma.colors.surfaceVoid, PanelShape).padding(10.dp),
                            color = Prissma.colors.textMuted, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        dismissButton = {
            if (!log.isNullOrEmpty()) {
                val label = if (log.length > MAX_CLIPBOARD_CHARS) "Copiar final del registro" else "Copiar registro"
                LauncherButton(label, { context.copyText("Diagnóstico de Starling", log.takeLast(MAX_CLIPBOARD_CHARS)) }, enabled = !busy)
            }
        },
        confirmButton = { LauncherButton("Cerrar", onDismiss, primary = true, enabled = !busy) }
    )
}
