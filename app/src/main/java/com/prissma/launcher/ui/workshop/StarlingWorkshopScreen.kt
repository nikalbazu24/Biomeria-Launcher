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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prissma.launcher.engine.imports.LocalArchiveStorage
import com.prissma.launcher.engine.imports.PreparedArchive
import com.prissma.launcher.engine.rust.InvalidStarlingMod
import com.prissma.launcher.engine.rust.STARLING_MOD_API
import com.prissma.launcher.engine.rust.StarlingInstallation
import com.prissma.launcher.engine.rust.StarlingLauncher
import com.prissma.launcher.engine.rust.StarlingMod
import com.prissma.launcher.engine.rust.StarlingModType
import com.prissma.launcher.engine.rust.launchProblem
import com.prissma.launcher.engine.rust.starlingModExtension
import com.prissma.launcher.engine.rust.validateStarlingMod
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PageHeader
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
internal fun StarlingWorkshopScreen(
    title: String,
    installation: StarlingInstallation?,
    launcher: StarlingLauncher,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val storage = remember(context) { LocalArchiveStorage(context.applicationContext, "starling-imports") }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PreparedArchive<StarlingMod>?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(storage) { onDispose { pending?.let(storage::discard) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null && working == null) {
            pending?.let(storage::discard)
            pending = null
            notice = null
            problem = null
            working = "Copiando el mod…"
            scope.launch {
                try {
                    pending = storage.prepare(source, ::starlingModExtension,
                        onVerify = { working = "Verificando el paquete…" }, validate = ::validateStarlingMod)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: InvalidStarlingMod) {
                    problem = error.message
                } catch (error: SecurityException) {
                    problem = "Android no dio acceso al archivo. Selecciónalo de nuevo."
                } catch (error: IOException) {
                    problem = "No se pudo leer o guardar el mod. Revisa el archivo y el espacio disponible."
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
        PageHeader("Taller de Rust", title, onBack)
        Column(Modifier.fillMaxWidth().background(Prissma.colors.surfaceCard, PanelShape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mods de Starling", style = MaterialTheme.typography.titleLarge)
            Text("Paquetes .hmod · API de Horizon $STARLING_MOD_API", color = Prissma.colors.textMuted)
            Text(installation.launchProblem() ?: checkNotNull(installation).label, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LauncherButton("Importar mod", {
                    try {
                        picker.launch(arrayOf("*/*"))
                    } catch (error: ActivityNotFoundException) {
                        problem = "Android no pudo abrir el selector de archivos."
                    }
                }, primary = pending == null, enabled = working == null)
                if (installation?.canOpen != true) {
                    LauncherButton("Ver Starling ARM64", { problem = launcher.openReleases() }, enabled = working == null)
                }
            }
            working?.let {
                Text(it)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            pending?.let { archive ->
                val mod = archive.content
                Text(mod.name.take(160), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text("${mod.id} · ${mod.version.take(96)} · ${mod.type.label}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                if (mod.type == StarlingModType.NATIVE) {
                    Text("Los mods nativos se compilan desde Opciones → Mods en Starling.", style = MaterialTheme.typography.bodySmall)
                }
                LauncherButton("Enviar a Starling", {
                    problem = launcher.importMod(archive)
                    if (problem == null) {
                        pending = null
                        notice = "Mod enviado. Starling muestra el resultado y gestiona la activación en su perfil."
                    }
                }, primary = true, enabled = installation?.canOpen == true)
            }
            problem?.let { Text(it, color = Prissma.colors.error) }
            if (problem == null) notice?.let { Text(it, color = Prissma.colors.brandAccent) }
        }
    }
}
