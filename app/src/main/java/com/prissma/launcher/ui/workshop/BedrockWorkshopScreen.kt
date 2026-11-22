package com.prissma.launcher.ui.workshop

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prissma.launcher.engine.bedrock.BedrockImportDelivery
import com.prissma.launcher.engine.bedrock.BedrockImportStorage
import com.prissma.launcher.engine.bedrock.BedrockInstallation
import com.prissma.launcher.engine.bedrock.BedrockLauncher
import com.prissma.launcher.engine.bedrock.InvalidBedrockArchive
import com.prissma.launcher.engine.bedrock.PreparedBedrockImport
import com.prissma.launcher.engine.bedrock.launchProblem
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PageHeader
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.hub.BedrockAppsDialog
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
internal fun BedrockWorkshopScreen(
    instanceTitle: String,
    installation: BedrockInstallation?,
    launcher: BedrockLauncher,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val storage = remember(context) { BedrockImportStorage(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PreparedBedrockImport?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var hasError by rememberSaveable { mutableStateOf(false) }
    var chooseApp by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(storage) { onDispose { pending?.let(storage::discard) } }

    fun report(message: String, error: Boolean = false) {
        notice = message
        hasError = error
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null && working == null) {
            pending?.let(storage::discard)
            pending = null
            notice = null
            working = "Copiando el archivo…"
            scope.launch {
                try {
                    pending = storage.prepare(source) { name -> working = "Verificando $name…" }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: InvalidBedrockArchive) {
                    report(error.message.orEmpty(), error = true)
                } catch (error: IOException) {
                    report("No se pudo preparar el archivo. Revisa que esté disponible y que quede espacio libre.", error = true)
                } catch (error: SecurityException) {
                    report("Android no dio acceso al archivo. Selecciónalo de nuevo.", error = true)
                } finally {
                    working = null
                }
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
        PageHeader("Taller de Bedrock", instanceTitle, onBack)
        Column(Modifier.fillMaxWidth().background(Prissma.colors.surfaceCard, PanelShape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Importar contenido local", style = MaterialTheme.typography.titleLarge)
            Text("Paquetes .mcpack · complementos .mcaddon · mundos .mcworld", color = Prissma.colors.textMuted)
            Text(installation.launchProblem() ?: checkNotNull(installation).label, style = MaterialTheme.typography.bodyMedium)
            LauncherButton("Importar archivo", {
                try {
                    picker.launch(arrayOf("*/*"))
                } catch (error: ActivityNotFoundException) {
                    report("Android no pudo abrir el selector de archivos.", error = true)
                }
            }, primary = pending == null, enabled = working == null)
            LauncherButton(if (installation?.canOpen == true) "Cambiar aplicación" else "Buscar Minecraft", { chooseApp = true })

            working?.let { message ->
                Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            pending?.let { archive ->
                Text("${archive.content.label} verificado", style = MaterialTheme.typography.titleSmall, color = Prissma.colors.brandAccent)
                Text(archive.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val target = installation?.takeIf { it.canOpen }
                if (target != null) {
                    LauncherButton("Abrir en Minecraft", {
                        when (launcher.importArchive(archive, target.packageName)) {
                            BedrockImportDelivery.SENT -> {
                                pending = null
                                report("Enviado a ${target.title}. Revisa el aviso de importación dentro del juego.")
                            }
                            BedrockImportDelivery.NO_GAME -> {
                                onRefresh()
                                report("Minecraft dejó de estar disponible. Vuelve a elegir la aplicación.", error = true)
                            }
                            BedrockImportDelivery.NO_IMPORTER -> {
                                report("Android no pudo abrir un importador de .${archive.content.extension} en ${target.title}.", error = true)
                            }
                        }
                    }, primary = true)
                }
            }
            notice?.let { message ->
                Text(message, color = if (hasError) Prissma.colors.error else Prissma.colors.brandAccent,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (chooseApp) BedrockAppsDialog(installation, launcher, onRefresh, onDismiss = { chooseApp = false })
}
