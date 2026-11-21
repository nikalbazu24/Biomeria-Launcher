package com.prissma.launcher.ui.hub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prissma.launcher.data.AppUpdater
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.data.InstanceStorage
import com.prissma.launcher.data.LaunchReport
import com.prissma.launcher.data.LaunchReports
import com.prissma.launcher.data.UpdateInfo
import com.prissma.launcher.ui.components.ControlShape
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConnectionsPage(
    storage: InstanceStorage,
    instances: List<GameInstance>,
    selected: GameInstance?,
    frontend: String?,
    onSelect: (GameInstance) -> Unit,
    onNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportingId by rememberSaveable { mutableStateOf<String?>(null) }
    val saveCartridge = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val instance = instances.firstOrNull { it.id == exportingId }
        exportingId = null
        if (uri != null && instance != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { storage.exportCartridge(instance, uri) } }
                .onSuccess { onNotice("Cartucho exportado.") }
                .onFailure { onNotice("No se pudo guardar el cartucho.") }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UtilityPanel("Frontends", "Cocoon, iiSU y Díptico") {
            if (frontend != null) Text("Último origen: " + frontend, color = Prissma.colors.textMuted,
                style = MaterialTheme.typography.bodySmall)
            Text("Elige una instancia para copiar su enlace o guardar el cartucho .mcinst.",
                color = Prissma.colors.textMuted)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                instances.forEach { item ->
                    val select = { onSelect(item) }
                    FilterChip(selected?.id == item.id, select,
                        { Text(item.title.take(30), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.gamepadClick(onClick = select))
                }
            }
            if (selected != null) {
                val link = storage.launchLink(selected)
                Text(link, Modifier.fillMaxWidth().background(Prissma.colors.surfaceVoid, ControlShape).padding(10.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = Prissma.colors.netP2P, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LauncherButton("Exportar cartucho", {
                        exportingId = selected.id
                        saveCartridge.launch(storage.cartridgeName(selected))
                    }, primary = true, enabled = exportingId == null)
                    LauncherButton("Copiar enlace", {
                        context.copyText("Enlace de PRISSMA", link)
                        onNotice("Enlace copiado.")
                    })
                }
            } else {
                Text("La biblioteca está vacía.", color = Prissma.colors.textMuted)
            }
        }
        UtilityPanel("Carpeta de cartuchos", "Catálogo generado en este dispositivo") {
            val path = remember(storage) { storage.exportInstancesDir.absolutePath }
            Text(path, color = Prissma.colors.textMuted, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
            LauncherButton("Copiar ruta", {
                context.copyText("Ruta de cartuchos", path)
                onNotice("Ruta copiada.")
            })
        }
    }
}

@Composable
internal fun SettingsPage(
    updater: AppUpdater,
    version: String,
    update: UpdateInfo?,
    onUpdate: (UpdateInfo?) -> Unit,
    onNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val reports = remember(context) { LaunchReports(context) }
    val scope = rememberCoroutineScope()
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var preview by remember { mutableStateOf<UpdateInfo?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var checkingPreview by remember { mutableStateOf(true) }
    var previewRefresh by remember { mutableIntStateOf(0) }
    var latest by remember { mutableStateOf<LaunchReport?>(null) }
    var reportError by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var publishing by remember { mutableStateOf(false) }

    LaunchedEffect(updater, previewRefresh) {
        checkingPreview = true
        previewError = null
        updater.checkForPreview().onSuccess { preview = it }
            .onFailure { previewError = it.message ?: "No se pudieron consultar las betas." }
        checkingPreview = false
    }

    LaunchedEffect(reports, refresh) {
        reports.latest().onSuccess {
            latest = it
            reportError = null
        }.onFailure {
            reportError = "No se pudo leer el último reporte."
        }
    }
    val updatesPanel: @Composable (Modifier) -> Unit = { modifier ->
        UtilityPanel("Actualizaciones", "Instalada · " + version, modifier) {
            Text(updateStatus ?: update?.let { "Disponible · " + it.versionTag }
                ?: "Canal de GitHub Releases", color = Prissma.colors.textMuted)
            progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                Text("Descargando · " + (it * 100).toInt() + " %", style = MaterialTheme.typography.bodySmall)
            }
            LauncherButton(if (checking) "Buscando…" else "Buscar actualización", {
                checking = true
                previewRefresh += 1
                scope.launch {
                    updater.checkForUpdate().onSuccess { found ->
                        onUpdate(found)
                        updateStatus = if (found == null) "No hay una actualización oficial más reciente."
                            else "Disponible · " + found.versionTag
                    }.onFailure { updateStatus = it.message ?: "No se pudo consultar GitHub." }
                    checking = false
                }
            }, icon = Icons.Default.Refresh, enabled = !checking && !checkingPreview && progress == null)
            update?.let { found ->
                LauncherButton("Descargar " + found.versionTag, {
                    progress = 0f
                    scope.launch {
                        updater.downloadAndInstall(found.apkDownloadUrl) { progress = it }
                            .onFailure { updateStatus = it.message ?: "No se pudo actualizar." }
                        progress = null
                    }
                }, primary = true, enabled = !checking && progress == null)
                Text(found.changelog.take(240), color = Prissma.colors.textMuted,
                    style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = Prissma.colors.borderSubtle)
            Text("Betas de prueba", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            val availablePreview = preview
            when {
                checkingPreview -> Text("Buscando betas…", style = MaterialTheme.typography.bodySmall)
                previewError != null -> Text(previewError.orEmpty(), color = Prissma.colors.error,
                    style = MaterialTheme.typography.bodySmall)
                availablePreview == null -> Text("No hay una beta de prueba más reciente.",
                    color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text("Las betas de prueba pueden requerir una instalación limpia si cambia la firma.",
                        color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                    LauncherButton("Abrir " + availablePreview.versionTag, {
                        runCatching { uriHandler.openUri(availablePreview.apkDownloadUrl) }
                            .onFailure { onNotice("No se pudo abrir la descarga de GitHub.") }
                    })
                }
            }
        }
    }
    val reportPanel: @Composable (Modifier) -> Unit = { modifier ->
        val report = latest
        val detail = report?.let {
            it.instanceId + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(it.modifiedAt))
        } ?: "Último arranque de Java"
        UtilityPanel("Diagnóstico", detail, modifier) {
            val preview = remember(report) { report?.text?.lineSequence()?.toList()?.takeLast(60)?.joinToString("\n") }
            Text(reportError ?: preview ?: "Todavía no hay un reporte de arranque.",
                Modifier.fillMaxWidth().heightIn(max = 155.dp).background(Prissma.colors.surfaceVoid, ControlShape)
                    .verticalScroll(rememberScrollState()).padding(10.dp),
                color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LauncherButton("Recargar", { refresh += 1; link = null }, enabled = !publishing)
                LauncherButton("Copiar", {
                    report?.let { context.copyText("Reporte de PRISSMA", it.text) }
                    onNotice("Reporte copiado.")
                }, enabled = report != null)
                LauncherButton(if (publishing) "Subiendo…" else "Subir a mclo.gs", {
                    if (report != null) {
                        publishing = true
                        scope.launch {
                            reports.upload(report.text).onSuccess {
                                link = it
                                context.copyText("Enlace del reporte", it)
                                onNotice("Enlace del reporte copiado.")
                            }.onFailure { onNotice(it.message ?: "No se pudo subir el reporte.") }
                            publishing = false
                        }
                    }
                }, enabled = report != null && !publishing)
            }
            link?.let { url ->
                Text(url, color = Prissma.colors.netP2P, style = MaterialTheme.typography.bodySmall)
                LauncherButton("Copiar enlace", { context.copyText("Enlace del reporte", url); onNotice("Enlace copiado.") })
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 620.dp) {
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                updatesPanel(Modifier.weight(.8f))
                reportPanel(Modifier.weight(1.2f))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                updatesPanel(Modifier.fillMaxWidth())
                reportPanel(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun UtilityPanel(title: String, subtitle: String, modifier: Modifier = Modifier,
                         content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Prissma.colors.surfaceCard, PanelShape)
        .border(1.dp, Prissma.colors.borderSubtle, PanelShape).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = Prissma.colors.borderSubtle)
        content()
    }
}

private fun Context.copyText(label: String, content: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, content))
}
