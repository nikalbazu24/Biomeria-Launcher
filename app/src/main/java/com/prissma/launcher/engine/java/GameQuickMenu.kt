package com.prissma.launcher.engine.java

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.prissma.launcher.ui.components.LauncherButton

@Composable
internal fun GameQuickMenu(
    canOpenInventory: Boolean,
    hasMap: Boolean,
    mapVisible: Boolean,
    onClose: () -> Unit,
    onInventory: () -> Unit,
    onCapture: () -> Unit,
    onMap: () -> Unit,
    onHud: () -> Unit,
    onPause: () -> Unit,
    onLan: () -> Unit,
    onInternet: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 8.dp) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Atajos", style = MaterialTheme.typography.titleMedium)
                LauncherButton(if (mapVisible) "Ocultar mapa" else "Mostrar mapa", onMap,
                    enabled = hasMap)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LauncherButton("Inventario", onInventory, Modifier.weight(1f), enabled = canOpenInventory)
                    LauncherButton("Captura", onCapture, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LauncherButton("HUD", onHud, Modifier.weight(1f))
                    LauncherButton("Pausa", onPause, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LauncherButton("Sala LAN", onLan, Modifier.weight(1f), enabled = hasMap && canOpenInventory)
                    LauncherButton("Por internet", onInternet, Modifier.weight(1f), enabled = hasMap)
                }
                LauncherButton("Volver", onClose, Modifier.focusRequester(focus), primary = true)
            }
        }
    }
}
