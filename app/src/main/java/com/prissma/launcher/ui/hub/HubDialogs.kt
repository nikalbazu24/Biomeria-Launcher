package com.prissma.launcher.ui.hub

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prissma.launcher.data.EngineType
import com.prissma.launcher.data.GameInstance
import com.prissma.launcher.data.SavedProfile
import com.prissma.launcher.engine.java.DEFAULT_GAME_VERSION
import com.prissma.launcher.engine.java.supportedVersions
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.theme.Prissma

@Composable
internal fun InstanceEditor(
    instance: GameInstance? = null,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onSave: (String, EngineType, String, String) -> Unit
) {
    var name by rememberSaveable(instance?.id) { mutableStateOf(instance?.title.orEmpty()) }
    var engine by rememberSaveable(instance?.id) { mutableStateOf(instance?.engine ?: EngineType.JAVA) }
    var version by rememberSaveable(instance?.id) { mutableStateOf(instance?.version ?: DEFAULT_GAME_VERSION) }
    var loader by rememberSaveable(instance?.id) { mutableStateOf(instance?.loader ?: "Vanilla") }
    val validVersion = if (engine.canLaunch) version in supportedVersions
        else version.trim().matches(Regex("[A-Za-z0-9._+-]{1,64}"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (instance == null) "Nueva instancia" else "Gestionar instancia") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it.take(72) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nombre") }, singleLine = true)
                if (instance == null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EngineType.entries.forEach { type ->
                            val select = { engine = type }
                            FilterChip(engine == type, select, { Text(type.displayName) },
                                modifier = Modifier.gamepadClick(onClick = select))
                        }
                    }
                    if (!engine.canLaunch) {
                        Text("Puedes organizar y exportar esta instancia. Su motor está en preparación.",
                            color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (engine.canLaunch) {
                        Text("Versión de Minecraft", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            supportedVersions.forEach { game ->
                                val choose = { version = game }
                                FilterChip(version == game, choose, { Text(game) },
                                    modifier = Modifier.gamepadClick(onClick = choose))
                            }
                        }
                    } else OutlinedTextField(version, { version = it.take(64) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Versión del juego") }, singleLine = true)
                } else {
                    Text(instance.engine.displayName + " · " + instance.version, color = Prissma.colors.textMuted)
                    if (onRemove != null) TextButton(onClick = onRemove) {
                        Text("Quitar de la biblioteca", color = Prissma.colors.error)
                    }
                }
                if (engine.canLaunch) {
                    Text("Cargador", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("Vanilla", "Fabric").forEach { choice ->
                            val select = { loader = choice }
                            FilterChip(loader.substringBefore(' ') == choice, select, { Text(choice) },
                                modifier = Modifier.gamepadClick(onClick = select))
                        }
                    }
                    if (loader.startsWith("Fabric")) Text("Descarga mods compatibles desde el Taller.",
                        color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            LauncherButton(if (instance == null) "Crear" else "Guardar",
                { onSave(name.trim(), engine, version.trim(), if (engine.canLaunch) loader else engine.defaultLoader) },
                enabled = name.isNotBlank() && (instance != null || validVersion), primary = true)
        },
        dismissButton = { LauncherButton("Cancelar", onDismiss) }
    )
}

@Composable
internal fun ProfileDialog(profile: SavedProfile, onDismiss: () -> Unit, onSave: (SavedProfile) -> Unit) {
    var name by rememberSaveable { mutableStateOf(profile.playerName) }
    var type by rememberSaveable { mutableStateOf(profile.accountType) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Perfil de juego") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(32) }, label = { Text("Nombre de jugador") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Offline", "Ely.by", "Microsoft").forEach {
                        val select = { type = it }
                        FilterChip(type == it, select, { Text(it) }, modifier = Modifier.gamepadClick(onClick = select))
                    }
                }
                Text("El perfil se guarda en este dispositivo. Los servicios externos requieren su propio inicio de sesión.",
                    color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { LauncherButton("Guardar", { onSave(SavedProfile(name.trim(), type)) },
            primary = true, enabled = name.isNotBlank()) },
        dismissButton = { LauncherButton("Cancelar", onDismiss) }
    )
}

@Composable
internal fun RemoveInstanceDialog(instance: GameInstance, onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Quitar " + instance.title + "?") },
        text = { Text("Se quitarán la ficha y el cartucho generado. Los archivos del juego seguirán en el dispositivo.") },
        confirmButton = { LauncherButton("Quitar instancia", onRemove) },
        dismissButton = { LauncherButton("Cancelar", onDismiss, primary = true) }
    )
}
