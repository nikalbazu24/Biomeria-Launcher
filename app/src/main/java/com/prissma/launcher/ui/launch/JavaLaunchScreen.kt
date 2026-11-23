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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.prissma.launcher.data.EngineType
import com.prissma.launcher.data.CrashReading
import com.prissma.launcher.data.LaunchReports
import com.prissma.launcher.data.LogLevel
import com.prissma.launcher.data.classifyLogLine
import com.prissma.launcher.data.readCrash
import com.prissma.launcher.ui.components.LogView
import com.prissma.launcher.ui.components.PrissmaMark
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.prissma.launcher.ui.components.BlockButton
import com.prissma.launcher.ui.components.BlockButtons
import com.prissma.launcher.ui.theme.biomeImage
import com.prissma.launcher.ui.theme.biomeLabel
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
import com.prissma.launcher.engine.java.normalizeFpsLimit
import com.prissma.launcher.engine.java.javaQuickPlayArguments
import com.prissma.launcher.engine.java.parseJavaServerAddress
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
    instance: GameInstance, playerName: String, accountType: String, preferredLanguage: String = "",
    requestedServer: String? = null, onBack: () -> Unit, onWorkshop: () -> Unit = {}
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
    val server = remember(requestedServer) { requestedServer?.let(::parseJavaServerAddress) }
    var requestedConnectionStarted by rememberSaveable(instance.id, requestedServer) { mutableStateOf(false) }
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
            settings.getBoolean("minimap-" + instance.id, false),
            settings.getBoolean("coordinates-" + instance.id, false),
            settings.getBoolean("battery-" + instance.id, false),
            settings.getBoolean("widgets-below-" + instance.id, false),
            settings.getFloat("widget-scale-" + instance.id, 1f).coerceIn(.75f, 1.5f),
            settings.getFloat("widget-opacity-" + instance.id, .65f).coerceIn(.25f, 1f),
            normalizeFpsLimit(settings.getInt("fps-limit-" + instance.id, 0)),
            settings.getBoolean("battery-saver-" + instance.id, false),
            settings.getBoolean("map-entities-" + instance.id, true)))
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
            .putBoolean("minimap-" + instance.id, updated.showMinimap)
            .putBoolean("coordinates-" + instance.id, updated.showCoordinates)
            .putBoolean("battery-" + instance.id, updated.showBattery)
            .putBoolean("widgets-below-" + instance.id, updated.widgetsBelow)
            .putFloat("widget-scale-" + instance.id, updated.widgetScale)
            .putFloat("widget-opacity-" + instance.id, updated.widgetOpacity)
            .putInt("fps-limit-" + instance.id, updated.fpsLimit)
            .putBoolean("battery-saver-" + instance.id, updated.batterySaver)
            .putBoolean("map-entities-" + instance.id, updated.showEntities).apply()
    }
    var crash by remember { mutableStateOf<CrashReading?>(null) }
    var crashLog by remember { mutableStateOf("") }
    val gameActivity = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getBundleExtra("GAME_HUD")?.let { saveHud(GameHudSettings.fromBundle(it)) }
        // The game runs in its own process; when it comes back, its log tells whether it crashed.
        scope.launch {
            val log = withContext(Dispatchers.IO) {
                runCatching { readLogTail(File(context.filesDir, "instances/" + instance.id + "/prissma_last.log")) }.getOrDefault("")
            }
            val reading = readCrash(log)
            if (reading.crashed) {
                crashLog = log
                crash = reading
            }
        }
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
                val dualScreenMod = if (supportsDualInventory(instance.version, instance.configuredModLoader)) {
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
                    putExtra("GAME_ARGS", gameArgs + ready.additionalGameArguments + javaQuickPlayArguments(instance.version, server))
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

    LaunchedEffect(server, runtime, game) {
        if (server != null && runtime != null && game != null && !requestedConnectionStarted) {
            requestedConnectionStarted = true
            start()
        }
    }

    var showLanguage by remember { mutableStateOf(false) }
    crash?.let { reading ->
        CrashScreen(
            instance, reading, crashLog,
            onWorkshop = { crash = null; onWorkshop() },
            onRetry = { crash = null; start() },
            onBack = { crash = null; onBack() }
        )
        return
    }
    val runtimeStep = stepOf(runtime != null, runtimeTask)
    val gameStep = if (supported) stepOf(game != null, gameTask) else LaunchStep(StepState.FAILED, "Esta versión necesita otro motor.", 0f)
    val ready = runtime != null && game != null
    val progress = (runtimeStep.fraction + gameStep.fraction) / 2f
    val statusLine = when {
        opening -> "Abriendo Minecraft…"
        ready -> "Todo listo para jugar."
        runtimeStep.state == StepState.RUNNING || runtimeStep.state == StepState.WAITING -> runtimeStep.detail
        else -> gameStep.detail
    }

    Box(
        Modifier.fillMaxSize().background(Prissma.colors.ink)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.ButtonB || it.key == Key.Escape)) {
                    onBack()
                    true
                } else false
            }
    ) {
        Image(painterResource(EngineType.JAVA.biomeImage), null, Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Color(0xFA080A0D), .55f to Color(0xF0080A0D), 1f to Color(0x99080A0D)
        )))
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            val wide = maxWidth >= 600.dp
            val prepare: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BlockButton(onBack, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        PadKey("B", Prissma.colors.engineRust, Color(0xFF2B0E05))
                        Spacer(Modifier.width(8.dp))
                        Text("Mis mundos", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                    }
                    Column {
                        Text(EngineType.JAVA.biomeLabel,
                            Modifier.background(Prissma.colors.engineJava, RoundedCornerShape(3.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
                            color = Prissma.colors.ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(8.dp))
                        Text(instance.title, style = MaterialTheme.typography.headlineLarge.copy(
                            shadow = Shadow(Color(0x8C000000), Offset(5f, 5f))), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Minecraft " + instance.version + " · " + instance.loader, color = Color(0xFFF2F5EF),
                            style = MaterialTheme.typography.titleSmall.copy(shadow = Shadow(Color(0xCC000000), Offset(2f, 2f))),
                            fontWeight = FontWeight.ExtraBold)
                    }
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xF5101418), PanelShape)
                            .border(2.dp, Prissma.colors.surfaceElevated, PanelShape).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Preparando tu mundo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        StepRow("Java para Android", runtimeStep) { downloads.runtime() }
                        StepRow("Archivos de Minecraft " + instance.version, gameStep) { if (supported) downloads.game(instance) }
                        if (instance.version in experimentalVersions) Text("Vanilla con Java 21 · en pruebas",
                            color = Prissma.colors.textPrimary, style = MaterialTheme.typography.bodySmall)
                        if (instance.configuredModLoader == "neoforge") Text("NeoForge $NEOFORGE_VERSION · el primer arranque prepara el cliente.",
                            color = Prissma.colors.textPrimary, style = MaterialTheme.typography.bodySmall)
                        StepRow("Abrir el juego", LaunchStep(
                            if (opening) StepState.RUNNING else StepState.WAITING,
                            if (opening) "Abriendo…" else "Esperando", 0f), null)
                        BlockProgress(if (ready) 1f else progress)
                        Text(statusLine, color = Color(0xFFE3E9EC), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    launchError?.let { Text(it, color = Prissma.colors.error, fontWeight = FontWeight.Bold) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BlockButton(::start, Modifier.heightIn(min = 54.dp), BlockButtons.primary, ready && !opening,
                            PaddingValues(horizontal = 28.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(when {
                                opening -> "Abriendo…"
                                !ready -> "Preparando…"
                                server == null -> "Jugar"
                                else -> "Conectar"
                            }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        }
                        Column {
                            server?.let { Text("Servidor · ${it.text}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall) }
                            if (!ready) Text("Puedes volver a tus mundos: la descarga sigue en segundo plano.",
                                color = Color(0xFFE3E9EC), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            val side: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
                    TipCard()
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xF50B0E11), PanelShape)
                            .border(2.dp, Prissma.colors.surfaceElevated, PanelShape).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Antes de entrar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OptionTile("Rendimiento", heapLabel(heapMb) + " · " + renderScale.percent + " %", Modifier.weight(1f)) { showPerformance = true }
                            OptionTile("Controles y HUD", hudSummary(hud), Modifier.weight(1f)) { showControls = true }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OptionTile("Doble pantalla", if (hud.dualScreen) "Activada · " + hud.panelHome.label else "Desactivada",
                                Modifier.weight(1f)) { showControls = true }
                            OptionTile("Idioma del juego", gameLanguages[language] ?: "Del juego", Modifier.weight(1f)) { showLanguage = true }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    prepare(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()))
                    side(Modifier.weight(.85f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    prepare(Modifier.fillMaxWidth())
                    side(Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (showLanguage) AlertDialog(
        onDismissRequest = { showLanguage = false },
        title = { Text("Idioma del juego") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                gameLanguages.forEach { (code, title) ->
                    val select = {
                        language = code
                        settings.edit().putString("language-" + instance.id, code).apply()
                        showLanguage = false
                    }
                    BlockButton(select, Modifier.fillMaxWidth(), if (language == code) BlockButtons.primary else BlockButtons.neutral) {
                        Text(title, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        },
        confirmButton = { LauncherButton("Cerrar", { showLanguage = false }) }
    )
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

internal enum class StepState { READY, RUNNING, WAITING, FAILED }

internal data class LaunchStep(val state: StepState, val detail: String, val fraction: Float)

/** What a download is doing, in words a player understands, and how far along it is. */
private fun stepOf(ready: Boolean, task: WorkInfo?): LaunchStep {
    if (ready) return LaunchStep(StepState.READY, "Listo", 1f)
    if (task?.state == WorkInfo.State.FAILED || task?.state == WorkInfo.State.CANCELLED) {
        return LaunchStep(StepState.FAILED, task?.outputData?.getString(DOWNLOAD_MESSAGE) ?: "La descarga se interrumpió.", 0f)
    }
    val percent = task?.progress?.getInt(DOWNLOAD_PERCENT, 0) ?: 0
    val message = task?.progress?.getString(DOWNLOAD_MESSAGE)
    return if (task?.state == WorkInfo.State.RUNNING) {
        LaunchStep(StepState.RUNNING, message ?: "Descargando…", percent / 100f)
    } else {
        LaunchStep(StepState.WAITING, "Esperando conexión a internet", 0f)
    }
}

private fun heapLabel(mb: Int) = if (mb % 1024 == 0) "${mb / 1024} GB de RAM" else "$mb MB de RAM"

private fun hudSummary(hud: GameHudSettings): String {
    val shown = listOfNotNull(
        "FPS".takeIf { hud.showFps }, "coordenadas".takeIf { hud.showCoordinates },
        "batería".takeIf { hud.showBattery }, "minimapa".takeIf { hud.showMinimap }
    )
    return if (shown.isEmpty()) "Mando · sin indicadores" else "Mando · " + shown.joinToString(", ")
}

@Composable
private fun StepRow(title: String, step: LaunchStep, onRetry: (() -> Unit)?) {
    val palette = Prissma.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(3.dp)).background(when (step.state) {
            StepState.READY -> palette.brandAccent
            StepState.FAILED -> palette.error
            else -> Color.Transparent
        }).border(if (step.state == StepState.READY || step.state == StepState.FAILED) 0.dp else 3.dp, palette.borderSubtle,
            RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
            when (step.state) {
                StepState.READY -> Text("✓", color = palette.onBrandAccent, fontWeight = FontWeight.ExtraBold)
                StepState.FAILED -> Text("!", color = palette.ink, fontWeight = FontWeight.ExtraBold)
                StepState.RUNNING -> CircularProgressIndicator(Modifier.size(12.dp), color = palette.gold, strokeWidth = 3.dp)
                StepState.WAITING -> Unit
            }
        }
        Text(title, Modifier.weight(1f), fontWeight = if (step.state == StepState.RUNNING) FontWeight.ExtraBold else FontWeight.Bold,
            color = if (step.state == StepState.WAITING) Color(0xFFB8C3C9) else palette.textPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        when (step.state) {
            StepState.READY -> Text("Listo", color = palette.brandAccentLight, fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.labelLarge)
            StepState.RUNNING -> Text(if (step.fraction > 0f) "${(step.fraction * 100).toInt()} %" else "…", color = palette.gold,
                fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
            StepState.FAILED -> if (onRetry != null) LauncherButton("Reintentar", onRetry)
            StepState.WAITING -> Text(step.detail, color = Color(0xFFB8C3C9), fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
    if (step.state == StepState.FAILED) Text(step.detail, color = palette.error, style = MaterialTheme.typography.bodySmall)
}

/** Progress drawn as blocks, like the bar of the splash screen. */
@Composable
private fun BlockProgress(fraction: Float, blocks: Int = 20) {
    val filled = (fraction.coerceIn(0f, 1f) * blocks).toInt()
    Row(Modifier.fillMaxWidth().background(Prissma.colors.ink).border(2.dp, Prissma.colors.borderSubtle).padding(4.dp)
        .semantics { contentDescription = "Progreso ${(fraction * 100).toInt()} %" },
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(blocks) { index ->
            Box(Modifier.weight(1f).height(14.dp).background(if (index < filled) Prissma.colors.brandAccent else Color(0xFF1E252C)))
        }
    }
}

private val tips = listOf(
    "Pulsa Select en partida para abrir el menú de Biomeria.",
    "En la AYN Thor, el inventario y el mapa pueden ir en la pantalla de abajo.",
    "Si algo falla, Biomeria guarda el registro para que puedas compartirlo.",
    "Con L1 y R1 cambias de bioma en Mis mundos."
)

@Composable
private fun TipCard() {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            index = (index + 1) % tips.size
        }
    }
    Column(Modifier.fillMaxWidth().background(Color(0xF50B0E11), PanelShape)
        .border(2.dp, Prissma.colors.surfaceElevated, PanelShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("CONSEJO", color = Prissma.colors.gold, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
        Crossfade(index, label = "tip") { Text(tips[it], fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, minLines = 2) }
    }
}

@Composable
private fun OptionTile(title: String, detail: String, modifier: Modifier, onClick: () -> Unit) {
    BlockButton(onClick, modifier.heightIn(min = 60.dp), BlockButtons.neutral,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(detail, color = Color(0xFFC9D2D7), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PadKey(key: String, color: Color, onColor: Color) {
    Box(Modifier.size(22.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(key, color = onColor, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** The end of a log file: enough to find the error without loading huge logs into memory. */
private fun readLogTail(file: File, bytes: Int = 96 * 1024): String {
    if (!file.isFile) return ""
    java.io.RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val start = (length - bytes).coerceAtLeast(0)
        raf.seek(start)
        val buffer = ByteArray((length - start).toInt())
        raf.readFully(buffer)
        val text = buffer.toString(Charsets.UTF_8)
        return if (start > 0) text.substringAfter('\n') else text
    }
}

@Composable
private fun CrashScreen(
    instance: GameInstance,
    reading: CrashReading,
    log: String,
    onWorkshop: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var shareStatus by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val errors = reading.tail.count { classifyLogLine(it) == LogLevel.ERROR }
    val share = {
        if (!sharing) {
            sharing = true
            shareStatus = "Subiendo el registro…"
            scope.launch {
                LaunchReports(context).upload(log)
                    .onSuccess { url ->
                        clipboard.setText(AnnotatedString(url))
                        shareStatus = "Enlace copiado: $url"
                    }
                    .onFailure { shareStatus = it.message ?: "No se pudo subir el registro." }
                sharing = false
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Prissma.colors.surfaceVoid).safeDrawingPadding().padding(20.dp)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.ButtonB, Key.Escape -> { onBack(); true }
                    Key.ButtonX -> { onRetry(); true }
                    Key.ButtonY -> { share(); true }
                    else -> false
                }
            }
    ) {
        val wide = maxWidth >= 640.dp
        val summary: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(64.dp, 70.dp)) {
                        PrissmaMark(Modifier.fillMaxSize())
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val w = size.width; val h = size.height
                            val crack = androidx.compose.ui.graphics.Path().apply {
                                moveTo(w * .5f, h * .12f); lineTo(w * .44f, h * .28f); lineTo(w * .54f, h * .36f)
                                lineTo(w * .47f, h * .48f); lineTo(w * .56f, h * .62f); lineTo(w * .5f, h * .74f); lineTo(w * .57f, h * .9f)
                            }
                            drawPath(crack, Prissma.colors.ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx()))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Minecraft se cerró inesperadamente", style = MaterialTheme.typography.headlineSmall)
                        Text(instance.title + " · Minecraft " + instance.version, color = Prissma.colors.textMuted,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().background(Color(0xFF1E1715), PanelShape).border(2.dp, Color(0xFF5A2E22), PanelShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Lo que encontramos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text(reading.cause ?: "No reconocemos este error. Abajo están las últimas líneas del registro: compártelo para pedir ayuda.",
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CrashAction("A", "Ir al Taller y corregir", Modifier.weight(1f), BlockButtons.primary, onClick = onWorkshop)
                    CrashAction("X", "Volver a intentar", Modifier.weight(1f), onClick = onRetry)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CrashAction("Y", if (sharing) "Subiendo…" else "Compartir registro", Modifier.weight(1f), onClick = share)
                    CrashAction("B", "Volver a mis mundos", Modifier.weight(1f), onClick = onBack)
                }
                Text(shareStatus ?: "\"Compartir registro\" lo sube a mclo.gs y copia el enlace para pedir ayuda. Tu sesión nunca se incluye.",
                    color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        val logPanel: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Final del registro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    if (errors > 0) Text(if (errors == 1) "1 error" else "$errors errores",
                        Modifier.background(Prissma.colors.engineRust, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Prissma.colors.ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    LauncherButton("Copiar", { clipboard.setText(AnnotatedString(reading.tail.joinToString("\n"))) })
                }
                val listState = rememberLazyListState()
                LaunchedEffect(reading) { if (reading.tail.isNotEmpty()) listState.scrollToItem(reading.tail.lastIndex) }
                LogView(reading.tail, Modifier.fillMaxWidth().weight(1f), listState)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                summary(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                logPanel(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                summary(Modifier.fillMaxWidth())
                logPanel(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun CrashAction(key: String, label: String, modifier: Modifier, colors: com.prissma.launcher.ui.components.BlockColors = BlockButtons.neutral,
                        onClick: () -> Unit) {
    BlockButton(onClick, modifier.heightIn(min = 52.dp), colors, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        PadKey(key, Color(0xFF0B0E11).copy(alpha = .35f), colors.content)
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}
