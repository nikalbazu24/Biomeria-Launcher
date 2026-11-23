package com.biomeria.launcher.ui.workshop

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.engine.bedrock.BedrockArchive
import com.biomeria.launcher.engine.bedrock.BedrockImportDelivery
import com.biomeria.launcher.engine.bedrock.BedrockInstallation
import com.biomeria.launcher.engine.bedrock.BedrockLauncher
import com.biomeria.launcher.engine.bedrock.BedrockLibraryItem
import com.biomeria.launcher.engine.bedrock.BedrockLibraryStorage
import com.biomeria.launcher.engine.bedrock.InvalidBedrockArchive
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.components.PageHeader
import com.biomeria.launcher.ui.components.PanelShape
import com.biomeria.launcher.ui.components.gamepadClick
import com.biomeria.launcher.ui.hub.BedrockAppsDialog
import com.biomeria.launcher.ui.theme.Biomeria
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
internal fun BedrockWorkshopScreen(
    instance: GameInstance,
    installation: BedrockInstallation?,
    launcher: BedrockLauncher,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val storage = remember(context, instance.id) { BedrockLibraryStorage(context.applicationContext, instance.id) }
    val scope = rememberCoroutineScope()
    val progress = remember { MutableStateFlow<String?>(null) }
    val working by progress.collectAsState()
    var archives by remember(storage) { mutableStateOf(emptyList<BedrockLibraryItem>()) }
    var filter by rememberSaveable { mutableStateOf<BedrockArchive?>(null) }
    var exportingId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var hasError by rememberSaveable { mutableStateOf(false) }
    var chooseApp by rememberSaveable { mutableStateOf(false) }

    fun report(message: String, error: Boolean = false) {
        notice = message
        hasError = error
    }

    suspend fun runTask(label: String, failure: String, action: suspend () -> Unit) {
        if (progress.value != null) return
        progress.value = label
        notice = null
        try {
            action()
        } catch (error: InvalidBedrockArchive) {
            report(error.message.orEmpty(), error = true)
        } catch (error: IOException) {
            report(failure, error = true)
        } catch (error: SecurityException) {
            report("Android no dio acceso al archivo. Vuelve a seleccionarlo.", error = true)
        } finally {
            progress.value = null
        }
    }

    LaunchedEffect(storage) {
        runTask("Cargando biblioteca…", "No se pudo abrir el contenido de esta instancia.") {
            archives = storage.list()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) scope.launch {
            runTask("Copiando el archivo…", "No se pudo guardar el archivo. Revisa que se pueda leer y quede espacio libre.") {
                val result = storage.importArchive(source) { progress.value = "Verificando el archivo…" }
                archives = storage.list()
                filter = result.item.type
                report(if (result.added) "Guardado en esta instancia." else "Este archivo ya está guardado en esta instancia.")
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destination ->
        val id = exportingId
        exportingId = null
        if (destination != null && id != null) scope.launch {
            runTask("Exportando archivo…", "No se pudo exportar el archivo al destino elegido.") {
                storage.export(id, destination)
                report("Copia exportada.")
            }
        }
    }
    val ready = working == null && exportingId == null
    val filtered = archives.filter { filter == null || it.type == filter }
    val target = installation?.takeIf { it.canOpen }

    LazyColumn(
        Modifier.fillMaxSize().background(Biomeria.colors.surfaceVoid).safeDrawingPadding()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.ButtonB || it.key == Key.Escape)) {
                    onBack()
                    true
                } else false
            },
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { PageHeader("Taller de Bedrock", instance.title, onBack) }
        item {
            Column(Modifier.fillMaxWidth().background(Biomeria.colors.surfaceCard, PanelShape).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Biblioteca de contenido", style = MaterialTheme.typography.titleLarge)
                Text("Archivos de esta instancia · .mcpack, .mcaddon y .mcworld", color = Biomeria.colors.textMuted)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LauncherButton("Importar archivo", {
                        try {
                            picker.launch(arrayOf("*/*"))
                        } catch (error: ActivityNotFoundException) {
                            report("Android no pudo abrir el selector de archivos.", error = true)
                        }
                    }, primary = true, enabled = ready)
                    LauncherButton(if (target == null) "Buscar Minecraft" else "Cambiar aplicación", { chooseApp = true }, enabled = ready)
                }
                working?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                notice?.let { message ->
                    Text(message, color = if (hasError) Biomeria.colors.error else Biomeria.colors.brandAccent,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tabs = listOf(null to "Todo", BedrockArchive.PACK to "Paquetes", BedrockArchive.ADDON to "Complementos",
                    BedrockArchive.WORLD to "Mundos")
                for ((type, label) in tabs) {
                    val select = { filter = type }
                    FilterChip(filter == type, select, { Text(label) }, modifier = Modifier.gamepadClick(onClick = select))
                }
            }
        }
        if (filtered.isEmpty() && working == null) item {
            Text(if (filter == null) "Aún no hay archivos guardados en esta instancia." else "No hay archivos en esta categoría.",
                color = Biomeria.colors.textMuted, modifier = Modifier.padding(horizontal = 4.dp))
        }
        items(filtered, key = { it.id }) { archive ->
            Column(Modifier.fillMaxWidth().background(Biomeria.colors.surfaceCard, PanelShape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(archive.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${archive.type.label} · ${Formatter.formatShortFileSize(context, archive.size)}", color = Biomeria.colors.textMuted)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LauncherButton("Exportar copia", {
                        exportingId = archive.id
                        try {
                            export.launch(archive.name)
                        } catch (error: ActivityNotFoundException) {
                            exportingId = null
                            report("Android no pudo abrir el selector de destino.", error = true)
                        }
                    }, enabled = ready)
                    if (target != null) LauncherButton("Abrir en Minecraft", {
                        scope.launch {
                            runTask("Preparando el archivo…", "No se pudo compartir el archivo guardado.") {
                                when (launcher.importArchive(archive, storage.sharedUri(archive.id), target.packageName)) {
                                    BedrockImportDelivery.SENT -> report("Enviado a ${target.title}. Revisa su aviso de importación.")
                                    BedrockImportDelivery.NO_GAME -> {
                                        onRefresh()
                                        report("Minecraft dejó de estar disponible. Vuelve a elegir la aplicación.", error = true)
                                    }
                                    BedrockImportDelivery.NO_IMPORTER -> report("${target.title} no abrió el archivo .${archive.type.extension}.", error = true)
                                }
                            }
                        }
                    }, enabled = ready)
                    LauncherButton("Quitar", { removingId = archive.id }, enabled = ready)
                }
            }
        }
    }

    archives.firstOrNull { it.id == removingId }?.let { archive ->
        AlertDialog(onDismissRequest = { removingId = null }, title = { Text("¿Quitar de esta instancia?") },
            text = { Text(archive.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = { LauncherButton("Quitar copia", {
                removingId = null
                scope.launch {
                    runTask("Quitando archivo…", "No se pudo quitar el archivo de esta instancia.") {
                        if (!storage.remove(archive.id)) throw IOException("El archivo ya no está en la biblioteca.")
                        archives = storage.list()
                        report("Copia quitada de esta instancia.")
                    }
                }
            }) }, dismissButton = { LauncherButton("Cancelar", { removingId = null }, primary = true) })
    }
    if (chooseApp) BedrockAppsDialog(installation, launcher, onRefresh, onDismiss = { chooseApp = false })
}
