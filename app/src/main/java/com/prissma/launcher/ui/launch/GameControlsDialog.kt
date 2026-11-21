package com.prissma.launcher.ui.launch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prissma.launcher.engine.java.GameHudSettings
import com.prissma.launcher.engine.java.GamepadAction
import com.prissma.launcher.engine.java.defaultGamepadActions
import com.prissma.launcher.engine.java.remappableGamepadButtons
import com.prissma.launcher.ui.components.LauncherButton

@Composable
internal fun GameControlsDialog(
    bindings: Map<Int, GamepadAction>,
    onBindings: (Map<Int, GamepadAction>) -> Unit,
    hud: GameHudSettings,
    onHud: (GameHudSettings) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Controles y HUD") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("En menús, mueve el cursor con un stick, pulsa A para confirmar y B para volver.")
                Text("En partida, el stick izquierdo mueve al jugador y el derecho controla la cámara. Select abre PRISSMA.")
                GameHudControls(hud, onHud)
                HorizontalDivider()
                Text("Botones en partida", style = MaterialTheme.typography.titleSmall)
                remappableGamepadButtons.forEach { (code, button) ->
                    var expanded by remember(code) { mutableStateOf(false) }
                    val action = bindings[code] ?: defaultGamepadActions.getValue(code)
                    Box {
                        LauncherButton("$button · " + action.label, { expanded = true }, Modifier.fillMaxWidth())
                        DropdownMenu(expanded, { expanded = false }) {
                            GamepadAction.entries.filter { it.bindable }.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                    expanded = false
                                    onBindings(bindings + (code to option))
                                })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { LauncherButton("Listo", onDismiss, primary = true) },
        dismissButton = { LauncherButton("Restablecer mando", { onBindings(emptyMap()) }) }
    )
}

@Composable
internal fun GameHudControls(settings: GameHudSettings, onChange: (GameHudSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Controles táctiles", Modifier.weight(1f))
            Switch(settings.showTouch, { onChange(settings.copy(showTouch = it)) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Contador de FPS", Modifier.weight(1f))
            Switch(settings.showFps, { onChange(settings.copy(showFps = it)) })
        }
        Text("Opacidad de pausa · " + (settings.pauseOpacity * 100).toInt() + " %", style = MaterialTheme.typography.bodySmall)
        Slider(settings.pauseOpacity, { onChange(settings.copy(pauseOpacity = it)) }, valueRange = .15f..1f, steps = 16)
    }
}
