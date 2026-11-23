package com.biomeria.launcher.engine.java

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.components.PanelShape
import com.biomeria.launcher.ui.theme.Biomeria
import com.biomeria.launcher.ui.theme.BiomeriaTheme
import com.biomeria.launcher.ui.launch.GameHudControls
import com.biomeria.launcher.data.PlayStats
import com.biomeria.launcher.data.accounts.AccountError
import com.biomeria.launcher.data.accounts.JavaAccountMode
import com.biomeria.launcher.data.accounts.MinecraftAccounts
import kotlinx.coroutines.delay
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess
import kotlin.math.roundToInt

internal const val MICROSOFT_PROFILE_ID = "MICROSOFT_PROFILE_ID"
internal const val ELY_PROFILE_ID = "ELY_PROFILE_ID"

class JavaGameActivity : ComponentActivity() {
    private lateinit var gameInput: GameInput
    private var controlsActive = false
    private val skinPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) Thread {
            CharacterLooks.importMessage.value = CharacterLooks.importSkin(applicationContext, uri)
        }.start()
    }

    /** Opens the system picker for a skin image; the editor shows the result. */
    internal fun pickSkinImage() {
        CharacterLooks.importMessage.value = null
        try {
            skinPicker.launch("image/png")
        } catch (error: android.content.ActivityNotFoundException) {
            CharacterLooks.importMessage.value = "No hay una app para elegir imágenes en este equipo."
        }
    }
    private var togglePanel: (() -> Unit)? = null
    private var toggleQuickMenu: (() -> Unit)? = null
    private var dualScreenBridge: DualScreenBridge? = null
    internal var lowerPanelVisible by mutableStateOf(false)
        private set
    private var lowerPanelInput: ((PanelPadAction) -> Unit)? = null
    private var lowerPanelPan: ((Float, Float) -> Unit)? = null
    private var resumed = false
    private var foregroundFpsLimit = 0
    private var saveBattery = false
    private var hostFocused = false
    private var inputManager: InputManager? = null
    internal var lowerGamepadFocused by mutableStateOf(false)
        private set
    private val padRouter = PanelPadRouter(
        onFocus = { focused ->
            lowerGamepadFocused = focused
            if (::gameInput.isInitialized) gameInput.reset()
        },
        onAction = { lowerPanelInput?.invoke(it) },
        onPan = { x, y -> lowerPanelPan?.invoke(x, y) }
    )
    private val devices = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = Unit
        override fun onInputDeviceChanged(deviceId: Int) = Unit
        override fun onInputDeviceRemoved(deviceId: Int) = padRouter.removeDevice(deviceId)
    }

    /** Keys for the big map on single-screen consoles while it is open; it takes the whole controller. */
    internal var mapKeys: ((Int) -> Unit)? = null
    internal var mapAxes by mutableStateOf<GamepadAxes?>(null)
        private set
    private var mapHat: Int? = null
    /** A short press of Select opens or closes the big map when there is no lower screen; returns false if it did not. */
    internal var selectOpensMap: (() -> Boolean)? = null

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        val opensMap = selectOpensMap
        if (event.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_SELECT && opensMap != null && !lowerPanelVisible) {
            if (event.action == AndroidKeyEvent.ACTION_UP && !event.isCanceled) {
                // Holding Select still opens Biomeria's menu.
                if (event.eventTime - event.downTime >= 600L || !opensMap()) togglePanel?.invoke()
            }
            return true
        }
        val keys = mapKeys
        if (keys != null && event.keyCode != AndroidKeyEvent.KEYCODE_BACK && (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_DPAD) ||
                AndroidKeyEvent.isGamepadButton(event.keyCode))
        ) {
            val directional = event.keyCode in AndroidKeyEvent.KEYCODE_DPAD_UP..AndroidKeyEvent.KEYCODE_DPAD_RIGHT
            if (event.action == AndroidKeyEvent.ACTION_DOWN && (event.repeatCount == 0 || directional)) keys(event.keyCode)
            return true
        }
        if (event.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_SELECT && togglePanel != null) {
            if (event.action == AndroidKeyEvent.ACTION_UP && !event.isCanceled &&
                !padRouter.select(event.deviceId, event.eventTime - event.downTime)
            ) togglePanel?.invoke()
            return true
        }
        val down = event.action == AndroidKeyEvent.ACTION_DOWN
        val up = event.action == AndroidKeyEvent.ACTION_UP
        if ((down || up) && (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
                event.isFromSource(InputDevice.SOURCE_DPAD) || AndroidKeyEvent.isGamepadButton(event.keyCode))
        ) {
            val routed = padRouter.button(event.deviceId, event.keyCode, lowerPanelAction(event.keyCode),
                down, event.repeatCount > 0, event.eventTime)
            if (routed) return true
            val quickMenu = toggleQuickMenu
            if (quickMenu != null && gameInput.opensQuickMenu(event.keyCode)) {
                if (up && !event.isCanceled) quickMenu()
                return true
            }
        }
        return (controlsActive && gameInput.keyEvent(event)) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val keys = mapKeys
        if (keys != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            val axes = readGamepadAxes(event)
            mapAxes = axes
            val hat = when {
                axes.hatY <= -.5f -> AndroidKeyEvent.KEYCODE_DPAD_UP
                axes.hatY >= .5f -> AndroidKeyEvent.KEYCODE_DPAD_DOWN
                axes.hatX <= -.5f -> AndroidKeyEvent.KEYCODE_DPAD_LEFT
                axes.hatX >= .5f -> AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                else -> null
            }
            if (hat != null && hat != mapHat) keys(hat)
            mapHat = hat
            return true
        }
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            val axes = readGamepadAxes(event)
            if (padRouter.motion(event.deviceId, axes, event.eventTime)) return true
            if (controlsActive) {
                gameInput.gamepadMotion(axes)
                return true
            }
        }
        return (controlsActive && gameInput.motion(event)) || super.dispatchGenericMotionEvent(event)
    }

    internal fun setLowerPanelVisible(visible: Boolean) {
        lowerPanelVisible = visible
        reconcilePadFocus()
    }

    internal fun attachLowerPanelInput(input: (PanelPadAction) -> Unit, pan: (Float, Float) -> Unit): () -> Unit {
        lowerPanelInput = input
        lowerPanelPan = pan
        reconcilePadFocus()
        return {
            if (lowerPanelInput === input) {
                lowerPanelInput = null
                lowerPanelPan = null
                reconcilePadFocus()
            }
        }
    }

    internal fun releaseLowerGamepad() = padRouter.release()

    internal fun tickLowerGamepad() = padRouter.tick(SystemClock.uptimeMillis())

    internal fun setFrameLimits(limit: Int, saver: Boolean) {
        foregroundFpsLimit = limit
        saveBattery = saver
        refreshFrameLimit()
    }

    private fun refreshFrameLimit() {
        NativeJavaBridge.nativeSetFrameLimit(javaFrameLimit(foregroundFpsLimit, saveBattery, resumed))
    }

    private fun reconcilePadFocus() {
        padRouter.setAvailable(controlsActive && resumed && hostFocused && lowerPanelVisible && lowerPanelInput != null)
    }

    // Play-time tracking: counts only while the screen is in front and the game is running.
    @Volatile internal var statsPlaying = false
    private var statsId: String? = null
    private var statsStart = 0L
    private var statsSeconds = 0L
    private val statsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statsBeat = object : Runnable {
        override fun run() {
            val id = statsId ?: return
            if (resumed && statsPlaying) {
                statsSeconds += 10
                PlayStats.beat(applicationContext, id, statsStart, statsSeconds)
            }
            statsHandler.postDelayed(this, 10_000)
        }
    }

    internal fun startStats(instanceId: String) {
        if (statsId != null) return
        statsId = instanceId
        statsStart = System.currentTimeMillis()
        statsSeconds = 0
        PlayStats.begin(applicationContext, instanceId, statsStart)
        statsHandler.postDelayed(statsBeat, 10_000)
    }

    internal fun stopStats() {
        if (statsId == null) return
        statsHandler.removeCallbacks(statsBeat)
        statsId = null
        PlayStats.end(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshFrameLimit()
        reconcilePadFocus()
    }

    override fun onPause() {
        val wasLowerFocused = lowerGamepadFocused
        resumed = false
        refreshFrameLimit()
        reconcilePadFocus()
        if (controlsActive && !wasLowerFocused) gameInput.reset()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CharacterLooks.load(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersiveMode()

        val title = intent.getStringExtra("INSTANCE_TITLE") ?: "Minecraft Java"
        val version = intent.getStringExtra("RESOLVED_VERSION") ?: "1.21.4"
        val mainClass = intent.getStringExtra("MAIN_CLASS") ?: "net.minecraft.client.main.Main"
        val gameDirPath = intent.getStringExtra("GAME_DIR_PATH") ?: filesDir.absolutePath
        val classpath = intent.getStringExtra("CLASSPATH") ?: ""
        val gameArgs = intent.getStringArrayExtra("GAME_ARGS") ?: emptyArray()
        val elyProfileId = intent.getStringExtra(ELY_PROFILE_ID)
        val profileId = elyProfileId ?: intent.getStringExtra(MICROSOFT_PROFILE_ID)
        val account = when {
            elyProfileId != null -> JavaAccountMode.ELY_BY
            profileId != null -> JavaAccountMode.MICROSOFT
            else -> JavaAccountMode.OFFLINE
        }
        val baseJvmArguments = intent.getStringArrayExtra("JVM_ARGS") ?: emptyArray()
        val libJvmPath = intent.getStringExtra("LIBJVM_PATH") ?: ""
        val javaHomePath = intent.getStringExtra("JAVA_HOME_PATH") ?: ""
        val javaHeapMb = intent.getIntExtra("JAVA_HEAP_MB", DEFAULT_JAVA_HEAP_MB)
        val renderScale = RenderScale.fromPercent(intent.getIntExtra("RENDER_SCALE", 100))
        val renderer = JavaRenderer.fromName(intent.getStringExtra("JAVA_RENDERER"))
        val hud = GameHudSettings.fromBundle(intent.getBundleExtra("GAME_HUD"))
        val panelMod = intent.getStringExtra(DUAL_SCREEN_MOD)?.let(::File)?.takeIf { it.isFile }
        val panelBridge = panelMod?.let {
            runCatching { DualScreenBridge.create(cacheDir, File(gameDirPath, "biomeria-map")) }.getOrNull()
        }
        dualScreenBridge = panelBridge
        // Icons for the lower screen come from the jars this game runs with; indexing is lazy.
        if (panelBridge != null) ItemIcons.install(classpath, File(gameDirPath))
        val jvmArguments = if (panelMod != null && panelBridge != null) {
            baseJvmArguments + arrayOf(
                "-Dfabric.addMods=" + panelMod.absolutePath,
                "-Dbiomeria.dualscreen.session=" + panelBridge.directory.absolutePath,
                "-Dbiomeria.map.directory=" + checkNotNull(panelBridge.terrainDirectory).absolutePath
            )
        } else baseJvmArguments
        gameInput = GameInput(
            decodeGamepadActions(intent.getStringExtra("GAMEPAD_BINDINGS").orEmpty()),
            intent.getDoubleExtra("HOTBAR_SCROLL_SCALE", 1.0)
        )
        inputManager = getSystemService(InputManager::class.java)?.also {
            it.registerInputDeviceListener(devices, Handler(Looper.getMainLooper()))
        }

        setContent {
            BiomeriaTheme {
                var token by remember { mutableStateOf(if (profileId == null) "0" else null) }
                var sessionError by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(profileId) {
                    if (profileId != null) {
                        try {
                            token = MinecraftAccounts(applicationContext).launchToken(account, profileId)
                        } catch (error: AccountError) {
                            sessionError = error.message
                        }
                    }
                }
                val accessToken = token
                if (accessToken == null) {
                    Column(Modifier.fillMaxSize().background(Biomeria.colors.surfaceVoid).safeDrawingPadding().padding(20.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(sessionError ?: "Verificando sesión de Minecraft…", color = Biomeria.colors.textMuted)
                        LauncherButton("Volver", { finish() })
                    }
                    return@BiomeriaTheme
                }
                JavaNativeViewportScreen(
                    activity = this@JavaGameActivity,
                    instanceTitle = title,
                    version = version,
                    mainClass = mainClass,
                    gameDirPath = gameDirPath,
                    classpath = classpath,
                    gameArgs = remember(accessToken, gameArgs) { gameArgs + arrayOf("--accessToken", accessToken) },
                    jvmArguments = jvmArguments,
                    libJvmPath = libJvmPath,
                    javaHomePath = javaHomePath,
                    javaHeapMb = javaHeapMb,
                    renderScale = renderScale,
                    renderer = renderer,
                    input = gameInput,
                    initialHud = hud,
                    panelBridge = panelBridge,
                    onHudChanged = { setResult(RESULT_OK, Intent().putExtra("GAME_HUD", it.toBundle())) },
                    onControls = {
                        controlsActive = it
                        reconcilePadFocus()
                    },
                    onTogglePanel = { togglePanel = it },
                    onToggleQuickMenu = { toggleQuickMenu = it },
                    onExitGame = { finish() }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val wasLowerFocused = lowerGamepadFocused
        hostFocused = hasFocus
        reconcilePadFocus()
        if (hasFocus) enableImmersiveMode()
        else if (controlsActive && !wasLowerFocused) gameInput.reset()
    }

    override fun onDestroy() {
        stopStats()
        inputManager?.unregisterInputDeviceListener(devices)
        dualScreenBridge?.directory?.deleteRecursively()
        super.onDestroy()
        exitProcess(0)
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

private fun lowerPanelAction(code: Int): PanelPadAction? = when (code) {
    AndroidKeyEvent.KEYCODE_DPAD_UP -> PanelPadAction.UP
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> PanelPadAction.DOWN
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> PanelPadAction.LEFT
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> PanelPadAction.RIGHT
    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_BUTTON_A -> PanelPadAction.PICK
    AndroidKeyEvent.KEYCODE_BUTTON_X -> PanelPadAction.TRANSFER
    AndroidKeyEvent.KEYCODE_BUTTON_Y -> PanelPadAction.SPLIT
    AndroidKeyEvent.KEYCODE_BUTTON_L2 -> PanelPadAction.DROP
    AndroidKeyEvent.KEYCODE_BUTTON_B -> PanelPadAction.BACK
    AndroidKeyEvent.KEYCODE_BUTTON_L1 -> PanelPadAction.PREVIOUS_TAB
    AndroidKeyEvent.KEYCODE_BUTTON_R1 -> PanelPadAction.NEXT_TAB
    AndroidKeyEvent.KEYCODE_BUTTON_THUMBR -> PanelPadAction.MARK
    AndroidKeyEvent.KEYCODE_BUTTON_START -> PanelPadAction.SEARCH
    else -> null
}

private class ScaledGameSurface(context: Context, private val scale: RenderScale) : SurfaceView(context) {
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (scale != RenderScale.FULL && width > 0 && height > 0) {
            holder.setFixedSize(scale.pixels(width), scale.pixels(height))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun JavaNativeViewportScreen(
    activity: JavaGameActivity,
    instanceTitle: String,
    version: String,
    mainClass: String,
    gameDirPath: String,
    classpath: String,
    gameArgs: Array<String>,
    jvmArguments: Array<String>,
    libJvmPath: String,
    javaHomePath: String,
    javaHeapMb: Int,
    renderScale: RenderScale,
    renderer: JavaRenderer,
    input: GameInput,
    initialHud: GameHudSettings,
    panelBridge: DualScreenBridge?,
    onHudChanged: (GameHudSettings) -> Unit,
    onControls: (Boolean) -> Unit,
    onTogglePanel: ((() -> Unit)?) -> Unit,
    onToggleQuickMenu: ((() -> Unit)?) -> Unit,
    onExitGame: () -> Unit
) {
    var gpuStatus by remember { mutableStateOf("Inicializando superficie EGL…") }
    var showConsole by remember { mutableStateOf(false) }
    var showOverlay by remember { mutableStateOf(true) }
    var menuTab by remember { mutableStateOf(GameMenuTab.LOG) }
    var saving by remember { mutableStateOf(false) }
    var sessionEnded by remember { mutableStateOf(false) }
    var mapExpanded by remember { mutableStateOf(false) }
    var hasPresentedFrame by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf(false) }
    var hud by remember(initialHud) { mutableStateOf(initialHud) }
    var showHudDialog by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var showQuickMenu by remember { mutableStateOf(false) }
    var showLanDialog by remember { mutableStateOf(false) }
    var showInternetDialog by remember { mutableStateOf(false) }
    val roomScope = rememberCoroutineScope()
    val internetRoom = remember(panelBridge, roomScope) { panelBridge?.let { InternetWorldShare(it, roomScope) } }
    DisposableEffect(internetRoom) {
        onDispose { internetRoom?.close() }
    }
    var pendingGameKey by remember { mutableStateOf<Int?>(null) }
    var fps by remember { mutableStateOf<Int?>(null) }
    var jvmStarted by remember { mutableStateOf(false) }
    LaunchedEffect(jvmStarted) {
        activity.statsPlaying = jvmStarted
        val id = activity.intent.getStringExtra("INSTANCE_ID")
        if (jvmStarted && id != null) activity.startStats(id)
    }
    var viewportSize by remember { mutableStateOf("0x0") }
    val logLines = remember { mutableStateListOf<String>() }
    val logListState = rememberLazyListState()

    fun handleBack() {
        when {
            showKeyboard -> showKeyboard = false
            showQuickMenu -> showQuickMenu = false
            showLanDialog -> showLanDialog = false
            showInternetDialog -> showInternetDialog = false
            mapExpanded -> mapExpanded = false
            libJvmPath.isBlank() -> onExitGame()
            else -> showOverlay = !showOverlay
        }
    }

    BackHandler { handleBack() }
    val toggle by rememberUpdatedState({ handleBack() })
    val quickToggle by rememberUpdatedState {
        if (!showKeyboard && !showHudDialog && !showLanDialog && !showInternetDialog && hasPresentedFrame) {
            if (showOverlay) showOverlay = false else {
                menuTab = GameMenuTab.GAME
                showOverlay = true
            }
        }
    }
    DisposableEffect(Unit) {
        onTogglePanel { toggle() }
        onToggleQuickMenu { quickToggle() }
        onDispose {
            onTogglePanel(null)
            onToggleQuickMenu(null)
        }
    }
    val canSendInput = jvmStarted && !showOverlay && !showKeyboard && !showQuickMenu && !showHudDialog &&
        !showLanDialog && !showInternetDialog && !mapExpanded
    val singleScreenMap = hud.showMinimap && !activity.lowerPanelVisible
    val collectTerrain = singleScreenMap || mapExpanded || (hud.dualScreen && hud.panelMap == PanelMapSource.BIOMERIA)
    val selectMap by rememberUpdatedState {
        if (showOverlay || showKeyboard || showQuickMenu || showHudDialog || panelBridge == null || !hasPresentedFrame) false
        else if (mapExpanded) { mapExpanded = false; true }
        else if (captured) { mapExpanded = true; true }
        else false
    }
    DisposableEffect(activity) {
        activity.selectOpensMap = { selectMap() }
        onDispose { activity.selectOpensMap = null }
    }
    val characterLook by CharacterLooks.look.collectAsState()
    val customSkin by CharacterLooks.customRevision.collectAsState()
    LaunchedEffect(panelBridge, characterLook, customSkin) {
        val bridge = panelBridge ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { CharacterLooks.send(activity.applicationContext, bridge, characterLook) } }
    }
    LaunchedEffect(panelBridge, collectTerrain, hud.showCoordinates, hud.showEntities) {
        withContext(Dispatchers.IO) {
            panelBridge?.collectTerrain(collectTerrain, hud.showCoordinates)
            panelBridge?.collectEntities(collectTerrain && hud.showEntities)
        }
    }
    LaunchedEffect(hud.cameraSpeed) { input.lookSpeed = hud.cameraSpeed.factor }
    LaunchedEffect(hud.fpsLimit, hud.batterySaver) {
        activity.setFrameLimits(hud.fpsLimit, hud.batterySaver)
    }
    DualScreenHost(
        activity = activity,
        enabled = hud.dualScreen && hasPresentedFrame,
        bridge = panelBridge,
        title = instanceTitle,
        hud = hud,
        fps = fps,
        allowGameInput = canSendInput && captured,
        allowMenus = canSendInput,
        onHud = { hud = it; onHudChanged(it) },
        onHotbar = { slot -> input.selectHotbarSlot(slot + 1) }
    )
    LaunchedEffect(canSendInput) {
        onControls(canSendInput)
        input.reset()
        if (canSendInput) {
            pendingGameKey?.let { input.tapKey(it) }
            pendingGameKey = null
            while (true) {
                captured = NativeJavaBridge.nativeCursorCaptured()
                input.setCaptured(captured)
                activity.tickLowerGamepad()
                input.tick()
                delay(16L)
            }
        }
    }

    LaunchedEffect(hud.showFps, hasPresentedFrame) {
        fps = null
        if (hud.showFps && hasPresentedFrame) {
            var last = NativeJavaBridge.nativePresentedFrames()
            var previous = SystemClock.elapsedRealtimeNanos()
            while (true) {
                delay(1000L)
                val current = NativeJavaBridge.nativePresentedFrames()
                val now = SystemClock.elapsedRealtimeNanos()
                if (current >= last && now > previous) {
                    fps = ((current - last) * 1_000_000_000.0 / (now - previous)).roundToInt()
                }
                last = current
                previous = now
            }
        }
    }

    // Recoge los registros en tiempo real desde C++ / JVM con auto-scroll
    LaunchedEffect(Unit) {
        while (true) {
            val freshLogs = NativeJavaBridge.nativePollLogs()
            if (!hasPresentedFrame && NativeJavaBridge.nativeHasPresentedFrame()) {
                hasPresentedFrame = true
                showOverlay = false
                menuTab = GameMenuTab.GAME
            }
            if (freshLogs.isNotEmpty()) {
                // Minecraft's main() returned (quit from the game or a crash): go back to Biomeria
                // instead of leaving a black screen. The launcher then reads the log for errors.
                if (freshLogs.any { it.contains("[BIOMERIA] Sesión Java finalizada") || it.contains("[ERROR] Excepción en Minecraft") }) {
                    sessionEnded = true
                }
                logLines.addAll(freshLogs)
                if (logLines.size > 600) {
                    logLines.removeRange(0, logLines.size - 600)
                }
            }
            delay(150L)
        }
    }

    LaunchedEffect(sessionEnded) {
        if (sessionEnded) {
            delay(1500L) // let the last lines of a crash reach the log file
            onExitGame()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ScaledGameSurface(ctx, renderScale).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            gpuStatus = NativeJavaBridge.nativeInitGraphicsSurface(
                                surface = holder.surface,
                                previewOnly = libJvmPath.isBlank()
                            )

                            if (gpuStatus.startsWith("Error:")) {
                                logLines.add("[ERROR] " + gpuStatus)
                            } else if (libJvmPath.isBlank()) {
                                logLines.add("[AVISO] Instala Java 21 ARM64 para iniciar Minecraft.")
                            }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            viewportSize = "${width}x${height}"
                            NativeJavaBridge.nativeSetViewportSize(width, height)
                            input.resize(this@apply.width, this@apply.height, width, height)
                            val waitingForScaledBuffer = renderScale != RenderScale.FULL &&
                                (width != renderScale.pixels(this@apply.width) ||
                                    height != renderScale.pixels(this@apply.height))
                            if (jvmStarted || libJvmPath.isBlank() || gpuStatus.startsWith("Error:") ||
                                width <= 0 || height <= 0 || waitingForScaledBuffer
                            ) return

                            val status = NativeJavaBridge.nativeLaunchJvm(
                                libJvmPath = libJvmPath,
                                javaHomePath = javaHomePath,
                                gameDirPath = gameDirPath,
                                cacheDirPath = ctx.cacheDir.absolutePath,
                                nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir,
                                classpath = classpath,
                                mainClass = mainClass,
                                jvmArguments = jvmArguments,
                                gameArgs = gameArgs + arrayOf("--width", width.toString(), "--height", height.toString()),
                                javaHeapMb = javaHeapMb
                            )
                            jvmStarted = status == 0 || status == 1
                            if (!jvmStarted) logLines.add("[ERROR] No se pudo iniciar el hilo de Java ($status).")
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            NativeJavaBridge.nativeDestroyGraphicsSurface()
                        }
                    })
                }
            }
        )

        if (canSendInput) {
            Box(Modifier.matchParentSize().pointerInteropFilter { event ->
                input.touch(event)
            })
            if (!captured && input.gamepadUsed) {
                Canvas(Modifier.matchParentSize()) {
                    val position = input.menuCursor
                    val center = Offset(position.x * size.width, position.y * size.height)
                    drawCircle(Color.Black.copy(alpha = .75f), radius = 8.dp.toPx(), center = center)
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = center, style = Stroke(1.5.dp.toPx()))
                    drawCircle(Color.White, radius = 1.5.dp.toPx(), center = center)
                }
            }
            if (captured && hud.showTouch) {
                TouchControls(input, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                Row(
                    Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    for (slot in 1..9) TouchButton("$slot", "Casilla $slot", Modifier.size(26.dp, 32.dp)) { down ->
                        if (down) input.selectHotbarSlot(slot)
                    }
                }
            }
            if (hud.showFps) {
                FpsCounter(fps, Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(start = 6.dp, top = 4.dp))
            }
            AchievementHost(panelBridge, hud.achievements,
                remember(activity) { activity.intent.getStringExtra("INSTANCE_ID") ?: instanceTitle },
                remember(activity) { activity.intent.getStringExtra("GAME_DIR_PATH")?.let(::File) },
                lessMotion = hud.reduceMotion)
            BiomeriaTipHost(hud.biomeriaTips, captured && !showOverlay,
                Modifier.align(when (hud.tipCorner) {
                    TipCorner.TOP_START -> Alignment.TopStart
                    TipCorner.TOP_END -> Alignment.TopEnd
                    TipCorner.BOTTOM_START -> Alignment.BottomStart
                    TipCorner.BOTTOM_END -> Alignment.BottomEnd
                }).windowInsetsPadding(WindowInsets.displayCutout).padding(8.dp))
            if (!hud.widgetsBelow || !activity.lowerPanelVisible) {
                GameWidgets(hud, fps, panelBridge,
                    Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(start = 9.dp, top = if (hud.showFps) 24.dp else 9.dp))
            }
            if (singleScreenMap && captured && panelBridge != null) {
                FloatingMap(panelBridge, hud, onOpen = { mapExpanded = true },
                    modifier = Modifier.align(when (hud.mapCorner) {
                        TipCorner.TOP_START -> Alignment.TopStart
                        TipCorner.TOP_END -> Alignment.TopEnd
                        TipCorner.BOTTOM_START -> Alignment.BottomStart
                        TipCorner.BOTTOM_END -> Alignment.BottomEnd
                    }).windowInsetsPadding(WindowInsets.displayCutout).padding(
                        start = 10.dp, end = 10.dp, bottom = 10.dp,
                        // Below the menu button on the right and the indicators on the left.
                        top = when (hud.mapCorner) {
                            TipCorner.TOP_END -> 54.dp
                            TipCorner.TOP_START -> if (hud.showFps || hud.showCoordinates || hud.showBattery) 56.dp else 10.dp
                            else -> 10.dp
                        }))
            }
            GameMenuPill(hud.pauseOpacity, { menuTab = GameMenuTab.GAME; showOverlay = true },
                Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout).padding(6.dp))
        }

        if (mapExpanded && panelBridge != null) {
            BiomeriaMapScreen(panelBridge, hud, onHud = { hud = it; onHudChanged(it) }, axes = activity.mapAxes,
                registerKeys = { activity.mapKeys = it },
                onMenu = { mapExpanded = false; menuTab = GameMenuTab.GAME; showOverlay = true },
                onClose = { mapExpanded = false })
        }
        if (showOverlay) {
            BiomeriaGameMenu(
                title = instanceTitle,
                version = version,
                ready = hasPresentedFrame,
                tab = menuTab,
                onTab = { menuTab = it },
                hud = hud,
                onHud = { hud = it; onHudChanged(it) },
                logLines = logLines,
                technical = "${renderer.label} · $gpuStatus · Vista $viewportSize al ${renderScale.percent} %",
                heapMb = javaHeapMb,
                renderScale = renderScale,
                renderer = renderer,
                canOpenInventory = captured,
                hasPanel = panelBridge != null,
                bridge = panelBridge,
                actions = GameMenuActions(
                    resume = { showOverlay = false },
                    inventory = { showOverlay = false; pendingGameKey = 69 },
                    capture = { showOverlay = false; pendingGameKey = 291 },
                    keyboard = {
                        showOverlay = false
                        if (captured) input.tapKey(84) // GLFW_KEY_T opens the chat with the default controls.
                        showKeyboard = true
                    },
                    minecraftPause = { showOverlay = false; pendingGameKey = 256 }, // GLFW_KEY_ESCAPE
                    lan = { showOverlay = false; showLanDialog = true },
                    internet = { showOverlay = false; showInternetDialog = true },
                    exit = onExitGame,
                    saveAndQuit = panelBridge?.let { bridge -> { if (bridge.requestQuit()) saving = true } }
                ),
                saving = saving
            )
        }
    }
    if (showKeyboard) {
        GameKeyboardDialog(onDismiss = { showKeyboard = false }) { text, pressEnter ->
            input.insertText(text)
            if (pressEnter) input.tapKey(257) // GLFW_KEY_ENTER
            showKeyboard = false
        }
    }
    if (showHudDialog) {
        AlertDialog(
            onDismissRequest = { showHudDialog = false },
            title = { Text("HUD de partida") },
            text = {
                Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
                    GameHudControls(hud) { hud = it; onHudChanged(it) }
                }
            },
            confirmButton = { LauncherButton("Listo", { showHudDialog = false }, primary = true) }
        )
    }
    if (showQuickMenu) {
        GameQuickMenu(
            canOpenInventory = captured,
            hasMap = panelBridge != null,
            mapVisible = hud.showMinimap,
            onClose = { showQuickMenu = false },
            onInventory = { showQuickMenu = false; pendingGameKey = 69 },
            onCapture = { showQuickMenu = false; pendingGameKey = 291 },
            onMap = {
                hud = hud.copy(showMinimap = !hud.showMinimap)
                onHudChanged(hud)
                showQuickMenu = false
            },
            onHud = { showQuickMenu = false; showHudDialog = true },
            onPause = { showQuickMenu = false; showOverlay = true },
            onLan = { showQuickMenu = false; showLanDialog = true },
            onInternet = { showQuickMenu = false; showInternetDialog = true }
        )
    }
    if (showLanDialog && panelBridge != null) {
        LocalWorldShareDialog(activity, panelBridge) { showLanDialog = false }
    }
    if (showInternetDialog && internetRoom != null) {
        InternetWorldShareDialog(internetRoom) { showInternetDialog = false }
    }
}


@Composable
private fun TouchControls(input: GameInput, modifier: Modifier) {
    Row(
        modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        TouchJoystick(input::touchMovement)
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TouchButton("E", "Inventario") { input.key(69, it) }
                TouchButton("Salto", "Saltar") { input.key(32, it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TouchButton("Usar", "Usar objeto") { input.mouseButton(1, it) }
                TouchButton("Golpe", "Atacar") { input.mouseButton(0, it) }
            }
        }
    }
}

@Composable
private fun TouchButton(
    label: String, description: String, modifier: Modifier = Modifier.size(51.dp, 39.dp), onHold: (Boolean) -> Unit
) {
    val action by rememberUpdatedState(onHold)
    Box(
        modifier.background(Color.Black.copy(alpha = .48f), PanelShape)
            .border(1.dp, Color.White.copy(alpha = .35f), PanelShape)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    action(true)
                    try {
                        waitForUpOrCancellation()?.consume()
                    } finally {
                        action(false)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}
