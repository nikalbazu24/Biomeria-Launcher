package com.biomeria.launcher.ui.workshop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.data.InstalledFileItem
import com.biomeria.launcher.data.ModrinthItem
import com.biomeria.launcher.data.ModrinthRepository
import com.biomeria.launcher.data.WorkshopCategory
import com.biomeria.launcher.data.workshopModLoaderName
import com.biomeria.launcher.engine.java.GameDownloads
import com.biomeria.launcher.ui.components.ControlShape
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.components.PageHeader
import com.biomeria.launcher.ui.components.PanelShape
import com.biomeria.launcher.ui.components.gamepadClick
import com.biomeria.launcher.ui.theme.Biomeria
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class WorkshopTab(val label: String, val category: WorkshopCategory?) {
    MODS("Mods", WorkshopCategory.MODS),
    SHADERS("Shaders", WorkshopCategory.SHADERS),
    PACKS("Texturas", WorkshopCategory.RESOURCE_PACKS),
    INSTALLED("Guardados", null)
}

@Composable
fun ContentWorkshopScreen(
    instance: GameInstance,
    importingPack: Boolean,
    packError: String?,
    onImportPack: () -> Unit,
    onPackDownloads: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val repository = remember(context) { ModrinthRepository(context) }
    val scope = rememberCoroutineScope()
    val downloads = remember(context) { GameDownloads(context) }
    val jobs by remember(downloads, instance.id) { downloads.mods(instance) }.collectAsState(emptyList())
    val activeJobs = jobs.filter { !it.state.isFinished }
    val finished = jobs.filter { it.state.isFinished }.map { it.id }
    val focus = LocalFocusManager.current
    val loader = instance.configuredModLoader
    val supportsMods = workshopModLoaderName(loader) != null

    var tab by rememberSaveable(instance.id) {
        mutableStateOf(if (loader == null) WorkshopTab.PACKS else WorkshopTab.MODS)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var includeOtherMods by rememberSaveable(instance.id) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<ModrinthItem>>(emptyList()) }
    var installed by remember { mutableStateOf<List<InstalledFileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showTutorial by rememberSaveable { mutableStateOf(false) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmRemoval by remember { mutableStateOf<InstalledFileItem?>(null) }
    var checkMod by remember(instance.id, instance.version, loader) { mutableStateOf<ModrinthItem?>(null) }
    val latestJobs = remember(jobs, instance) { latestWorkshopJobs(jobs, listOf(instance)) }

    fun search() {
        focus.clearFocus()
        submitted = query.trim()
        reload += 1
    }

    LaunchedEffect(tab, submitted, reload, instance, includeOtherMods) {
        status = null
        val category = tab.category
        if (category == null) {
            installed = withContext(Dispatchers.IO) { repository.getInstalledFiles(instance.id, instance.version, loader) }
            loading = false
        } else {
            results = emptyList()
            loading = true
            repository.searchProjects(submitted, category, instance.version, loader, onlyCompatible = !includeOtherMods)
                .onSuccess { results = it }
                .onFailure { status = it.message ?: "No se pudo consultar Modrinth." }
            loading = false
        }
    }

    LaunchedEffect(finished, tab) {
        if (tab == WorkshopTab.INSTALLED) {
            installed = withContext(Dispatchers.IO) { repository.getInstalledFiles(instance.id, instance.version, loader) }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Biomeria.colors.surfaceVoid).safeDrawingPadding().padding(14.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ButtonB, Key.Escape -> { onBack(); true }
                    Key.ButtonL1, Key.ButtonR1 -> {
                        val tabs = WorkshopTab.entries
                        val step = if (event.key == Key.ButtonL1) -1 else 1
                        tab = tabs[(tab.ordinal + tabs.size + step) % tabs.size]
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PageHeader("Taller", "${instance.title} · Minecraft ${instance.version} · ${instance.loader}", onBack)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LauncherButton(workshopDownloadsLabel(jobs), { showDownloads = true }, primary = activeJobs.isNotEmpty())
            WorkshopTab.entries.forEach {
                val choose = { tab = it }
                FilterChip(tab == it, choose, { Text(it.label) }, modifier = Modifier.gamepadClick(onClick = choose))
            }
            LauncherButton("Cómo agregar mods", { showTutorial = true })
            LauncherButton(if (importingPack) "Leyendo modpack…" else "Importar .mrpack", onImportPack, enabled = !importingPack)
            LauncherButton("Descargas de modpacks", onPackDownloads)
        }
        packError?.let { Text(it, color = Biomeria.colors.error, style = MaterialTheme.typography.bodySmall) }
        if (tab.category != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f),
                    placeholder = { Text("Buscar en " + tab.label.lowercase()) }, singleLine = true,
                    shape = ControlShape, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }))
                LauncherButton("Buscar", ::search, icon = Icons.Default.Search, enabled = !loading)
            }
            if (tab == WorkshopTab.MODS && supportsMods) {
                val toggle = { includeOtherMods = !includeOtherMods }
                FilterChip(includeOtherMods, toggle, { Text("Incluir otras versiones y cargadores") },
                    modifier = Modifier.gamepadClick(onClick = toggle))
            } else if (tab == WorkshopTab.MODS) {
                Text("Selecciona Fabric o NeoForge en la instancia para instalar mods.",
                    style = MaterialTheme.typography.bodySmall, color = Biomeria.colors.textMuted)
            }
        }
        status?.let { Text(it, color = Biomeria.colors.netP2P, style = MaterialTheme.typography.bodySmall) }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            tab == WorkshopTab.INSTALLED && installed.isEmpty() -> {
                EmptyResults("Todavía no hay archivos guardados en esta instancia.")
            }
            tab == WorkshopTab.INSTALLED -> LazyVerticalGrid(
                columns = GridCells.Adaptive(245.dp), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(installed, key = { it.file.absolutePath }) {
                    Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(it.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(it.categoryLabel + " · " + it.sizeKb + " KB", color = Biomeria.colors.textMuted,
                            style = MaterialTheme.typography.bodySmall)
                        it.compatibility?.let { note ->
                            Text(note, style = MaterialTheme.typography.bodySmall, color = Biomeria.colors.textMuted)
                        }
                        LauncherButton("Quitar archivo", { confirmRemoval = it }, enabled = activeJobs.isEmpty())
                    }
                }
            }
            results.isEmpty() -> EmptyResults(if (tab == WorkshopTab.MODS && includeOtherMods) "No se encontraron mods."
                else "Sin resultados compatibles con Minecraft ${instance.version}.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(245.dp), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(results, key = { it.projectId }) { item ->
                    val job = latestJobs.firstOrNull { it.workshopProject == item.projectId || item.projectId in it.tags }
                    ProjectCard(
                        item, job,
                        enabled = job?.state?.isFinished != false && (item.category != WorkshopCategory.MODS || supportsMods)
                    ) {
                        if (item.category == WorkshopCategory.MODS) checkMod = item
                        else downloads.mod(instance, item.projectId, item.title, item.category)
                    }
                }
            }
        }
    }

    if (showDownloads) WorkshopDownloadsDialog(jobs, listOf(instance), downloads) { showDownloads = false }

    checkMod?.let { item ->
        WorkshopCompatibilityDialog(item, instance, repository, onDismiss = { checkMod = null }) { plan ->
            downloads.mod(instance, item.projectId, item.title, item.category, plan.pins)
            checkMod = null
        }
    }

    if (showTutorial) {
        AlertDialog(
            onDismissRequest = { showTutorial = false },
            title = { Text("Agregar mods") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Crea una instancia Java, elige la versión de Minecraft y selecciona Fabric o NeoForge.")
                    Text("2. Abre el Taller de esa instancia, entra en Mods y busca lo que quieres instalar.")
                    Text("3. Pulsa Comprobar. Revisa qué versión del mod y qué dependencias se instalarán, y confirma la descarga.")
                    Text("4. Revisa los archivos en Guardados. Cuando termine la descarga, vuelve a la instancia y pulsa Jugar.")
                }
            },
            confirmButton = { LauncherButton("Entendido", { showTutorial = false }, primary = true) }
        )
    }

    confirmRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRemoval = null },
            title = { Text("¿Quitar archivo?") },
            text = { Text(target.fileName) },
            dismissButton = { LauncherButton("Cancelar", { confirmRemoval = null }, primary = true) },
            confirmButton = { LauncherButton("Quitar", {
                confirmRemoval = null
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { repository.deleteInstalledFile(target) }
                    installed = withContext(Dispatchers.IO) { repository.getInstalledFiles(instance.id, instance.version, loader) }
                    status = if (removed) "Archivo quitado." else "No se pudo quitar el archivo."
                }
            }) }
        )
    }
}

@Composable
private fun ProjectCard(item: ModrinthItem, job: WorkInfo?, enabled: Boolean, onSave: () -> Unit) {
    Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Biomeria.colors.surfaceElevated, ControlShape), contentAlignment = Alignment.Center) {
                if (item.iconUrl != null) {
                    AsyncImage(item.iconUrl, null, Modifier.fillMaxSize().clip(ControlShape))
                } else Icon(Icons.Default.Build, null, Modifier.size(18.dp), tint = Biomeria.colors.netP2P)
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(item.author, maxLines = 1, color = Biomeria.colors.textMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(item.description, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = Biomeria.colors.textMuted)
        if (job != null) WorkshopJobProgress(job)
        if (job?.state?.isFinished != false) {
            val action = when {
                job?.state == WorkInfo.State.FAILED -> "Reintentar"
                item.category == WorkshopCategory.MODS -> "Comprobar"
                else -> "Descargar"
            }
            LauncherButton(action, onSave, enabled = enabled)
        }
    }
}

@Composable
private fun cardModifier() = Modifier.fillMaxWidth().background(Biomeria.colors.surfaceCard, PanelShape)
    .border(1.dp, Biomeria.colors.borderSubtle, PanelShape).padding(12.dp)

@Composable
private fun EmptyResults(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = Biomeria.colors.textMuted)
    }
}
