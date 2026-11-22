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
import com.prissma.launcher.engine.java.DEFAULT_GAME_VERSION
import com.prissma.launcher.engine.java.supportedVersions
import com.prissma.launcher.engine.java.supportsFabric
import com.prissma.launcher.engine.java.validCatalogVersion
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
    var showVersions by rememberSaveable { mutableStateOf(false) }
    val validVersion = when (engine) {
        EngineType.JAVA -> validCatalogVersion(version)
        EngineType.BEDROCK -> true
        EngineType.RUST -> version.trim().matches(Regex("[A-Za-z0-9._+-]{1,64}"))
    }
    val playable = version in supportedVersions
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
                    when (engine) {
                        EngineType.JAVA -> {
                            Text("Versión de Minecraft", style = MaterialTheme.typography.titleSmall)
                            LauncherButton(version + " · Cambiar", { showVersions = true })
                            if (!playable) Text("Puedes guardar la instancia. Esta versión requiere otro runtime o renderizador.",
                                color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        EngineType.BEDROCK -> Text("La tarjeta abre la versión de Minecraft instalada en Android.",
                            color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                        EngineType.RUST -> {
                            Text("Puedes organizar y exportar esta instancia. Su motor está en preparación.",
                                color = Prissma.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(version, { version = it.take(64) }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("Versión del juego") }, singleLine = true)
                        }
                    }
                } else {
                    Text(if (instance.engine == EngineType.BEDROCK) "Bedrock · Versión instalada en Android"
                        else "${instance.engine.displayName} · ${instance.version}", color = Prissma.colors.textMuted)
                    if (onRemove != null) TextButton(onClick = onRemove) {
                        Text("Quitar de la biblioteca", color = Prissma.colors.error)
                    }
                }
                if (engine == EngineType.JAVA && playable) {
                    Text("Cargador", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        val loaders = if (supportsFabric(version)) listOf("Vanilla", "Fabric") else listOf("Vanilla")
                        loaders.forEach { choice ->
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
                {
                    val savedVersion = if (engine == EngineType.BEDROCK) instance?.version ?: "Instalada" else version.trim()
                    val savedLoader = if (engine == EngineType.JAVA && playable) loader else engine.defaultLoader
                    onSave(name.trim(), engine, savedVersion, savedLoader)
                },
                enabled = name.isNotBlank() && (instance != null || validVersion), primary = true)
        },
        dismissButton = { LauncherButton("Cancelar", onDismiss) }
    )
    if (showVersions) MinecraftVersionPicker({ showVersions = false }) { selected ->
        version = selected.version
        if (!supportsFabric(selected.version)) loader = "Vanilla"
        showVersions = false
    }
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
