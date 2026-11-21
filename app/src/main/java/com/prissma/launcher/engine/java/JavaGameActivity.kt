package com.prissma.launcher.engine.java

import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.prissma.launcher.ui.theme.Prissma
import com.prissma.launcher.ui.theme.PrissmaTheme
import kotlinx.coroutines.delay

class JavaGameActivity : ComponentActivity() {

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
        val libJvmPath = intent.getStringExtra("LIBJVM_PATH") ?: ""
        val javaHomePath = intent.getStringExtra("JAVA_HOME_PATH") ?: ""
        val useVulkanZink = intent.getBooleanExtra("USE_VULKAN_ZINK", true)

        setContent {
            PrissmaTheme {
                JavaNativeViewportScreen(
                    instanceTitle = title,
                    version = version,
                    mainClass = mainClass,
                    gameDirPath = gameDirPath,
                    classpath = classpath,
                    gameArgs = gameArgs,
                    libJvmPath = libJvmPath,
                    javaHomePath = javaHomePath,
                    useVulkanZink = useVulkanZink,
                    onExitGame = { finish() }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
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

@Composable
private fun JavaNativeViewportScreen(
    instanceTitle: String,
    version: String,
    mainClass: String,
    gameDirPath: String,
    classpath: String,
    gameArgs: Array<String>,
    libJvmPath: String,
    javaHomePath: String,
    useVulkanZink: Boolean,
    onExitGame: () -> Unit
) {
    BackHandler(onBack = onExitGame)

    var gpuStatus by remember { mutableStateOf("Inicializando superficie gráfica EGL/Vulkan...") }
    var showConsole by remember { mutableStateOf(true) }
    var jvmStarted by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf("0x0") }
    val logLines = remember { mutableStateListOf<String>() }
    val logListState = rememberLazyListState()

    // Recoge los registros en tiempo real desde C++ / JVM con auto-scroll
    LaunchedEffect(Unit) {
        while (true) {
            val freshLogs = NativeJavaBridge.nativePollLogs()
            if (freshLogs.isNotEmpty()) {
                logLines.addAll(freshLogs)
                if (logLines.size > 150) {
                    logLines.removeRange(0, logLines.size - 150)
                }
                if (showConsole && logLines.isNotEmpty()) {
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
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ButtonB, Key.Escape -> {
                        onExitGame()
                        true
                    }
                    Key.ButtonSelect, Key.ButtonY -> {
                        showConsole = !showConsole
                        true
                    }
                    else -> false
                }
            }
    ) {
        // 1. Lienzo nativo Android SurfaceView conectado a EGL / OpenGL ES / Vulkan
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            gpuStatus = NativeJavaBridge.nativeInitGraphicsSurface(
                                surface = holder.surface,
                                useVulkanZink = useVulkanZink
                            )

                            if (!jvmStarted && libJvmPath.isNotBlank()) {
                                jvmStarted = true
                                NativeJavaBridge.nativeLaunchJvm(
                                    libJvmPath = libJvmPath,
                                    javaHomePath = javaHomePath,
                                    gameDirPath = gameDirPath,
                                    classpath = classpath,
                                    mainClass = mainClass,
                                    gameArgs = gameArgs,
                                    useVulkanZink = useVulkanZink
                                )
                            } else if (libJvmPath.isBlank()) {
                                logLines.add("[AVISO] Falta instalar el paquete JRE 21 ARM64. Vuelve atrás [B] para descargarlo o importarlo.")
                            }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            viewportSize = "${width}x${height}"
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            NativeJavaBridge.nativeDestroyGraphicsSurface()
                        }
                    })
                }
            }
        )

        // 2. HUD flotante superior (telemetría GPU + resolución + controles)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC0B0E14), RoundedCornerShape(12.dp))
                    .border(1.dp, Prissma.colors.borderSubtle, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$instanceTitle · MC $version ($viewportSize)",
                        color = Prissma.colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = gpuStatus,
                        color = Prissma.colors.brandAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showConsole = !showConsole }) {
                        Text(
                            text = if (showConsole) "Ocultar Logs [Y]" else "Ver Logs [Y]",
                            color = Prissma.colors.textPrimary,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = onExitGame,
                        colors = ButtonDefaults.buttonColors(containerColor = Prissma.colors.engineJava)
                    ) {
                        Text(
                            text = "Salir [B]",
                            color = Prissma.colors.surfaceVoid,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // 3. Consola en tiempo real de la JVM (opcional con botón Y)
            if (showConsole) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                        .background(Color(0xD9080A0F), RoundedCornerShape(12.dp))
                        .border(1.dp, Prissma.colors.engineJava.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
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
                                    color = if (line.contains("[ERROR]")) Color(0xFFF87171) else Prissma.colors.textPrimary,
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
}
