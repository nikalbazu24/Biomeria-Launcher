package com.prissma.launcher.ui.hub

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import com.prissma.launcher.ui.theme.PixelFont
import androidx.work.WorkInfo
import com.prissma.launcher.data.AppUpdater
import com.prissma.launcher.data.EngineType
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.data.InstanceStorage
import com.prissma.launcher.data.LibraryPreferences
import com.prissma.launcher.data.ModpackImports
import com.prissma.launcher.data.UpdateInfo
import com.prissma.launcher.engine.bedrock.BedrockInstallation
import com.prissma.launcher.engine.bedrock.BedrockLauncher
import com.prissma.launcher.engine.java.GameDownloads
import com.prissma.launcher.engine.java.MODPACK_IMPORT_TAG
import com.prissma.launcher.engine.java.MojangDownloader
import com.prissma.launcher.engine.java.supportsJavaLoader
import com.prissma.launcher.engine.rust.StarlingInstallation
import com.prissma.launcher.engine.rust.StarlingLauncher
import com.prissma.launcher.ui.components.BlockButton
import com.prissma.launcher.ui.components.ControlShape
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PrissmaMark
import com.prissma.launcher.ui.components.ReleaseNotesDialog
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.launch.JavaLaunchScreen
import com.prissma.launcher.ui.launch.JavaServersDialog
import com.prissma.launcher.ui.theme.Prissma
import com.prissma.launcher.ui.workshop.BedrockWorkshopScreen
import com.prissma.launcher.ui.workshop.ContentWorkshopScreen
import com.prissma.launcher.ui.workshop.StarlingWorkshopScreen
import com.prissma.launcher.ui.workshop.WorkshopDownloadsDialog
import com.prissma.launcher.ui.workshop.workshopDownloadsLabel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class HubPage(val label: String, val icon: ImageVector) {
    LIBRARY("Mis mundos", Icons.Default.Home),
    WORKSHOP("Taller", Icons.Default.Build),
    CONNECTIONS("Jugar juntos", Icons.Default.Share),
    ACHIEVEMENTS("Logros", Icons.Default.Star),
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
    onOpenStarling: (GameInstance) -> String?,
    onConsumeStarlingReport: (Uri) -> Unit,
    externalLaunchInstanceId: String? = null,
    externalLaunchFromWeb: Boolean = false,
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
    var importingPack by remember { mutableStateOf(false) }
    var packError by remember { mutableStateOf<String?>(null) }
    val consumedPacks = remember(context) { context.getSharedPreferences("imported_modpacks", android.content.Context.MODE_PRIVATE) }
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
    var launchingServer by rememberSaveable { mutableStateOf<String?>(null) }
    var serversId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmWebLaunchId by rememberSaveable { mutableStateOf<String?>(null) }
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

    val importPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importingPack) {
            packError = null
            importingPack = true
            scope.launch {
                try {
                    val instance = ModpackImports(context).stage(uri)
                    downloads.importModpack(instance)
                    showWorkshopDownloads = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    packError = error.message ?: "No se pudo preparar el modpack."
                    if (page != HubPage.WORKSHOP) notify(checkNotNull(packError))
                } finally {
                    importingPack = false
                }
            }
        }
    }

    LaunchedEffect(workshopJobs) {
        val ready = workshopJobs.mapNotNull { job ->
            if (job.state != WorkInfo.State.SUCCEEDED || MODPACK_IMPORT_TAG !in job.tags) return@mapNotNull null
            val id = job.outputData.getString("id") ?: return@mapNotNull null
            if (consumedPacks.getBoolean(id, false)) return@mapNotNull null
            val title = job.outputData.getString("title") ?: return@mapNotNull null
            val version = job.outputData.getString("version") ?: return@mapNotNull null
            val loader = job.outputData.getString("loader") ?: return@mapNotNull null
            GameInstance(id, title, EngineType.JAVA, version, loader)
        }
        if (ready.isNotEmpty() && saveCatalog((instances + ready).distinctBy(GameInstance::id))) {
            consumedPacks.edit().also { edit -> ready.forEach { edit.putBoolean(it.id, true) } }.apply()
        }
    }

    fun launchInstance(instance: GameInstance, server: String? = null) {
        when (instance.engine) {
            EngineType.JAVA -> {
                launchingServer = server
                launchingId = instance.id
            }
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
                val problem = onOpenStarling(instance)
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
            // A web page can open prissma:// links on its own; only launch after the player agrees.
            if (externalLaunchFromWeb) confirmWebLaunchId = instance.id else launchInstance(instance)
        } else notify("El cartucho apunta a una instancia que no está en la biblioteca.")
        onConsumeExternalLaunch()
    }

    instances.firstOrNull { it.id == launchingId && it.engine == EngineType.JAVA }?.let {
        JavaLaunchScreen(it, profile.playerName, profile.accountType, profile.language, launchingServer,
            onBack = { launchingId = null },
            onWorkshop = { selectedId = it.id; launchingId = null; page = HubPage.WORKSHOP })
        return
    }

    val showWorkshop = page == HubPage.WORKSHOP && workshopInstance != null
    BackHandler(enabled = page != HubPage.LIBRARY && !showWorkshop) { page = HubPage.LIBRARY }
    if (showWorkshop) {
        val back = { page = HubPage.LIBRARY }
        when (workshopInstance.engine) {
            EngineType.JAVA -> ContentWorkshopScreen(
                instance = workshopInstance, importingPack = importingPack, packError = packError,
                onImportPack = { importPack.launch(arrayOf("*/*")) },
                onPackDownloads = { showWorkshopDownloads = true }, onBack = back
            )
            EngineType.BEDROCK -> key(workshopInstance.id) {
                BedrockWorkshopScreen(workshopInstance, bedrock, bedrockLauncher, onRefreshBedrock, back)
            }
            EngineType.RUST -> key(workshopInstance.id) {
                StarlingWorkshopScreen(workshopInstance, starling, starlingLauncher, back)
            }
        }
        if (showWorkshopDownloads) WorkshopDownloadsDialog(workshopJobs, instances, downloads) { showWorkshopDownloads = false }
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
            accountLabel = profile.accountType,
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
                    page == HubPage.LIBRARY && event.key == Key.ButtonY && selected != null -> {
                        favorites = if (selected.id in favorites) favorites - selected.id else favorites + selected.id
                        preferences.setFavorites(favorites)
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
                    onPlay = { selected?.let { launchInstance(it) } },
                    onServers = { serversId = selected?.id },
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
                HubPage.ACHIEVEMENTS -> AchievementsPage(instances, selected?.id, Modifier.fillMaxSize())
            }
        }
    }

    if (showWorkshopDownloads) WorkshopDownloadsDialog(workshopJobs, instances, downloads) { showWorkshopDownloads = false }
    instances.firstOrNull { it.id == serversId && it.engine == EngineType.JAVA }?.let { instance ->
        JavaServersDialog(instance, onPlay = { address ->
            serversId = null
            launchInstance(instance, address.text)
        }, onDismiss = { serversId = null })
    }

    if (showBedrockApps) {
        BedrockAppsDialog(bedrock, bedrockLauncher, onRefreshBedrock, onDismiss = { showBedrockApps = false })
    }
    if (showStarlingDiagnostics) {
        StarlingDiagnosticsDialog(starling, starlingLauncher, selected?.takeIf { it.engine == EngineType.RUST },
            starlingReport, onConsumeStarlingReport) {
            showStarlingDiagnostics = false
        }
    }

    if (!showWorkshopDownloads && !showBedrockApps && !showStarlingDiagnostics && starlingReport == null &&
        !releaseNotesSeen && page == HubPage.LIBRARY && externalLaunchInstanceId == null &&
        updateSettingsRequest == 0 && !showAccount && !showNew && editingId == null && deleteId == null
    ) {
        installedNotes?.let { ReleaseNotesDialog(version, it, ::acknowledgeReleaseNotes) }
    }

    instances.firstOrNull { it.id == confirmWebLaunchId }?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmWebLaunchId = null },
            title = { Text("¿Abrir " + item.title + "?") },
            text = { Text("Un enlace de una página web quiere iniciar esta instancia. Ábrela solo si tú pulsaste el enlace.") },
            confirmButton = {
                TextButton(onClick = { confirmWebLaunchId = null; launchInstance(item) }) { Text("Jugar") }
            },
            dismissButton = { TextButton(onClick = { confirmWebLaunchId = null }) { Text("Cancelar") } }
        )
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
    accountLabel: String,
    updateAvailable: Boolean,
    onProfile: () -> Unit,
    downloadsLabel: String,
    onDownloads: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }
    val palette = Prissma.colors
    BoxWithConstraints(modifier.background(palette.surfaceVoid)) {
        val landscape = maxWidth >= 585.dp && maxHeight >= 275.dp
        val hints: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().background(palette.ink).drawBehind {
                    drawRect(palette.surfaceElevated, size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
                }.padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PadHint("A", if (page == HubPage.LIBRARY) "Jugar" else "Elegir", palette.engineJava, palette.onBrandAccent)
                if (page == HubPage.LIBRARY) {
                    PadHint("X", "Taller", palette.engineBedrock, Color(0xFF08202B))
                    PadHint("Y", "Favorito", palette.gold, Color(0xFF2A2508))
                }
                PadHint("B", "Volver", palette.engineRust, Color(0xFF2B0E05))
                Spacer(Modifier.weight(1f))
                if (page == HubPage.LIBRARY) Text("L1 / R1 cambian de bioma", color = palette.textMuted,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        val header: @Composable () -> Unit = {
            if (page != HubPage.LIBRARY) {
                Text(page.label, Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxHeight().width(168.dp).background(palette.ink)
                        .drawBehind {
                            drawRect(palette.surfaceElevated, androidx.compose.ui.geometry.Offset(size.width - 2.dp.toPx(), 0f),
                                androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
                        }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrissmaMark(Modifier.size(26.dp, 29.dp))
                        Text("Biomeria", fontFamily = PixelFont, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            color = palette.textPrimary, maxLines = 1)
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        HubPage.entries.forEach { item ->
                            NavigationItem(
                                item, page == item, updateAvailable && item == HubPage.SETTINGS, { onPage(item) },
                                modifier = if (item == HubPage.LIBRARY) Modifier.focusRequester(firstFocus) else Modifier
                            )
                        }
                        RailButton(downloadsLabel, Icons.Default.Refresh, onDownloads)
                    }
                    ProfileChip(playerName, playerAvatar, accountLabel, onProfile)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                        header()
                        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                    }
                    hints()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(palette.ink).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrissmaMark(Modifier.size(24.dp, 26.dp))
                    Text("Biomeria", Modifier.weight(1f), fontFamily = PixelFont, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    LauncherButton(downloadsLabel, onDownloads)
                    BlockButton(onProfile, contentPadding = PaddingValues(6.dp)) {
                        ProfileAvatar(playerName, playerAvatar, Modifier.size(28.dp))
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    header()
                    Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                }
                Row(Modifier.fillMaxWidth().background(palette.ink).padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    HubPage.entries.forEach { item ->
                        NavigationItem(item, page == item, updateAvailable && item == HubPage.SETTINGS, { onPage(item) },
                            compact = true,
                            modifier = if (item == HubPage.LIBRARY) Modifier.focusRequester(firstFocus) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun PadHint(key: String, label: String, color: Color, onColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(key, color = onColor, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(label, color = Prissma.colors.textPrimary, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ProfileChip(name: String, avatar: File?, account: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focus by source.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().clip(ControlShape).background(Prissma.colors.surfaceCard)
            .border(if (focus) 3.dp else 2.dp, if (focus) Prissma.colors.gold else Prissma.colors.surfaceElevated, ControlShape)
            .gamepadClick(onClick = onClick)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProfileAvatar(name, avatar, Modifier.size(32.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (account == "Offline") "Sin conexión" else account, style = MaterialTheme.typography.labelSmall,
                color = Prissma.colors.textMuted, maxLines = 1)
        }
    }
}

@Composable
private fun RailButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focus by source.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(ControlShape)
            .background(if (focus) Prissma.colors.surfaceElevated else Color.Transparent)
            .border(if (focus) 3.dp else 0.dp, if (focus) Prissma.colors.gold else Color.Transparent, ControlShape)
            .gamepadClick(onClick = onClick)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Prissma.colors.textMuted)
        Text(label, color = Prissma.colors.textMuted, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NavigationItem(page: HubPage, selected: Boolean, update: Boolean, onClick: () -> Unit,
                           modifier: Modifier = Modifier, compact: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val focus by source.collectIsFocusedAsState()
    val tint = if (selected || focus) Prissma.colors.textPrimary else Prissma.colors.textMuted
    val accent = Prissma.colors.brandAccent
    val base = modifier.clip(ControlShape)
        .background(if (selected || focus) Prissma.colors.surfaceElevated else Color.Transparent)
        .drawBehind {
            if (selected && !compact) drawRect(accent, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
        }
        .border(if (focus) 3.dp else 0.dp, if (focus) Prissma.colors.gold else Color.Transparent, ControlShape)
        .gamepadClick(onClick = onClick)
        .selectable(selected, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
    val icon: @Composable () -> Unit = {
        BadgedBox(badge = { if (update) Badge(containerColor = Prissma.colors.gold) }) {
            Icon(page.icon, null, Modifier.size(20.dp), tint = if (selected) accent else tint)
        }
    }
    if (compact) {
        Column(base.widthIn(min = 72.dp).heightIn(min = 46.dp).padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Text(page.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, color = tint)
        }
    } else {
        Row(base.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            icon()
            Text(page.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = tint)
        }
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
