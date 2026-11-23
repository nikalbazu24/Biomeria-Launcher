package com.prissma.launcher.ui.launch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prissma.launcher.engine.java.GameHudSettings
import com.prissma.launcher.engine.java.GamepadAction
import com.prissma.launcher.engine.java.PanelMapSource
import com.prissma.launcher.engine.java.PanelPage
import com.prissma.launcher.engine.java.defaultGamepadActions
import com.prissma.launcher.engine.java.remappableGamepadButtons
import com.prissma.launcher.engine.java.javaFpsLimits
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
                Text("En partida, el stick izquierdo mueve al jugador y el derecho controla la cámara. Select abre el menú de Biomeria.")
                GameHudControls(hud, onHud)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Doble pantalla", Modifier.weight(1f))
                    Switch(hud.dualScreen, { onHud(hud.copy(dualScreen = it)) })
                }
                if (hud.dualScreen) {
                    Text("Inventario, crafteo y mapas disponibles en Java 1.21.4 con Fabric.",
                        style = MaterialTheme.typography.bodySmall)
                    PanelPreferenceControls(hud, onHud)
                }
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
internal fun PanelPreferenceControls(settings: GameHudSettings, onChange: (GameHudSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelSwitch("Mesa de crafteo abajo", settings.routeCrafting) { onChange(settings.copy(routeCrafting = it)) }
        PanelSwitch("Cofres, tolvas y dispensadores abajo", settings.routeChests) { onChange(settings.copy(routeChests = it)) }
        PanelSwitch("Hornos abajo", settings.routeFurnaces) { onChange(settings.copy(routeFurnaces = it)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ranuras compactas", Modifier.weight(1f))
            Switch(settings.compactPanel, { onChange(settings.copy(compactPanel = it)) })
        }
        Text("Pestaña al iniciar", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelPage.entries.filter { it.canStart }.forEach { page ->
                FilterChip(settings.panelHome == page, { onChange(settings.copy(panelHome = page)) },
                    label = { Text(page.label) })
            }
        }
        Text("Fuente del mapa", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelMapSource.entries.forEach { source ->
                FilterChip(settings.panelMap == source, { onChange(settings.copy(panelMap = source)) },
                    label = { Text(source.label) })
            }
        }
    }
}

@Composable
private fun PanelSwitch(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(enabled, onChange)
    }
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
        PanelSwitch("Minimapa en el juego (Fabric 1.21.4)", settings.showMinimap) {
            onChange(settings.copy(showMinimap = it))
        }
        PanelSwitch("Coordenadas (Fabric 1.21.4)", settings.showCoordinates) { onChange(settings.copy(showCoordinates = it)) }
        PanelSwitch("Nivel de batería", settings.showBattery) { onChange(settings.copy(showBattery = it)) }
        if (settings.dualScreen) {
            PanelSwitch("Indicadores en la pantalla inferior", settings.widgetsBelow) { onChange(settings.copy(widgetsBelow = it)) }
        }
        if (settings.showFps || settings.showCoordinates || settings.showBattery) {
            Text("Tamaño de indicadores", style = MaterialTheme.typography.bodySmall)
            Slider(settings.widgetScale, { onChange(settings.copy(widgetScale = it)) }, valueRange = .75f..1.5f, steps = 5)
            Text("Opacidad de indicadores", style = MaterialTheme.typography.bodySmall)
            Slider(settings.widgetOpacity, { onChange(settings.copy(widgetOpacity = it)) }, valueRange = .25f..1f, steps = 5)
        }
        Text("Límite de FPS", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            javaFpsLimits.forEach { fps ->
                FilterChip(settings.fpsLimit == fps, { onChange(settings.copy(fpsLimit = fps)) },
                    label = { Text(if (fps == 0) "Del juego" else fps.toString()) })
            }
        }
        PanelSwitch("Limitar a 15 FPS en segundo plano", settings.batterySaver) { onChange(settings.copy(batterySaver = it)) }
        Text("Opacidad de pausa · " + (settings.pauseOpacity * 100).toInt() + " %", style = MaterialTheme.typography.bodySmall)
        Slider(settings.pauseOpacity, { onChange(settings.copy(pauseOpacity = it)) }, valueRange = .15f..1f, steps = 16)
    }
}
