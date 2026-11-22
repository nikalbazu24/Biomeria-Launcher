package com.prissma.launcher.ui.launch

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.data.accounts.JavaAccountMode
import com.prissma.launcher.data.accounts.MinecraftAccounts
import com.prissma.launcher.engine.java.DOWNLOAD_MESSAGE
import com.prissma.launcher.engine.java.DOWNLOAD_PERCENT
import com.prissma.launcher.engine.java.GameDownloads
import com.prissma.launcher.engine.java.GameHudSettings
import com.prissma.launcher.engine.java.PanelMapSource
import com.prissma.launcher.engine.java.PanelPage
import com.prissma.launcher.engine.java.DUAL_SCREEN_MOD
import com.prissma.launcher.engine.java.supportsDualInventory
import com.prissma.launcher.engine.java.DEFAULT_JAVA_HEAP_MB
import com.prissma.launcher.engine.java.JavaRenderer
import com.prissma.launcher.engine.java.RenderScale
import com.prissma.launcher.engine.java.selectedHeapSize
import com.prissma.launcher.engine.java.decodeGamepadActions
import com.prissma.launcher.engine.java.encodeGamepadActions
import com.prissma.launcher.engine.java.JavaGameActivity
import com.prissma.launcher.engine.java.ELY_PROFILE_ID
import com.prissma.launcher.engine.java.MICROSOFT_PROFILE_ID
import com.prissma.launcher.engine.java.JavaRuntimeManager
import com.prissma.launcher.engine.java.MojangDownloader
import com.prissma.launcher.engine.java.PreparedJavaGame
import com.prissma.launcher.engine.java.RUNTIME_WORK
import com.prissma.launcher.engine.java.applyMinecraftOptions
import com.prissma.launcher.engine.java.gameLanguages
import com.prissma.launcher.engine.java.supportsJavaLoader
import com.prissma.launcher.engine.java.NEOFORGE_VERSION
import com.prissma.launcher.engine.java.experimentalVersions
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PageHeader
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun JavaLaunchScreen(
    instance: GameInstance, playerName: String, accountType: String, preferredLanguage: String = "", onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { context.getSharedPreferences("prissma_java", Context.MODE_PRIVATE) }
    val downloads = remember(context) { GameDownloads(context) }
    val runtimeManager = remember(context) { JavaRuntimeManager(context) }
    val downloader = remember(context) { MojangDownloader(context) }
    val accounts = remember(context) { MinecraftAccounts(context.applicationContext) }
    val runtimeWork by remember(downloads) { downloads.observe(RUNTIME_WORK) }.collectAsState(emptyList())
    val gameWork by remember(downloads, instance) { downloads.observe(downloads.key(instance)) }.collectAsState(emptyList())
    val runtimeTask = runtimeWork.firstOrNull { !it.state.isFinished } ?: runtimeWork.lastOrNull()
    val gameTask = gameWork.firstOrNull { !it.state.isFinished } ?: gameWork.lastOrNull()
    var runtime by remember(runtimeManager) { mutableStateOf(runtimeManager.getInstalledRuntime()) }
    var game by remember(instance) { mutableStateOf<PreparedJavaGame?>(null) }
    var language by remember(instance.id) { mutableStateOf(settings.getString("language-" + instance.id, preferredLanguage).orEmpty()) }
    var launchError by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var showPerformance by remember { mutableStateOf(false) }
    val deviceRam = remember(context) { totalDeviceRam(context) }
    var heapMb by remember(instance.id, deviceRam) {
        mutableIntStateOf(selectedHeapSize(settings.getInt("heap-" + instance.id, DEFAULT_JAVA_HEAP_MB), deviceRam))
    }
    var renderScale by remember(instance.id) {
        mutableStateOf(RenderScale.fromPercent(settings.getInt("render-scale-" + instance.id, 100)))
    }
    var renderer by remember(instance.id) {
        mutableStateOf(JavaRenderer.fromName(settings.getString("renderer-" + instance.id, null)))
    }
    var bindings by remember(instance.id) {
        mutableStateOf(decodeGamepadActions(settings.getString("controls-" + instance.id, "").orEmpty()))
    }
    var hud by remember(instance.id) {
        mutableStateOf(GameHudSettings(settings.getBoolean("touch-" + instance.id, true),
            settings.getBoolean("fps-" + instance.id, false),
            settings.getFloat("pause-opacity-" + instance.id, .65f).coerceIn(.15f, 1f),
            settings.getBoolean("dual-screen-" + instance.id, false),
            settings.getBoolean("panel-crafting-" + instance.id, true),
            PanelPage.fromPreference(settings.getString("panel-home-" + instance.id, null)),
            PanelMapSource.fromPreference(settings.getString("panel-map-" + instance.id, null)),
            settings.getBoolean("panel-compact-" + instance.id, false),
            settings.getBoolean("panel-chests-" + instance.id, true),
            settings.getBoolean("panel-furnaces-" + instance.id, true),
            settings.getBoolean("panel-xaero-map-" + instance.id, true)))
    }
    val supported = supportsJavaLoader(instance.version, instance.loader)

    fun saveHud(updated: GameHudSettings) {
        hud = updated
        settings.edit().putBoolean("touch-" + instance.id, updated.showTouch)
            .putBoolean("fps-" + instance.id, updated.showFps)
            .putFloat("pause-opacity-" + instance.id, updated.pauseOpacity)
            .putBoolean("dual-screen-" + instance.id, updated.dualScreen)
            .putBoolean("panel-crafting-" + instance.id, updated.routeCrafting)
            .putString("panel-home-" + instance.id, updated.panelHome.name)
            .putString("panel-map-" + instance.id, updated.panelMap.name)
            .putBoolean("panel-compact-" + instance.id, updated.compactPanel)
            .putBoolean("panel-chests-" + instance.id, updated.routeChests)
            .putBoolean("panel-furnaces-" + instance.id, updated.routeFurnaces)
            .putBoolean("panel-xaero-map-" + instance.id, updated.routeXaeroMap).apply()
    }
    val gameActivity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getBundleExtra("GAME_HUD")?.let { saveHud(GameHudSettings.fromBundle(it)) }
    }

    LaunchedEffect(instance) {
        game = withContext(Dispatchers.IO) { downloader.prepared(instance.id, instance.version, instance.loader) }
        if (game == null && supported) downloads.game(instance)
        if (runtime == null) downloads.runtime()
    }
    LaunchedEffect(runtimeTask?.state) {
        if (runtimeTask?.state == WorkInfo.State.SUCCEEDED) {
            runtime = withContext(Dispatchers.IO) { runtimeManager.getInstalledRuntime() }
        }
    }
    LaunchedEffect(instance, gameTask?.state) {
        if (gameTask?.state == WorkInfo.State.SUCCEEDED) {
            game = withContext(Dispatchers.IO) { downloader.prepared(instance.id, instance.version, instance.loader) }
        }
    }

    fun start() {
        val ready = game ?: return
        val installed = runtime ?: return
        if (opening) return
        opening = true
        launchError = null
        scope.launch {
            try {
                val mode = JavaAccountMode.fromProfile(accountType)
                val identity = accounts.prepareLaunch(mode)
                val agentOptions = if (mode == JavaAccountMode.ELY_BY) {
                    runtimeManager.elyJvmArguments(installed.javaHomeDir, accounts.elyAgentMetadata())
                } else emptyList()
                val gameDir = File(context.filesDir, "instances/" + instance.id)
                val scrollScale = withContext(Dispatchers.IO) { applyMinecraftOptions(gameDir, language) }
                val classpath = runtimeManager.buildClasspath(ready)
                val dualScreenMod = if (hud.dualScreen && supportsDualInventory(instance.version, instance.configuredModLoader)) {
                    runtimeManager.dualScreenMod().absolutePath
                } else null
                val playerUuid = UUID.nameUUIDFromBytes("OfflinePlayer:$playerName".toByteArray(Charsets.UTF_8))
                val gameArgs = runtimeManager.buildMinecraftArgs(
                    instance.id, if (ready.neoForgePlan == null) instance.version else "neoforge-$NEOFORGE_VERSION",
                    identity?.playerName ?: playerName, identity?.profileId ?: playerUuid.toString(), ready.assetsDir,
                    ready.assetIndexId, account = mode
                )
                gameActivity.launch(Intent(context, JavaGameActivity::class.java).apply {
                    putExtra("INSTANCE_TITLE", instance.title)
                    putExtra("RESOLVED_VERSION", instance.version)
                    putExtra("MAIN_CLASS", ready.mainClass)
                    putExtra("GAME_DIR_PATH", gameDir.absolutePath)
                    putExtra("CLASSPATH", classpath)
                    putExtra("GAME_ARGS", gameArgs + ready.additionalGameArguments)
                    when (mode) {
                        JavaAccountMode.MICROSOFT -> putExtra(MICROSOFT_PROFILE_ID, checkNotNull(identity).profileId)
                        JavaAccountMode.ELY_BY -> putExtra(ELY_PROFILE_ID, checkNotNull(identity).profileId)
                        JavaAccountMode.OFFLINE -> Unit
                    }
                    putExtra("JVM_ARGS", (ready.jvmArguments + agentOptions).toTypedArray())
                    putExtra("LIBJVM_PATH", installed.libJvmFile.absolutePath)
                    putExtra("JAVA_HOME_PATH", installed.javaHomeDir.absolutePath)
                    putExtra("JAVA_HEAP_MB", heapMb)
                    putExtra("RENDER_SCALE", renderScale.percent)
                    putExtra("JAVA_RENDERER", renderer.name)
                    putExtra("GAMEPAD_BINDINGS", encodeGamepadActions(bindings))
                    putExtra("HOTBAR_SCROLL_SCALE", scrollScale)
                    putExtra("GAME_HUD", hud.toBundle())
                    if (dualScreenMod != null) putExtra(DUAL_SCREEN_MOD, dualScreenMod)
                })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                launchError = error.message ?: "No se pudo preparar Minecraft."
            } finally {
                opening = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Prissma.colors.surfaceVoid).safeDrawingPadding().padding(14.dp)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.ButtonB || it.key == Key.Escape)) {
                    onBack()
                    true
                } else false
            }.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageHeader(instance.title, "Minecraft " + instance.version + " · " + instance.loader, onBack)
        val gamePanel: @Composable (Modifier) -> Unit = { modifier ->
            LaunchPanel("Archivos para jugar", modifier) {
                DownloadRow("Java 21 · ARM64", runtime != null, runtimeTask) { downloads.runtime() }
                HorizontalDivider(color = Prissma.colors.borderSubtle)
                if (instance.version in experimentalVersions) Text("Vanilla con Java 21 · En pruebas",
                    color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                if (instance.configuredModLoader == "neoforge") Text("NeoForge $NEOFORGE_VERSION · El primer arranque prepara el cliente.",
                    color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                if (supported) DownloadRow("Minecraft " + instance.version, game != null, gameTask) { downloads.game(instance) }
                else Text("La versión o el cargador de esta instancia requiere otro runtime.", color = Prissma.colors.error)
                launchError?.let { Text(it, color = Prissma.colors.error) }
                LauncherButton(if (opening) "Abriendo…" else "Jugar", ::start,
                    primary = true, icon = Icons.Default.PlayArrow, enabled = runtime != null && game != null && !opening)
            }
        }
        val optionsPanel: @Composable (Modifier) -> Unit = { modifier ->
            LaunchPanel("Opciones de partida", modifier) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    LauncherButton("Controles y HUD", { showControls = true })
                    LauncherButton("Rendimiento y vídeo", { showPerformance = true })
                }
                Text("Idioma de Minecraft", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    gameLanguages.forEach { (code, title) ->
                        val select = {
                            language = code
                            settings.edit().putString("language-" + instance.id, code).apply()
                        }
                        FilterChip(language == code, select, { Text(title) }, modifier = Modifier.gamepadClick(onClick = select))
                    }
                }
                if (runtime == null || game == null) {
                    Text("Puedes volver a la biblioteca mientras se descargan los archivos.",
                        style = MaterialTheme.typography.bodySmall, color = Prissma.colors.textMuted)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 535.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    gamePanel(Modifier.weight(1.2f))
                    optionsPanel(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    gamePanel(Modifier.fillMaxWidth())
                    optionsPanel(Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (showPerformance) GamePerformanceDialog(
        heapMb, deviceRam, renderScale, renderer,
        onHeap = { heapMb = it; settings.edit().putInt("heap-" + instance.id, it).apply() },
        onScale = { renderScale = it; settings.edit().putInt("render-scale-" + instance.id, it.percent).apply() },
        onRenderer = { renderer = it; settings.edit().putString("renderer-" + instance.id, it.name).apply() },
        onDismiss = { showPerformance = false }
    )
    if (showControls) GameControlsDialog(
        bindings, { mapping ->
            bindings = mapping
            settings.edit().putString("controls-" + instance.id, encodeGamepadActions(mapping)).apply()
        }, hud, ::saveHud, { showControls = false }
    )

}

@Composable
private fun DownloadRow(title: String, ready: Boolean, task: WorkInfo?, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontWeight = FontWeight.SemiBold)
        if (ready) Text("Listo", color = Prissma.colors.brandAccent)
    }
    if (ready) return
    val failed = task?.state in listOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)
    if (failed) {
        Text(task?.outputData?.getString(DOWNLOAD_MESSAGE) ?: "Descarga interrumpida.", color = Prissma.colors.error)
        LauncherButton("Reintentar", retry)
    } else {
        val percent = task?.progress?.getInt(DOWNLOAD_PERCENT, 0) ?: 0
        val message = task?.progress?.getString(DOWNLOAD_MESSAGE)
            ?: if (task?.state == WorkInfo.State.RUNNING) "Preparando…" else "En cola · esperando conexión"
        Text(message, color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
        if (percent > 0) LinearProgressIndicator({ percent / 100f }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun LaunchPanel(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(Prissma.colors.surfaceCard, PanelShape)
        .border(1.dp, Prissma.colors.borderSubtle, PanelShape).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        content()
    }
}
