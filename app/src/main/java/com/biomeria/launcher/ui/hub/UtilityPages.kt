package com.biomeria.launcher.ui.hub

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.data.AppUpdater
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.data.InstanceStorage
import com.biomeria.launcher.data.LaunchReport
import com.biomeria.launcher.data.LaunchReports
import com.biomeria.launcher.data.UpdateInfo
import com.biomeria.launcher.data.UpdateNotifications
import com.biomeria.launcher.ui.components.ControlShape
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.components.PanelShape
import com.biomeria.launcher.ui.components.ReleaseNotesDialog
import com.biomeria.launcher.ui.components.gamepadClick
import com.biomeria.launcher.ui.theme.Biomeria
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
            if (frontend != null) Text("Último origen: " + frontend, color = Biomeria.colors.textMuted,
                style = MaterialTheme.typography.bodySmall)
            Text("Elige una instancia para copiar su enlace o guardar el cartucho .mcinst.",
                color = Biomeria.colors.textMuted)
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
                Text(link, Modifier.fillMaxWidth().background(Biomeria.colors.surfaceVoid, ControlShape).padding(10.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = Biomeria.colors.netP2P, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LauncherButton("Exportar cartucho", {
                        exportingId = selected.id
                        saveCartridge.launch(storage.cartridgeName(selected))
                    }, primary = true, enabled = exportingId == null)
                    LauncherButton("Copiar enlace", {
                        context.copyText("Enlace de Biomeria", link)
                        onNotice("Enlace copiado.")
                    })
                }
            } else {
                Text("La biblioteca está vacía.", color = Biomeria.colors.textMuted)
            }
        }
        UtilityPanel("Carpeta de cartuchos", "Catálogo generado en este dispositivo") {
            val path = remember(storage) { storage.exportInstancesDir.absolutePath }
            Text(path, color = Biomeria.colors.textMuted, fontFamily = FontFamily.Monospace,
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
    installedNotes: String?,
    onInstalledNotesSeen: () -> Unit,
    onUpdate: (UpdateInfo?) -> Unit,
    onNotice: (String) -> Unit
) {
    val context = LocalContext.current
    val reports = remember(context) { LaunchReports(context) }
    val scope = rememberCoroutineScope()
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var notesDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
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

    var notificationsEnabled by remember { mutableStateOf(UpdateNotifications.enabled(context)) }
    var pendingInstallUrl by rememberSaveable { mutableStateOf<String?>(null) }

    fun install(url: String) {
        progress = 0f
        updateStatus = null
        scope.launch {
            updater.downloadAndInstall(url) { progress = it }
                .onSuccess { updateStatus = "Confirma la instalación en Android." }
                .onFailure { updateStatus = it.message ?: "No se pudo actualizar." }
            progress = null
        }
    }

    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val url = pendingInstallUrl
        pendingInstallUrl = null
        if (url != null && updater.canInstall()) install(url)
        else updateStatus = "Permite instalar apps de Biomeria para continuar."
    }

    val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationsEnabled = UpdateNotifications.enabled(context)
        if (notificationsEnabled) UpdateNotifications.schedule(context)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsEnabled = UpdateNotifications.enabled(context)
        if (granted) UpdateNotifications.schedule(context)
        else onNotice("Puedes habilitar los avisos en los ajustes de Android.")
    }

    fun requestInstall(url: String) {
        if (updater.canInstall()) install(url)
        else {
            pendingInstallUrl = url
            runCatching { installPermission.launch(updater.installPermissionIntent()) }.onFailure {
                pendingInstallUrl = null
                updateStatus = "Abre los ajustes de Android y permite instalar apps de Biomeria."
            }
        }
    }

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
                ?: "Canal de GitHub Releases", color = Biomeria.colors.textMuted)
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
                LauncherButton("Instalar " + found.versionTag, { requestInstall(found.apkDownloadUrl) },
                    primary = true, enabled = !checking && progress == null)
                LauncherButton("Novedades de " + found.versionTag, {
                    notesDialog = found.versionTag to found.changelog
                })
            }
            if (installedNotes != null) {
                LauncherButton("Novedades de la versión instalada", { notesDialog = version to installedNotes })
            }
            var music by remember { mutableStateOf(com.biomeria.launcher.data.MenuMusic.enabled(context)) }
            var musicVolume by remember { mutableStateOf(com.biomeria.launcher.data.MenuMusic.volume(context)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Música de Minecraft en el inicio", fontWeight = FontWeight.Bold)
                    Text(if (com.biomeria.launcher.data.MenuMusic.available(context)) "Usa las canciones de tu Minecraft Java."
                        else "Suena cuando descargues una versión de Java.",
                        color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                }
                androidx.compose.material3.Switch(music, {
                    music = it
                    com.biomeria.launcher.data.MenuMusic.setEnabled(context, it)
                })
            }
            if (music) androidx.compose.material3.Slider(musicVolume, {
                musicVolume = it
                com.biomeria.launcher.data.MenuMusic.setVolume(context, it)
            }, valueRange = 0f..1f)
            LauncherButton("Ver la introducción otra vez", {
                com.biomeria.launcher.ui.intro.IntroPreferences.reset(context)
                onNotice("La introducción aparecerá la próxima vez que abras Biomeria.")
            })
            LauncherButton(if (notificationsEnabled) "Gestionar avisos de Android" else "Activar avisos de actualización", {
                if (!notificationsEnabled && Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            })
            HorizontalDivider(color = Biomeria.colors.borderSubtle)
            Text("Betas de prueba", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            val availablePreview = preview
            when {
                checkingPreview -> Text("Buscando betas…", style = MaterialTheme.typography.bodySmall)
                previewError != null -> Text(previewError.orEmpty(), color = Biomeria.colors.error,
                    style = MaterialTheme.typography.bodySmall)
                availablePreview == null -> Text("No hay una beta de prueba más reciente.",
                    color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text("Las betas de prueba pueden requerir una instalación limpia si cambia la firma.",
                        color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                    LauncherButton("Instalar " + availablePreview.versionTag,
                        { requestInstall(availablePreview.apkDownloadUrl) }, enabled = progress == null)
                    LauncherButton("Novedades de " + availablePreview.versionTag, {
                        notesDialog = availablePreview.versionTag to availablePreview.changelog
                    })
                }
            }
        }
    }
    notesDialog?.let { (notesVersion, body) ->
        ReleaseNotesDialog(notesVersion, body) {
            if (notesVersion == version) onInstalledNotesSeen()
            notesDialog = null
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
                Modifier.fillMaxWidth().heightIn(max = 155.dp).background(Biomeria.colors.surfaceVoid, ControlShape)
                    .verticalScroll(rememberScrollState()).padding(10.dp),
                color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LauncherButton("Recargar", { refresh += 1; link = null }, enabled = !publishing)
                LauncherButton("Copiar", {
                    report?.let { context.copyText("Reporte de Biomeria", it.text) }
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
                Text(url, color = Biomeria.colors.netP2P, style = MaterialTheme.typography.bodySmall)
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
    Column(modifier.fillMaxWidth().background(Biomeria.colors.surfaceCard, PanelShape)
        .border(1.dp, Biomeria.colors.borderSubtle, PanelShape).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = Biomeria.colors.borderSubtle)
        content()
    }
}

internal fun Context.copyText(label: String, content: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, content))
}
