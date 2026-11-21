package com.prissma.launcher.ui.launch

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import com.prissma.launcher.engine.java.DOWNLOAD_MESSAGE
import com.prissma.launcher.engine.java.DOWNLOAD_PERCENT
import com.prissma.launcher.engine.java.GameDownloads
import com.prissma.launcher.engine.java.JavaGameActivity
import com.prissma.launcher.engine.java.JavaRuntimeManager
import com.prissma.launcher.engine.java.MojangDownloader
import com.prissma.launcher.engine.java.PreparedJavaGame
import com.prissma.launcher.engine.java.RUNTIME_WORK
import com.prissma.launcher.engine.java.applyMinecraftOptions
import com.prissma.launcher.engine.java.gameLanguages
import com.prissma.launcher.engine.java.supportedVersions
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
fun JavaLaunchScreen(instance: GameInstance, playerName: String, accountType: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { context.getSharedPreferences("prissma_java", Context.MODE_PRIVATE) }
    val downloads = remember(context) { GameDownloads(context) }
    val runtimeManager = remember(context) { JavaRuntimeManager(context) }
    val downloader = remember(context) { MojangDownloader(context) }
    val runtimeWork by remember(downloads) { downloads.observe(RUNTIME_WORK) }.collectAsState(emptyList())
    val gameWork by remember(downloads, instance) { downloads.observe(downloads.key(instance)) }.collectAsState(emptyList())
    val runtimeTask = runtimeWork.firstOrNull { !it.state.isFinished } ?: runtimeWork.lastOrNull()
    val gameTask = gameWork.firstOrNull { !it.state.isFinished } ?: gameWork.lastOrNull()
    var runtime by remember(runtimeManager) { mutableStateOf(runtimeManager.getInstalledRuntime()) }
    var game by remember(instance) { mutableStateOf<PreparedJavaGame?>(null) }
    var language by remember(instance.id) { mutableStateOf(settings.getString("language-" + instance.id, "").orEmpty()) }
    var launchError by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    val supported = instance.version in supportedVersions &&
        (instance.loader == "Vanilla" || instance.configuredModLoader == "fabric")

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
                val gameDir = File(context.filesDir, "instances/" + instance.id)
                withContext(Dispatchers.IO) { applyMinecraftOptions(gameDir, language) }
                val classpath = runtimeManager.buildClasspath(ready)
                val playerUuid = UUID.nameUUIDFromBytes("OfflinePlayer:$playerName".toByteArray(Charsets.UTF_8))
                val gameArgs = runtimeManager.buildMinecraftArgs(
                    instance.id, instance.version, playerName, playerUuid.toString(), ready.assetsDir,
                    ready.assetIndexId, accountType
                )
                context.startActivity(Intent(context, JavaGameActivity::class.java).apply {
                    putExtra("INSTANCE_TITLE", instance.title)
                    putExtra("RESOLVED_VERSION", instance.version)
                    putExtra("MAIN_CLASS", ready.mainClass)
                    putExtra("GAME_DIR_PATH", gameDir.absolutePath)
                    putExtra("CLASSPATH", classpath)
                    putExtra("GAME_ARGS", gameArgs)
                    putExtra("LIBJVM_PATH", installed.libJvmFile.absolutePath)
                    putExtra("JAVA_HOME_PATH", installed.javaHomeDir.absolutePath)
                    putExtra("USE_VULKAN_ZINK", false)
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
                if (supported) DownloadRow("Minecraft " + instance.version, game != null, gameTask) { downloads.game(instance) }
                else Text("La versión o el cargador de esta instancia requiere otro runtime.", color = Prissma.colors.error)
                launchError?.let { Text(it, color = Prissma.colors.error) }
                LauncherButton(if (opening) "Abriendo…" else "Jugar", ::start,
                    primary = true, icon = Icons.Default.PlayArrow, enabled = runtime != null && game != null && !opening)
            }
        }
        val optionsPanel: @Composable (Modifier) -> Unit = { modifier ->
            LaunchPanel("Idioma de Minecraft", modifier) {
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
