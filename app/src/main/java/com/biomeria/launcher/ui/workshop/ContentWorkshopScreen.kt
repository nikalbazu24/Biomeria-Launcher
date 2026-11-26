package com.biomeria.launcher.ui.workshop

import com.biomeria.launcher.ui.components.BiomeriaDialog
import com.biomeria.launcher.ui.components.BiomeriaTextField
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
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.theme.PixelFont
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
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

private enum class WorkshopTab(val label: String, val detail: String, val category: WorkshopCategory?) {
    MODS("Mods", "Cambian el juego", WorkshopCategory.MODS),
    SHADERS("Shaders", "Luz y sombras", WorkshopCategory.SHADERS),
    TEXTURES("Texturas", "Cómo se ve", WorkshopCategory.RESOURCE_PACKS),
    MODPACKS("Modpacks", "Todo en uno", null),
    INSTALLED("Guardados", "", null)
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
        mutableStateOf(if (loader == null) WorkshopTab.TEXTURES else WorkshopTab.MODS)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var includeOtherMods by rememberSaveable(instance.id) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<ModrinthItem>>(emptyList()) }
    var installed by remember { mutableStateOf<List<InstalledFileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var previewItem by remember { mutableStateOf<ModrinthItem?>(null) }
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                PageHeader("Taller", "${instance.title} · Minecraft ${instance.version} · ${instance.loader}", onBack)
            }
            LauncherButton(workshopDownloadsLabel(jobs), { showDownloads = true }, primary = activeJobs.isNotEmpty())
            LauncherButton("¿Cómo funciona?", { showTutorial = true })
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkshopTab.entries.forEach { item ->
                    val selected = tab == item
                    BlockButton({ tab = item }, Modifier.fillMaxWidth(),
                        if (selected) BlockButtons.primary else BlockButtons.neutral,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(item.label, fontFamily = PixelFont, fontSize = 18.sp, maxLines = 1)
                            val detail = if (item == WorkshopTab.INSTALLED) "${installed.size} en este mundo" else item.detail
                            Text(detail, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                color = LocalContentColor.current.copy(alpha = .75f))
                        }
                    }
                }
                Text("Solo ves lo que funciona con ${instance.version} y ${workshopModLoaderName(loader) ?: instance.loader}. Los botones te avisan si falta algo.",
                    Modifier.fillMaxWidth().background(Biomeria.colors.surfaceCard, PanelShape).padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = Biomeria.colors.textMuted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tab.category != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        BiomeriaTextField(query, { query = it }, Modifier.weight(1f),
                            placeholder = "Buscar en " + tab.label.lowercase(), singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }))
                        LauncherButton("Buscar", ::search, icon = Icons.Default.Search, enabled = !loading)
                        if (tab == WorkshopTab.MODS && supportsMods) {
                            BlockButton({ includeOtherMods = !includeOtherMods },
                                colors = if (includeOtherMods) BlockButtons.gold else BlockButtons.neutral) {
                                Text("Mostrar otras versiones", fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            }
                        }
                    }
                    if (tab == WorkshopTab.MODS && !supportsMods) {
                        Text("Selecciona Fabric o NeoForge en la instancia para instalar mods.",
                            style = MaterialTheme.typography.bodySmall, color = Biomeria.colors.textMuted)
                    }
                }
                packError?.let { Text(it, color = Biomeria.colors.error, style = MaterialTheme.typography.bodySmall) }
                status?.let { Text(it, color = Biomeria.colors.netP2P, style = MaterialTheme.typography.bodySmall) }

                when {
                    tab == WorkshopTab.MODPACKS -> Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Modpacks", fontFamily = PixelFont, fontSize = 22.sp)
                        Text("Un modpack trae mods, ajustes y la versión lista. Importa uno desde tu consola.",
                            color = Biomeria.colors.textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LauncherButton(if (importingPack) "Leyendo modpack…" else "Importar modpack", onImportPack,
                                primary = true, enabled = !importingPack)
                            LauncherButton("Descargas de modpacks", onPackDownloads)
                        }
                    }
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                    tab == WorkshopTab.INSTALLED && installed.isEmpty() -> {
                        EmptyResults("Todavía no hay archivos guardados en este mundo.")
                    }
                    tab == WorkshopTab.INSTALLED -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(260.dp), modifier = Modifier.fillMaxSize(),
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
                        columns = GridCells.Adaptive(320.dp), modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(results, key = { it.projectId }) { item ->
                            val job = latestJobs.firstOrNull { it.workshopProject == item.projectId || item.projectId in it.tags }
                            ProjectCard(
                                item, job,
                                enabled = job?.state?.isFinished != false && (item.category != WorkshopCategory.MODS || supportsMods),
                                compatible = !(tab == WorkshopTab.MODS && includeOtherMods),
                                onPreview = if (item.category == WorkshopCategory.SHADERS && item.gallery.isNotEmpty()) {
                                    { previewItem = item }
                                } else null
                            ) {
                                if (item.category == WorkshopCategory.MODS) checkMod = item
                                else downloads.mod(instance, item.projectId, item.title, item.category)
                            }
                        }
                    }
                }
            }
        }
    }

    previewItem?.let { item ->
        ShaderPreviewDialog(item, onDismiss = { previewItem = null }) {
            previewItem = null
            downloads.mod(instance, item.projectId, item.title, item.category)
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
        BiomeriaDialog(
            onDismissRequest = { showTutorial = false }, accent = Biomeria.colors.netP2P, chip = "Ayuda",
            title = { Text("Agregar mods") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Crea una instancia Java, elige la versión de Minecraft y selecciona Fabric o NeoForge.")
                    Text("2. Abre el Taller de esa instancia, entra en Mods y busca lo que quieres instalar.")
                    Text("3. Pulsa Instalar. Revisa qué versión del mod y qué dependencias se instalarán, y confirma la descarga.")
                    Text("4. Revisa los archivos en Guardados. Cuando termine la descarga, vuelve a la instancia y pulsa Jugar.")
                }
            },
            confirmButton = { LauncherButton("Entendido", { showTutorial = false }, primary = true) }
        )
    }

    confirmRemoval?.let { target ->
        BiomeriaDialog(
            onDismissRequest = { confirmRemoval = null }, accent = Biomeria.colors.engineRust, chip = "Cuidado",
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

private fun formatDownloads(count: Int): String = when {
    count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f M", count / 1_000_000f).replace('.', ',')
    count >= 1_000 -> "${count / 1_000} mil"
    else -> count.toString()
}

@Composable
private fun ProjectCard(
    item: ModrinthItem, job: WorkInfo?, enabled: Boolean, compatible: Boolean,
    onPreview: (() -> Unit)?, onSave: () -> Unit
) {
    Row(cardModifier(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).background(Biomeria.colors.surfaceElevated, ControlShape)
            .border(2.dp, Biomeria.colors.ink, ControlShape), contentAlignment = Alignment.Center) {
            if (item.iconUrl != null) {
                AsyncImage(item.iconUrl, null, Modifier.fillMaxSize().clip(ControlShape))
            } else Icon(Icons.Default.Build, null, Modifier.size(24.dp), tint = Biomeria.colors.netP2P)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontFamily = PixelFont, fontSize = 17.sp)
                if (compatible) Text("Compatible", Modifier.background(Biomeria.colors.brandAccent, ControlShape)
                    .padding(horizontal = 7.dp, vertical = 1.dp), color = Biomeria.colors.ink,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Text(item.author + " · " + formatDownloads(item.downloads) + " descargas", maxLines = 1,
                color = Biomeria.colors.textMuted, style = MaterialTheme.typography.labelMedium)
            Text(item.description, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = Biomeria.colors.textMuted)
            if (job != null) WorkshopJobProgress(job)
            if (job?.state?.isFinished != false) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (onPreview != null) BlockButton(onPreview, colors = BlockButtons.gold) {
                        Text("Vista previa", fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                    LauncherButton(if (job?.state == WorkInfo.State.FAILED) "Reintentar" else "Instalar", onSave,
                        primary = true, enabled = enabled)
                }
            }
        }
    }
}

/** Pictures the author published for the shader, with buttons to move between them. */
@Composable
private fun ShaderPreviewDialog(item: ModrinthItem, onDismiss: () -> Unit, onInstall: () -> Unit) {
    var index by remember(item.projectId) { mutableIntStateOf(0) }
    val last = item.gallery.lastIndex
    BiomeriaDialog(
        onDismissRequest = onDismiss, accent = Biomeria.colors.gold, chip = "Shader", width = 760.dp,
        title = { Text("Vista previa · " + item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(280.dp).background(Biomeria.colors.ink, ControlShape)
                    .border(2.dp, Biomeria.colors.borderSubtle, ControlShape), contentAlignment = Alignment.Center) {
                    AsyncImage(item.gallery[index.coerceIn(0, last)], item.title, Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LauncherButton("Anterior", { index = if (index == 0) last else index - 1 }, enabled = last > 0)
                    Text("${index + 1} de ${last + 1}", Modifier.weight(1f), color = Biomeria.colors.textMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    LauncherButton("Siguiente", { index = if (index == last) 0 else index + 1 }, enabled = last > 0)
                }
                Text("Son imágenes que publicó el autor. En tu consola puede verse distinto según el rendimiento.",
                    style = MaterialTheme.typography.bodySmall, color = Biomeria.colors.textMuted)
            }
        },
        dismissButton = { LauncherButton("Cerrar", onDismiss) },
        confirmButton = { LauncherButton("Instalar", onInstall, primary = true) }
    )
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
