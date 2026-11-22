package com.prissma.launcher.ui.hub

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prissma.launcher.data.AppUpdater
import com.prissma.launcher.data.EngineType
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.data.InstanceStorage
import com.prissma.launcher.data.LibraryPreferences
import com.prissma.launcher.data.UpdateInfo
import com.prissma.launcher.engine.bedrock.BedrockInstallation
import com.prissma.launcher.engine.bedrock.BedrockLauncher
import com.prissma.launcher.engine.java.GameDownloads
import com.prissma.launcher.engine.java.MojangDownloader
import com.prissma.launcher.engine.java.supportsJavaLoader
import com.prissma.launcher.engine.rust.StarlingInstallation
import com.prissma.launcher.engine.rust.StarlingLauncher
import com.prissma.launcher.ui.components.ControlShape
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PrissmaMark
import com.prissma.launcher.ui.components.ReleaseNotesDialog
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.launch.JavaLaunchScreen
import com.prissma.launcher.ui.theme.Prissma
import com.prissma.launcher.ui.workshop.BedrockWorkshopScreen
import com.prissma.launcher.ui.workshop.ContentWorkshopScreen
import com.prissma.launcher.ui.workshop.StarlingWorkshopScreen
import com.prissma.launcher.ui.workshop.WorkshopDownloadsDialog
import com.prissma.launcher.ui.workshop.workshopDownloadsLabel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class HubPage(val label: String, val icon: ImageVector) {
    LIBRARY("Biblioteca", Icons.Default.Home),
    WORKSHOP("Taller", Icons.Default.Build),
    CONNECTIONS("Conexiones", Icons.Default.Share),
    SETTINGS("Ajustes", Icons.Default.Settings)
}

@Composable
internal fun HubScreen(
    bedrock: BedrockInstallation?,
    bedrockLauncher: BedrockLauncher,
    onRefreshBedrock: () -> Unit,
    onOpenBedrock: () -> String?,
    starling: StarlingInstallation?,
    starlingLauncher: StarlingLauncher,
    starlingReport: Uri?,
    onOpenStarling: () -> String?,
    onConsumeStarlingReport: (Uri) -> Unit,
    externalLaunchInstanceId: String? = null,
    updateSettingsRequest: Int = 0,
    sourceFrontendPackage: String? = null,
    onConsumeExternalLaunch: () -> Unit = {}
) {
    val context = LocalContext.current
    val storage = remember(context) { InstanceStorage(context) }
    val downloads = remember(context) { GameDownloads(context) }
    val preferences = remember(context) { LibraryPreferences(context) }
    val updater = remember(context) { AppUpdater(context) }
    val version = remember(updater) { updater.getCurrentVersionName() }
    val scope = rememberCoroutineScope()
    val notices = remember { SnackbarHostState() }

    var instances by remember(storage) { mutableStateOf(storage.loadInstances()) }
    val workshopJobs by remember(downloads, instances) { downloads.workshopDownloads(instances) }.collectAsState(emptyList())
    var showWorkshopDownloads by rememberSaveable { mutableStateOf(false) }
    var profile by remember(storage) { mutableStateOf(storage.loadProfile()) }
    var favorites by remember(preferences) { mutableStateOf(preferences.favorites()) }
    var recent by remember(preferences) { mutableStateOf(preferences.recent()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf<EngineType?>(null) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(LibraryOrder.RECENT) }
    var page by rememberSaveable { mutableStateOf(HubPage.LIBRARY) }

    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showBedrockApps by rememberSaveable { mutableStateOf(false) }
    var showStarlingDiagnostics by rememberSaveable { mutableStateOf(false) }
    var savingProfile by remember { mutableStateOf(false) }
    var showNew by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var launchingId by rememberSaveable { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var installedNotes by remember(updater, version) { mutableStateOf<String?>(null) }
    var releaseNotesSeen by remember(updater, version) { mutableStateOf(updater.hasSeenReleaseNotes(version)) }

    fun acknowledgeReleaseNotes() {
        updater.markReleaseNotesSeen(version)
        releaseNotesSeen = true
    }

    val visible = remember(instances, filter, query, favorites, favoritesOnly, recent, order) {
        visibleLibrary(instances, filter, query, favorites, favoritesOnly, recent, order)
    }
    val selected = librarySelection(visible, selectedId)
    val workshopInstance = selected ?: librarySelection(instances, selectedId)

    fun notify(message: String) {
        scope.launch {
            notices.currentSnackbarData?.dismiss()
            notices.showSnackbar(message, withDismissAction = true)
        }
    }

    fun saveCatalog(updated: List<GameInstance>): Boolean {
        return runCatching { storage.saveInstances(updated) }
            .onSuccess { instances = updated }
            .onFailure { notify("No se pudo guardar la biblioteca.") }
            .isSuccess
    }

    fun launchInstance(instance: GameInstance) {
        when (instance.engine) {
            EngineType.JAVA -> launchingId = instance.id
            EngineType.BEDROCK -> {
                launchingId = null
                val problem = onOpenBedrock()
                if (problem != null) {
                    if (bedrock?.canOpen != true) showBedrockApps = true
                    notify(problem)
                    return
                }
            }
            EngineType.RUST -> {
                launchingId = null
                val problem = onOpenStarling()
                if (problem != null) {
                    showStarlingDiagnostics = true
                    notify(problem)
                    return
                }
            }
        }
        recent = (listOf(instance.id) + recent.filterNot { it == instance.id }).take(24)
        preferences.setRecent(recent)
    }

    LaunchedEffect(updateSettingsRequest) {
        if (updateSettingsRequest != 0) {
            launchingId = null
            page = HubPage.SETTINGS
        }
    }
    LaunchedEffect(downloads) {
        val sample = instances.firstOrNull { it.id == "survival-mods-java" }
        if (sample != null && withContext(Dispatchers.IO) {
            MojangDownloader(context).prepared(sample.id, sample.version, sample.loader) == null
        }) downloads.game(sample)
    }
    LaunchedEffect(updater) { update = updater.checkForUpdate().getOrNull() }
    LaunchedEffect(updater, version) { installedNotes = updater.installedReleaseNotes(version) }
    LaunchedEffect(starlingReport) {
        if (starlingReport != null) {
            launchingId = null
            page = HubPage.LIBRARY
            showStarlingDiagnostics = true
        }
    }
    LaunchedEffect(externalLaunchInstanceId) {
        val id = externalLaunchInstanceId ?: return@LaunchedEffect
        page = HubPage.LIBRARY
        filter = null
        query = ""
        favoritesOnly = false
        val instance = instances.firstOrNull { it.id == id }
        if (instance != null) {
            selectedId = instance.id
            launchInstance(instance)
        } else notify("El cartucho apunta a una instancia que no está en la biblioteca.")
        onConsumeExternalLaunch()
    }

    instances.firstOrNull { it.id == launchingId && it.engine == EngineType.JAVA }?.let {
        JavaLaunchScreen(it, profile.playerName, profile.accountType, profile.language, onBack = { launchingId = null })
        return
    }

    val showWorkshop = page == HubPage.WORKSHOP && workshopInstance != null
    BackHandler(enabled = page != HubPage.LIBRARY && !showWorkshop) { page = HubPage.LIBRARY }
    if (showWorkshop) {
        val back = { page = HubPage.LIBRARY }
        when (workshopInstance.engine) {
            EngineType.JAVA -> ContentWorkshopScreen(instance = workshopInstance, onBack = back)
            EngineType.BEDROCK -> key(workshopInstance.id) {
                BedrockWorkshopScreen(workshopInstance, bedrock, bedrockLauncher, onRefreshBedrock, back)
            }
            EngineType.RUST -> StarlingWorkshopScreen(workshopInstance.title, starling, starlingLauncher, back)
        }
        return
    }

    Scaffold(
        containerColor = Prissma.colors.surfaceVoid,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(notices) }
    ) { padding ->
        HubFrame(
            page = page,
            onPage = { page = it },
            playerName = profile.label,
            playerAvatar = storage.profileAvatar(profile),
            updateAvailable = update != null,
            onProfile = { showAccount = true },
            downloadsLabel = workshopDownloadsLabel(workshopJobs),
            onDownloads = { showWorkshopDownloads = true },
            modifier = Modifier.fillMaxSize().padding(padding).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when {
                    event.key == Key.ButtonB && page != HubPage.LIBRARY -> {
                        page = HubPage.LIBRARY
                        true
                    }
                    event.key == Key.ButtonStart -> {
                        page = HubPage.CONNECTIONS
                        true
                    }
                    page == HubPage.LIBRARY &&
                        (event.key == Key.ButtonL1 || event.key == Key.ButtonR1) -> {
                        val filters = listOf(null) + EngineType.entries
                        val offset = if (event.key == Key.ButtonL1) -1 else 1
                        filter = filters[(filters.indexOf(filter) + filters.size + offset) % filters.size]
                        true
                    }
                    page == HubPage.LIBRARY && event.key == Key.ButtonX && selected != null -> {
                        page = HubPage.WORKSHOP
                        true
                    }
                    else -> false
                }
            }
        ) {
            when (page) {
                HubPage.LIBRARY -> LibraryPage(
                    visible, selected, favorites, filter, favoritesOnly, order, query, bedrock, starling,
                    onQuery = { query = it }, onFilter = { filter = it },
                    onFavoritesFilter = { favoritesOnly = !favoritesOnly },
                    onOrder = { order = if (order == LibraryOrder.RECENT) LibraryOrder.NAME else LibraryOrder.RECENT },
                    onSelect = { selectedId = it.id },
                    onNew = { showNew = true },
                    onPlay = { selected?.let(::launchInstance) },
                    onBedrockApps = { showBedrockApps = true },
                    onStarlingDiagnostics = { showStarlingDiagnostics = true },
                    onWorkshop = { page = HubPage.WORKSHOP },
                    onEdit = { editingId = selected?.id },
                    onFavorite = {
                        selected?.let {
                            favorites = if (it.id in favorites) favorites - it.id else favorites + it.id
                            preferences.setFavorites(favorites)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                HubPage.CONNECTIONS -> ConnectionsPage(
                    storage, instances, librarySelection(instances, selectedId), sourceFrontendPackage,
                    onSelect = { selectedId = it.id }, onNotice = ::notify
                )
                HubPage.SETTINGS -> SettingsPage(
                    updater, version, update, installedNotes, ::acknowledgeReleaseNotes, { update = it }, ::notify
                )
                HubPage.WORKSHOP -> EmptyWorkshop { showNew = true }
            }
        }
    }

    if (showWorkshopDownloads) WorkshopDownloadsDialog(workshopJobs, instances, downloads) { showWorkshopDownloads = false }

    if (showBedrockApps) {
        BedrockAppsDialog(bedrock, bedrockLauncher, onRefreshBedrock, onDismiss = { showBedrockApps = false })
    }
    if (showStarlingDiagnostics) {
        StarlingDiagnosticsDialog(starling, starlingLauncher, starlingReport, onConsumeStarlingReport) {
            showStarlingDiagnostics = false
        }
    }

    if (!showWorkshopDownloads && !showBedrockApps && !showStarlingDiagnostics && starlingReport == null &&
        !releaseNotesSeen && page == HubPage.LIBRARY && externalLaunchInstanceId == null &&
        updateSettingsRequest == 0 && !showAccount && !showNew && editingId == null && deleteId == null
    ) {
        installedNotes?.let { ReleaseNotesDialog(version, it, ::acknowledgeReleaseNotes) }
    }

    if (showAccount) ProfileDialog(profile, storage.profileAvatar(profile), { showAccount = false }, savingProfile) { updated, photo ->
        savingProfile = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { storage.saveProfile(updated, photo) } }
                .onSuccess { profile = it; showAccount = false }
                .onFailure { notify(it.message ?: "No se pudo guardar el perfil.") }
            savingProfile = false
        }
    }

    if (showNew) InstanceEditor(onDismiss = { showNew = false }) { title, engine, gameVersion, loader ->
        val item = GameInstance(title = title, engine = engine, version = gameVersion, loader = loader)
        if (saveCatalog(listOf(item) + instances)) {
            if (item.engine == EngineType.JAVA && supportsJavaLoader(item.version, item.loader)) downloads.game(item)
            selectedId = item.id
            filter = null
            query = ""
            favoritesOnly = false
            page = HubPage.LIBRARY
            showNew = false
        }
    }

    instances.firstOrNull { it.id == editingId }?.let { item ->
        InstanceEditor(
            instance = item,
            onDismiss = { editingId = null },
            onRemove = { deleteId = item.id; editingId = null }
        ) { title, _, _, loader ->
            val edited = item.copy(title = title, loader = loader)
            if (saveCatalog(instances.map { if (it.id == item.id) edited else it })) {
                if (edited.engine == EngineType.JAVA && supportsJavaLoader(edited.version, edited.loader) && edited.loader != item.loader) {
                    downloads.game(edited)
                }
                editingId = null
                query = ""
            }
        }
    }

    instances.firstOrNull { it.id == deleteId }?.let { item ->
        RemoveInstanceDialog(item, onDismiss = { deleteId = null }) {
            if (saveCatalog(instances.filterNot { it.id == item.id })) {
                favorites = favorites - item.id
                recent = recent.filterNot { it == item.id }
                preferences.setFavorites(favorites)
                preferences.setRecent(recent)
                deleteId = null
                selectedId = null
            }
        }
    }
}

@Composable
private fun HubFrame(
    page: HubPage,
    onPage: (HubPage) -> Unit,
    playerName: String,
    playerAvatar: File?,
    updateAvailable: Boolean,
    onProfile: () -> Unit,
    downloadsLabel: String,
    onDownloads: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }
    BoxWithConstraints(modifier) {
        val landscape = maxWidth >= 585.dp && maxHeight >= 275.dp
        val navigation: @Composable () -> Unit = {
            HubPage.entries.forEach { item ->
                val action = { onPage(item) }
                NavigationItem(
                    item, page == item, updateAvailable && item == HubPage.SETTINGS, action,
                    modifier = if (item == HubPage.LIBRARY) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
        val mainPanel: @Composable (Modifier) -> Unit = { panelModifier ->
            Column(panelModifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(page.label, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    LauncherButton(downloadsLabel, onDownloads)
                    OutlinedButton(onProfile, Modifier.widthIn(max = 170.dp).gamepadClick(onClick = onProfile),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                        ProfileAvatar(playerName, playerAvatar, Modifier.size(25.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(playerName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxHeight().width(84.dp).background(Prissma.colors.surfaceCard)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    PrissmaMark(Modifier.size(36.dp))
                    Text("PRISSMA", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(3.dp))
                    navigation()
                }
                mainPanel(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                mainPanel(Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().background(Prissma.colors.surfaceCard).padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) { navigation() }
            }
        }
    }
}

@Composable
private fun NavigationItem(page: HubPage, selected: Boolean, update: Boolean, onClick: () -> Unit,
                           modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val focus by source.collectIsFocusedAsState()
    val tint = if (selected || focus) Prissma.colors.brandAccent else Prissma.colors.textMuted
    Column(
        modifier.widthIn(min = 65.dp).clip(ControlShape)
            .background(if (selected || focus) Prissma.colors.surfaceElevated else Color.Transparent)
            .border(1.dp, if (focus) tint else Color.Transparent, ControlShape)
            .gamepadClick(onClick = onClick)
            .selectable(selected, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .heightIn(min = 43.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BadgedBox(badge = { if (update) Badge() }) { Icon(page.icon, null, Modifier.size(17.dp), tint = tint) }
        Text(page.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = tint)
    }
}

@Composable
private fun EmptyWorkshop(onCreate: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Crea una instancia Java para abrir el taller.", color = Prissma.colors.textMuted)
        Spacer(Modifier.height(10.dp))
        LauncherButton("Nueva instancia", onCreate, primary = true)
    }
}
