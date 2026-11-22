package com.prissma.launcher.ui.workshop

import android.content.ActivityNotFoundException
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.engine.rust.StarlingInstallation
import com.prissma.launcher.engine.rust.StarlingLauncher
import com.prissma.launcher.engine.rust.launchProblem
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.PageHeader
import com.prissma.launcher.ui.components.PanelShape
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun StarlingWorkshopScreen(
    instance: GameInstance,
    installation: StarlingInstallation?,
    launcher: StarlingLauncher,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) {
            checking = true
            problem = null
            scope.launch {
                try {
                    val name = withContext(Dispatchers.IO) {
                        resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: source.lastPathSegment.orEmpty().substringAfterLast('/')
                    }
                    problem = if (name.endsWith(".hmod", ignoreCase = true)) launcher.open(instance, source)
                        else "Selecciona un paquete .hmod compatible con Horizon 0.2."
                } catch (error: SecurityException) {
                    problem = "Android no dio acceso al archivo. Selecciónalo de nuevo."
                } catch (error: IOException) {
                    problem = "No se pudo leer el archivo seleccionado."
                } finally {
                    checking = false
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
            },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageHeader("Taller de Rust", instance.title, onBack)
        Column(Modifier.fillMaxWidth().background(Prissma.colors.surfaceCard, PanelShape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mods de Starling", style = MaterialTheme.typography.titleLarge)
            Text("Elige un .hmod de Horizon 0.2. El motor integrado comprueba el paquete y muestra el resultado en su taller.")
            LauncherButton("Importar mod", {
                try {
                    picker.launch(arrayOf("*/*"))
                } catch (error: ActivityNotFoundException) {
                    problem = "Android no pudo abrir el selector de archivos."
                }
            }, primary = true, enabled = installation?.canOpen == true && !checking)
            if (checking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            (problem ?: installation.launchProblem())?.let { Text(it, color = Prissma.colors.error) }
        }
    }
}
