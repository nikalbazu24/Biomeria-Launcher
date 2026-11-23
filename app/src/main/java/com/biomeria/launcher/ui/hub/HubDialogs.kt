package com.biomeria.launcher.ui.hub

import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.components.BiomeriaDialog
import com.biomeria.launcher.ui.components.BiomeriaTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.data.EngineType
import com.biomeria.launcher.data.GameInstance
import com.biomeria.launcher.engine.java.DEFAULT_GAME_VERSION
import com.biomeria.launcher.engine.java.supportedVersions
import com.biomeria.launcher.engine.java.javaLoaders
import com.biomeria.launcher.engine.java.supportsJavaLoader
import com.biomeria.launcher.engine.java.validCatalogVersion
import com.biomeria.launcher.engine.rust.STARLING_MINECRAFT_VERSION
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.components.gamepadClick
import com.biomeria.launcher.ui.theme.Biomeria

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
        EngineType.RUST -> true
    }
    val playable = version in supportedVersions
    BiomeriaDialog(
        onDismissRequest = onDismiss, accent = Biomeria.colors.brandAccent,
        chip = if (instance == null) "Nuevo" else "Mundo", width = 640.dp,
        title = { Text(if (instance == null) "Nueva instancia" else "Gestionar instancia") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                BiomeriaTextField(name, { name = it.take(72) }, modifier = Modifier.fillMaxWidth(),
                    label = "Nombre", singleLine = true)
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
                                color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        EngineType.BEDROCK -> Text("La tarjeta abre la versión de Minecraft instalada en Android.",
                            color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                        EngineType.RUST -> {
                            Text("Abre Starling para Minecraft $STARLING_MINECRAFT_VERSION. Starling gestiona los perfiles y mundos Rust.",
                                color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Text(when (instance.engine) {
                        EngineType.JAVA -> "Java · ${instance.version}"
                        EngineType.BEDROCK -> "Bedrock · Versión instalada en Android"
                        EngineType.RUST -> "Rust · Starling"
                    }, color = Biomeria.colors.textMuted)
                    if (onRemove != null) TextButton(onClick = onRemove) {
                        Text("Quitar de la biblioteca", color = Biomeria.colors.error)
                    }
                }
                if (engine == EngineType.JAVA && playable) {
                    Text("Cargador", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        val loaders = javaLoaders(version)
                        loaders.forEach { choice ->
                            val select = { loader = choice }
                            FilterChip(loader.substringBefore(' ') == choice, select, { Text(choice) },
                                modifier = Modifier.gamepadClick(onClick = select))
                        }
                    }
                    if (loader != "Vanilla") Text("Descarga mods compatibles desde el Taller.",
                        color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            LauncherButton(if (instance == null) "Crear" else "Guardar",
                {
                    val savedVersion = when (engine) {
                        EngineType.JAVA -> version.trim()
                        EngineType.BEDROCK -> instance?.version ?: "Instalada"
                        EngineType.RUST -> STARLING_MINECRAFT_VERSION
                    }
                    val savedLoader = if (engine == EngineType.JAVA && playable) loader else engine.defaultLoader
                    onSave(name.trim(), engine, savedVersion, savedLoader)
                },
                enabled = name.isNotBlank() && (instance != null || validVersion), primary = true)
        },
        dismissButton = { LauncherButton("Cancelar", onDismiss) }
    )
    if (showVersions) MinecraftVersionPicker({ showVersions = false }) { selected ->
        version = selected.version
        if (!supportsJavaLoader(selected.version, loader)) loader = "Vanilla"
        showVersions = false
    }
}

@Composable
internal fun RemoveInstanceDialog(instance: GameInstance, onDismiss: () -> Unit, onRemove: () -> Unit) {
    BiomeriaDialog(
        onDismissRequest = onDismiss, accent = Biomeria.colors.engineRust, chip = "Cuidado",
        title = { Text("¿Quitar " + instance.title + "?") },
        text = { Text("Se quitarán la ficha y el cartucho generado. Los archivos del juego seguirán en el dispositivo.") },
        confirmButton = { BlockButton(onRemove, colors = BlockButtons.danger) { Text("Quitar instancia", fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold) } },
        dismissButton = { LauncherButton("Cancelar", onDismiss, primary = true) }
    )
}
