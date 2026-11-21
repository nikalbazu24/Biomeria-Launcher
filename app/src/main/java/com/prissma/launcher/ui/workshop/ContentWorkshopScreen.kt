package com.prissma.launcher.ui.workshop

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
import coil.compose.AsyncImage
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.data.InstalledFileItem
import com.prissma.launcher.data.ModrinthItem
import com.prissma.launcher.data.ModrinthRepository
import com.prissma.launcher.data.WorkshopCategory
import com.prissma.launcher.ui.components.ControlShape
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PageHeader
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.theme.Prissma
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
fun ContentWorkshopScreen(instance: GameInstance, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val repository = remember(context) { ModrinthRepository(context) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val loader = instance.configuredModLoader

    var tab by rememberSaveable(instance.id) {
        mutableStateOf(if (loader == null) WorkshopTab.PACKS else WorkshopTab.MODS)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<ModrinthItem>>(emptyList()) }
    var installed by remember { mutableStateOf<List<InstalledFileItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var savingId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmRemoval by remember { mutableStateOf<InstalledFileItem?>(null) }

    fun search() {
        focus.clearFocus()
        submitted = query.trim()
        reload += 1
    }

    LaunchedEffect(tab, submitted, reload, instance.id) {
        status = null
        val category = tab.category
        if (category == null) {
            installed = withContext(Dispatchers.IO) { repository.getInstalledFiles(instance.id) }
            loading = false
        } else {
            results = emptyList()
            loading = true
            repository.searchProjects(submitted, category, instance.version, loader)
                .onSuccess { results = it }
                .onFailure { status = it.message ?: "No se pudo consultar Modrinth." }
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Prissma.colors.surfaceVoid).safeDrawingPadding().padding(14.dp)
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
        PageHeader("Taller", instance.title + " · Minecraft " + instance.version + " · Modrinth", onBack)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            WorkshopTab.entries.forEach {
                val choose = { tab = it }
                FilterChip(tab == it, choose, { Text(it.label) }, modifier = Modifier.gamepadClick(onClick = choose))
            }
        }
        if (tab.category != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f),
                    placeholder = { Text("Buscar en " + tab.label.lowercase()) }, singleLine = true,
                    shape = ControlShape, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }))
                LauncherButton("Buscar", ::search, icon = Icons.Default.Search, enabled = !loading)
            }
            if (tab == WorkshopTab.MODS) {
                Text(
                    if (loader == null) "Los mods requieren un cargador configurado."
                    else "Archivos para " + loader + ". El arranque integrado aún usa Minecraft vanilla.",
                    style = MaterialTheme.typography.bodySmall, color = Prissma.colors.textMuted
                )
            }
        }
        status?.let { Text(it, color = Prissma.colors.netP2P, style = MaterialTheme.typography.bodySmall) }

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
                        Text(it.categoryLabel + " · " + it.sizeKb + " KB", color = Prissma.colors.textMuted,
                            style = MaterialTheme.typography.bodySmall)
                        LauncherButton("Quitar archivo", { confirmRemoval = it })
                    }
                }
            }
            results.isEmpty() -> EmptyResults("Sin resultados compatibles con Minecraft " + instance.version + ".")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(245.dp), modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(results, key = { it.projectId }) { item ->
                    ProjectCard(
                        item, downloading = savingId == item.projectId,
                        enabled = savingId == null && (item.category != WorkshopCategory.MODS || loader != null)
                    ) {
                        savingId = item.projectId
                        scope.launch {
                            repository.downloadAndInstall(item, instance.id, instance.version, loader)
                                .onSuccess { status = "Guardado: " + it.name }
                                .onFailure { status = it.message ?: "No se pudo guardar el archivo." }
                            savingId = null
                            if (tab == WorkshopTab.INSTALLED) {
                                installed = withContext(Dispatchers.IO) { repository.getInstalledFiles(instance.id) }
                            }
                        }
                    }
                }
            }
        }
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
                    installed = withContext(Dispatchers.IO) { repository.getInstalledFiles(instance.id) }
                    status = if (removed) "Archivo quitado." else "No se pudo quitar el archivo."
                }
            }) }
        )
    }
}

@Composable
private fun ProjectCard(item: ModrinthItem, downloading: Boolean, enabled: Boolean, onSave: () -> Unit) {
    Column(cardModifier(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Prissma.colors.surfaceElevated, ControlShape), contentAlignment = Alignment.Center) {
                if (item.iconUrl != null) {
                    AsyncImage(item.iconUrl, null, Modifier.fillMaxSize().clip(ControlShape))
                } else Icon(Icons.Default.Build, null, Modifier.size(18.dp), tint = Prissma.colors.netP2P)
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(item.author, maxLines = 1, color = Prissma.colors.textMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(item.description, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = Prissma.colors.textMuted)
        LauncherButton(if (downloading) "Guardando…" else "Guardar", onSave, enabled = enabled)
    }
}

@Composable
private fun cardModifier() = Modifier.fillMaxWidth().background(Prissma.colors.surfaceCard, PanelShape)
    .border(1.dp, Prissma.colors.borderSubtle, PanelShape).padding(12.dp)

@Composable
private fun EmptyResults(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = Prissma.colors.textMuted)
    }
}
