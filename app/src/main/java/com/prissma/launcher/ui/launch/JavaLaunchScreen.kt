package com.prissma.launcher.ui.launch

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.engine.java.JavaGameActivity
import com.prissma.launcher.engine.java.JavaRuntimeManager
import com.prissma.launcher.engine.java.MojangDownloader
import com.prissma.launcher.engine.java.PreparedJavaSession
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PageHeader
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

@Composable
fun JavaLaunchScreen(instance: GameInstance, playerName: String, accountType: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember(context) { MojangDownloader(context) }
    val runtimeManager = remember(context) { JavaRuntimeManager(context) }
    val settings = remember(context) { context.getSharedPreferences("prissma_java", Context.MODE_PRIVATE) }

    var session by remember(instance.id) { mutableStateOf<PreparedJavaSession?>(null) }
    var runtime by remember(runtimeManager) { mutableStateOf(runtimeManager.getInstalledRuntime()) }
    var zink by rememberSaveable(instance.id) { mutableStateOf(settings.getBoolean(instance.id, true)) }
    var gameProgress by remember(instance.id) { mutableFloatStateOf(0f) }
    var gameStatus by remember(instance.id) { mutableStateOf("Consultando versiones de Mojang…") }
    var gameError by remember { mutableStateOf<String?>(null) }
    var runtimeStatus by remember { mutableStateOf<String?>(null) }
    var runtimeProgress by remember { mutableStateOf<Float?>(null) }
    var opening by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    fun installRuntime(uri: Uri? = null) {
        if (runtimeProgress != null) return
        runtimeProgress = 0f
        runtimeStatus = null
        scope.launch {
            val report: (Float, String) -> Unit = { pct, status ->
                runtimeProgress = pct
                runtimeStatus = status
            }
            val result = if (uri == null) {
                runtimeManager.installFromUrl(JavaRuntimeManager.DEFAULT_JRE21_URL, report)
            } else {
                runtimeManager.installFromLocalUri(uri, report)
            }
            result.onSuccess {
                runtime = it
                runtimeStatus = "Java 21 listo."
            }.onFailure { runtimeStatus = it.message ?: "No se pudo instalar Java 21." }
            runtimeProgress = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { installRuntime(it) }
    }

    fun start() {
        val ready = session ?: return
        if (opening) return
        opening = true
        gameError = null
        scope.launch {
            try {
                val classpath = runtimeManager.buildClasspath(ready.instanceId, ready.clientJarFile)
                val gameArgs = runtimeManager.buildMinecraftArgs(
                    ready.instanceId, ready.resolvedVersion, ready.playerName, ready.playerUuid,
                    ready.assetsDir, ready.assetIndexId
                )
                context.startActivity(Intent(context, JavaGameActivity::class.java).apply {
                    putExtra("INSTANCE_TITLE", ready.instanceTitle)
                    putExtra("RESOLVED_VERSION", ready.resolvedVersion)
                    putExtra("MAIN_CLASS", ready.mainClass)
                    putExtra("GAME_DIR_PATH", File(context.filesDir, "instances/" + ready.instanceId).absolutePath)
                    putExtra("CLASSPATH", classpath)
                    putExtra("GAME_ARGS", gameArgs)
                    putExtra("LIBJVM_PATH", runtime?.libJvmFile?.absolutePath.orEmpty())
                    putExtra("JAVA_HOME_PATH", runtime?.javaHomeDir?.absolutePath.orEmpty())
                    putExtra("USE_VULKAN_ZINK", zink)
                })
            } catch (error: IOException) {
                gameError = error.message ?: "No se pudo preparar la biblioteca Java."
            } finally {
                opening = false
            }
        }
    }

    LaunchedEffect(instance.id, retry) {
        gameError = null
        gameProgress = 0f
        session = null
        downloader.prepareJavaInstance(
            instance.id, instance.title, instance.version, playerName, accountType,
            onProgress = { pct, status -> gameProgress = pct; gameStatus = status }
        ).onSuccess {
            session = it
            gameStatus = "Minecraft " + it.resolvedVersion + " · Archivos listos"
        }.onFailure { gameError = it.message ?: "No se pudieron preparar los archivos de Mojang." }
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
        PageHeader("Preparar juego", instance.title + " · Minecraft " + instance.version, onBack)
        val runtimePanel: @Composable (Modifier) -> Unit = { modifier ->
            LaunchPanel("Java 21 · ARM64", modifier) {
                Text(if (runtime != null) "Runtime instalado" else "Instala Java para iniciar Minecraft.",
                    color = if (runtime != null) Prissma.colors.brandAccent else Prissma.colors.textMuted)
                runtimeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                runtimeProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LauncherButton(if (runtime != null) "Reinstalar" else "Descargar", { installRuntime() },
                        enabled = runtimeProgress == null && !opening)
                    LauncherButton("Importar ZIP", { picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = runtimeProgress == null && !opening)
                }
            }
        }
        val gamePanel: @Composable (Modifier) -> Unit = { modifier ->
            LaunchPanel("Archivos del juego", modifier) {
                Text(gameStatus, color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { gameProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                gameError?.let {
                    Text(it, color = Prissma.colors.error, style = MaterialTheme.typography.bodySmall)
                    LauncherButton("Reintentar", { retry += 1 })
                }
                Spacer(Modifier.height(2.dp))
                Text("Backend gráfico", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "Vulkan / Zink", false to "OpenGL ES").forEach { (value, label) ->
                        val choose = {
                            zink = value
                            settings.edit().putBoolean(instance.id, value).apply()
                        }
                        FilterChip(zink == value, choose, { Text(label) }, modifier = Modifier.gamepadClick(onClick = choose))
                    }
                }
                Text("Esta beta arranca con MobileGlues (OpenGL ES).", color = Prissma.colors.textMuted,
                    style = MaterialTheme.typography.bodySmall)
                if (session != null) LauncherButton(
                    if (runtime != null) "Iniciar Minecraft" else "Probar pantalla GPU",
                    ::start, primary = true, icon = Icons.Default.PlayArrow,
                    enabled = !opening && runtimeProgress == null
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 535.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    gamePanel(Modifier.weight(1.2f))
                    runtimePanel(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    gamePanel(Modifier.fillMaxWidth())
                    runtimePanel(Modifier.fillMaxWidth())
                }
            }
        }
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
