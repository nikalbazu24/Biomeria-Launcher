package com.prissma.launcher.engine.java

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
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.theme.Prissma
import com.prissma.launcher.ui.theme.PrissmaTheme
import com.prissma.launcher.ui.launch.GameHudControls
import com.prissma.launcher.data.accounts.AccountError
import com.prissma.launcher.data.accounts.JavaAccountMode
import com.prissma.launcher.data.accounts.MinecraftAccounts
import kotlinx.coroutines.delay
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

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
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
            runCatching { DualScreenBridge.create(cacheDir, File(gameDirPath, "prissma-map")) }.getOrNull()
        }
        dualScreenBridge = panelBridge
        val jvmArguments = if (panelMod != null && panelBridge != null) {
            baseJvmArguments + arrayOf(
                "-Dfabric.addMods=" + panelMod.absolutePath,
                "-Dprissma.dualscreen.session=" + panelBridge.directory.absolutePath,
                "-Dprissma.map.directory=" + checkNotNull(panelBridge.terrainDirectory).absolutePath
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
            PrissmaTheme {
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
                    Column(Modifier.fillMaxSize().background(Prissma.colors.surfaceVoid).safeDrawingPadding().padding(20.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(sessionError ?: "Verificando sesión de Minecraft…", color = Prissma.colors.textMuted)
                        LauncherButton("Volver", { finish() })
                    }
                    return@PrissmaTheme
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
    AndroidKeyEvent.KEYCODE_BUTTON_X -> PanelPadAction.SPLIT
    AndroidKeyEvent.KEYCODE_BUTTON_Y -> PanelPadAction.TRANSFER
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
    var viewportSize by remember { mutableStateOf("0x0") }
    val logLines = remember { mutableStateListOf<String>() }
    val logListState = rememberLazyListState()

    fun handleBack() {
        when {
            showKeyboard -> showKeyboard = false
            showQuickMenu -> showQuickMenu = false
            showLanDialog -> showLanDialog = false
            showInternetDialog -> showInternetDialog = false
            libJvmPath.isBlank() -> onExitGame()
            else -> showOverlay = !showOverlay
        }
    }

    BackHandler { handleBack() }
    val toggle by rememberUpdatedState({ handleBack() })
    val quickToggle by rememberUpdatedState {
        if (!showOverlay && !showKeyboard && !showHudDialog && !showLanDialog && !showInternetDialog && hasPresentedFrame) {
            showQuickMenu = !showQuickMenu
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
        !showLanDialog && !showInternetDialog
    val collectTerrain = hud.showMinimap || (hud.dualScreen && hud.panelMap == PanelMapSource.PRISSMA)
    LaunchedEffect(panelBridge, collectTerrain, hud.showCoordinates) {
        withContext(Dispatchers.IO) { panelBridge?.collectTerrain(collectTerrain, hud.showCoordinates) }
    }
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
            }
            if (freshLogs.isNotEmpty()) {
                logLines.addAll(freshLogs)
                if (logLines.size > 150) {
                    logLines.removeRange(0, logLines.size - 150)
                }
                if (showOverlay && showConsole) {
                    logListState.scrollToItem(logLines.lastIndex)
                }
            }
            delay(150L)
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
            if (!hud.widgetsBelow || !activity.lowerPanelVisible) {
                GameWidgets(hud, fps, panelBridge,
                    Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(start = 9.dp, top = if (hud.showFps) 24.dp else 9.dp))
            }
            if (hud.showMinimap && captured && panelBridge != null) {
                TerrainMapPane(panelBridge,
                    Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(8.dp).size(114.dp), compact = true)
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.displayCutout).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (hasPresentedFrame) {
                    LauncherButton("Atajos", { showQuickMenu = true })
                    LauncherButton("Teclado", {
                        if (captured) input.tapKey(84) // GLFW_KEY_T abre el chat con los controles predeterminados.
                        showKeyboard = true
                    })
                }
                LauncherButton(
                    "II", { showOverlay = true },
                    modifier = Modifier.graphicsLayer { alpha = hud.pauseOpacity }
                        .semantics { contentDescription = "Menú de Biomeria" }
                )
            }
        }

        if (showOverlay) Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Prissma.colors.surfaceGlass, PanelShape)
                    .border(1.dp, Prissma.colors.borderSubtle, PanelShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$instanceTitle · MC $version ($viewportSize · ${renderScale.percent}%)",
                        color = Prissma.colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (gpuStatus.startsWith("Error:")) gpuStatus else "${renderer.label} · $gpuStatus",
                        color = Prissma.colors.brandAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (jvmStarted) {
                        LauncherButton(if (hasPresentedFrame) "Reanudar" else "Ocultar panel",
                            { showOverlay = false }, primary = true)
                    }
                    if (hasPresentedFrame) {
                        LauncherButton("HUD", { showHudDialog = true })
                    }
                    LauncherButton(
                        if (showConsole) "Ocultar registro" else "Ver registro",
                        { showConsole = !showConsole }
                    )
                    LauncherButton("Salir", onExitGame)
                }
            }

            if (showConsole) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(.40f)
                        .background(Prissma.colors.surfaceGlass, PanelShape)
                        .border(1.dp, Prissma.colors.borderSubtle, PanelShape)
                        .padding(12.dp)
                ) {
                    if (logLines.isEmpty()) {
                        Text(
                            text = "Esperando salida del motor Java...",
                            color = Prissma.colors.textMuted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        LazyColumn(
                            state = logListState,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(logLines) { line ->
                                Text(
                                    text = line,
                                    color = if (line.contains("[ERROR]")) Prissma.colors.error else Prissma.colors.textPrimary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
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
