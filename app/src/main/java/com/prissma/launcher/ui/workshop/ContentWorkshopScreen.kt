package com.prissma.launcher.ui.workshop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.prissma.launcher.data.InstalledFileItem
import com.prissma.launcher.data.ModrinthItem
import com.prissma.launcher.data.ModrinthRepository
import com.prissma.launcher.data.WorkshopCategory
import com.prissma.launcher.ui.hub.EngineType
import com.prissma.launcher.ui.hub.GameInstance
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.launch

private enum class WorkshopTab(val label: String, val category: WorkshopCategory?) {
    MODS("Mods", WorkshopCategory.MODS),
    SHADERS("Shaders", WorkshopCategory.SHADERS),
    PACKS("Resource Packs", WorkshopCategory.RESOURCE_PACKS),
    INSTALLED("Instalados", null)
}

@Composable
fun ContentWorkshopScreen(
    instance: GameInstance,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val repository = remember { ModrinthRepository(context) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val accentColor = Prissma.colors.engineColor(instance.engine.name)

    // Si la instancia es Rust o Bedrock, arrancamos en Resource Packs (compatibles)
    var selectedTab by remember {
        mutableStateOf(
            if (instance.engine == EngineType.JAVA) WorkshopTab.MODS else WorkshopTab.PACKS
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ModrinthItem>>(emptyList()) }
    var installedFiles by remember {
        mutableStateOf(repository.getInstalledFiles(instance.id))
    }

    var isLoading by remember { mutableStateOf(false) }
    var downloadingProjectId by remember { mutableStateOf<String?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    fun refreshInstalled() {
        installedFiles = repository.getInstalledFiles(instance.id)
    }

    fun runSearch() {
        val cat = selectedTab.category ?: return
        scope.launch {
            isLoading = true
            statusBanner = null
            repository.searchProjects(searchQuery, cat)
                .onSuccess { list -> searchResults = list }
                .onFailure { err ->
                    statusBanner = "No se pudo conectar a Modrinth: ${err.message}"
                }
            isLoading = false
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == WorkshopTab.INSTALLED) {
            refreshInstalled()
        } else {
            runSearch()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Prissma.colors.surfaceVoid)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ButtonB, Key.Escape -> {
                        onBack()
                        true
                    }
                    Key.ButtonL1 -> {
                        val tabs = WorkshopTab.entries
                        val prev = (tabs.indexOf(selectedTab) - 1 + tabs.size) % tabs.size
                        selectedTab = tabs[prev]
                        true
                    }
                    Key.ButtonR1 -> {
                        val tabs = WorkshopTab.entries
                        val next = (tabs.indexOf(selectedTab) + 1) % tabs.size
                        selectedTab = tabs[next]
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        // 1. Cabecera con botón Volver, título de instancia y pestañas
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Prissma.colors.surfaceCard)
                        .border(1.dp, Prissma.colors.borderSubtle, CircleShape)
                        .clickable(onClick = onBack)
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "← Volver [B]",
                        color = Prissma.colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column {
                    Text(
                        text = "Taller Modrinth · ${instance.title}",
                        color = Prissma.colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Motor ${instance.engine.displayName} · Versión ${instance.version}",
                        color = accentColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Pestañas de categoría [L1 / R1]
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Prissma.colors.surfaceCard)
                    .border(1.dp, Prissma.colors.borderSubtle, CircleShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WorkshopTab.entries.forEach { tab ->
                    val active = tab == selectedTab
                    val labelText = if (tab == WorkshopTab.INSTALLED) {
                        "${tab.label} (${installedFiles.size})"
                    } else {
                        tab.label
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (active) Prissma.colors.surfaceElevated else Color.Transparent
                            )
                            .clickable { selectedTab = tab }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = labelText,
                            color = if (active) accentColor else Prissma.colors.textMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Aviso informativo si busca mods Java desde una instancia Rust o Bedrock
        if (instance.engine != EngineType.JAVA &&
            (selectedTab == WorkshopTab.MODS || selectedTab == WorkshopTab.SHADERS)
        ) {
            Text(
                text = "Nota: Modrinth aloja mods y shaders de Java. Igual puedes descargarlos para probar.",
                color = Prissma.colors.engineJava,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Prissma.colors.surfaceCard, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 2. Barra de búsqueda (visible en Mods, Shaders y Resource Packs)
        if (selectedTab != WorkshopTab.INSTALLED) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Buscar ${selectedTab.label.lowercase()} en Modrinth (ej. Sodium, BSL, Iris)...",
                            color = Prissma.colors.textMuted,
                            fontSize = 13.sp
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        runSearch()
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Prissma.colors.borderSubtle,
                        focusedTextColor = Prissma.colors.textPrimary,
                        unfocusedTextColor = Prissma.colors.textPrimary,
                        focusedContainerColor = Prissma.colors.surfaceCard,
                        unfocusedContainerColor = Prissma.colors.surfaceCard
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        runSearch()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(52.dp)
                ) {
                    Text(
                        text = "Buscar",
                        color = Prissma.colors.surfaceVoid,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Aviso de estado de descarga o error
        statusBanner?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Prissma.colors.surfaceElevated, RoundedCornerShape(8.dp))
                    .border(1.dp, Prissma.colors.brandAccent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    text = msg,
                    color = Prissma.colors.textPrimary,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 3. Lista principal de resultados o archivos instalados
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                selectedTab == WorkshopTab.INSTALLED -> {
                    InstalledList(
                        files = installedFiles,
                        onDelete = { fileItem ->
                            if (repository.deleteInstalledFile(fileItem)) {
                                refreshInstalled()
                                statusBanner = "Eliminado: ${fileItem.fileName}"
                            }
                        }
                    )
                }
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accentColor)
                    }
                }
                searchResults.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No se encontraron resultados. Prueba con otro nombre.",
                            color = Prissma.colors.textMuted
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(searchResults, key = { it.projectId }) { item ->
                            ModrinthResultCard(
                                item = item,
                                accentColor = accentColor,
                                isDownloading = downloadingProjectId == item.projectId,
                                onInstall = {
                                    scope.launch {
                                        downloadingProjectId = item.projectId
                                        statusBanner = "Descargando ${item.title}..."
                                        repository.downloadAndInstall(
                                            item = item,
                                            instanceId = instance.id,
                                            preferredMcVersion = instance.version
                                        ).onSuccess { savedFile ->
                                            refreshInstalled()
                                            statusBanner = "Instalado: ${savedFile.name} en /${item.category.folderName}"
                                        }.onFailure { err ->
                                            statusBanner = "Error instalando ${item.title}: ${err.message}"
                                        }
                                        downloadingProjectId = null
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModrinthResultCard(
    item: ModrinthItem,
    accentColor: Color,
    isDownloading: Boolean,
    onInstall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Prissma.colors.surfaceCard)
            .border(1.dp, Prissma.colors.borderSubtle, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Icono del proyecto desde Modrinth
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Prissma.colors.surfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            if (item.iconUrl != null) {
                AsyncImage(
                    model = item.iconUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = item.title.take(2).uppercase(),
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Título, autor y descripción
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.title,
                    color = Prissma.colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "por ${item.author}",
                    color = Prissma.colors.textMuted,
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = item.description,
                color = Prissma.colors.textMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Botón Instalar
        Button(
            onClick = onInstall,
            enabled = !isDownloading,
            colors = ButtonDefaults.buttonColors(
                containerColor = Prissma.colors.brandAccent,
                contentColor = Prissma.colors.surfaceVoid
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Prissma.colors.surfaceVoid
                )
            } else {
                Text(
                    text = "Instalar",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun InstalledList(
    files: List<InstalledFileItem>,
    onDelete: (InstalledFileItem) -> Unit
) {
    if (files.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Aún no has descargado mods ni shaders para esta instancia.",
                color = Prissma.colors.textMuted,
                fontSize = 14.sp
            )
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        items(files, key = { it.file.absolutePath }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Prissma.colors.surfaceCard)
                    .border(1.dp, Prissma.colors.borderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        color = Prissma.colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.categoryLabel} · ${item.sizeKb} KB",
                        color = Prissma.colors.brandAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                OutlinedButton(
                    onClick = { onDelete(item) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Quitar",
                        color = Color(0xFFF87171),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
