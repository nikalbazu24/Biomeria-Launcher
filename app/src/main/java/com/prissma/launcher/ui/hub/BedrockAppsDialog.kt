package com.prissma.launcher.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prissma.launcher.engine.bedrock.BedrockInstallation
import com.prissma.launcher.engine.bedrock.BedrockLauncher
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun BedrockAppsDialog(
    current: BedrockInstallation?,
    launcher: BedrockLauncher,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var games by remember(launcher) { mutableStateOf<List<BedrockInstallation>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(launcher) {
        games = withContext(Dispatchers.IO) { launcher.installedGames() }
    }

    fun select(packageName: String?) {
        launcher.choosePackage(packageName)
        onRefresh()
        onDismiss()
    }

    fun openExternal(action: () -> String?) {
        problem = action()
        if (problem == null) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Minecraft en Android") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 245.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val installed = games
                when {
                    installed == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    installed.isEmpty() -> Text("Si ya tienes Minecraft, abre Biomeria en el mismo perfil de Android.")
                    else -> {
                        Text("Elige la aplicación que abrirán las tarjetas Bedrock.")
                        installed.forEach { game ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                LauncherButton(game.label, { select(game.packageName) }, primary = current?.packageName == game.packageName)
                                Text(game.packageName, style = MaterialTheme.typography.bodySmall, color = Prissma.colors.textMuted)
                            }
                        }
                    }
                }
                if (current != null && !current.canOpen) {
                    LauncherButton("Ajustes de Minecraft", { openExternal { launcher.openAppSettings(current.packageName) } })
                }
                if (launcher.preferredPackage != null) {
                    LauncherButton("Detección automática", { select(null) })
                }
                problem?.let { Text(it, color = Prissma.colors.error) }
            }
        },
        dismissButton = { LauncherButton("Cerrar", onDismiss) },
        confirmButton = { LauncherButton("Ver en Google Play", { openExternal(launcher::openStore) }, primary = true) }
    )
}
