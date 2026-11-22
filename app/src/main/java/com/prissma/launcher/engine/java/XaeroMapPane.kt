package com.prissma.launcher.engine.java

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

@Composable
internal fun XaeroMapPane(bridge: DualScreenBridge, inventory: PanelInventory, modifier: Modifier = Modifier) {
    var frame by remember(inventory.generation) { mutableStateOf<XaeroFrame?>(null) }
    val snapshot by rememberUpdatedState(inventory)
    LaunchedEffect(bridge, inventory.generation) {
        while (true) {
            val previous = frame
            frame = withContext(Dispatchers.IO) {
                bridge.readXaeroFrame(inventory.generation, previous) ?: previous
            }
            delay(90)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Xaero’s World Map", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { bridge.send(inventory, PanelCommand.MAP_ZOOM, -1) },
                modifier = Modifier.semantics { contentDescription = "Alejar mapa" },
                enabled = frame != null,
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text("−") }
            OutlinedButton(
                onClick = { bridge.send(inventory, PanelCommand.MAP_ZOOM, 1) },
                modifier = Modifier.semantics { contentDescription = "Acercar mapa" },
                enabled = frame != null,
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text("+") }
            OutlinedButton(onClick = { bridge.send(inventory, PanelCommand.CLOSE_MENU) }) { Text("Cerrar mapa") }
        }
        val current = frame
        if (current == null) {
            Text("Abriendo el mapa…", color = Prissma.colors.textMuted)
        } else {
            val image = remember(current) {
                Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888).apply {
                    copyPixelsFromBuffer(ByteBuffer.wrap(current.rgba))
                }.asImageBitmap()
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val aspect = current.width.toFloat() / current.height
                val width = minOf(maxWidth, maxHeight * aspect)
                val height = width / aspect
                Image(image, contentDescription = "Mapa interactivo de Xaero",
                    modifier = Modifier.size(width, height).pointerInput(bridge, inventory.generation) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            var location = xaeroTouchPosition(down.position.x, down.position.y, size.width, size.height)
                            if (!bridge.send(snapshot, PanelCommand.MAP_DOWN, location)) return@awaitEachGesture
                            var lastMove = SystemClock.uptimeMillis()
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                    location = xaeroTouchPosition(pointer.position.x, pointer.position.y, size.width, size.height)
                                    pointer.consume()
                                    val now = SystemClock.uptimeMillis()
                                    if (pointer.pressed && now - lastMove >= 45L) {
                                        bridge.send(snapshot, PanelCommand.MAP_MOVE, location)
                                        lastMove = now
                                    }
                                } while (pointer.pressed)
                            } finally {
                                bridge.send(snapshot, PanelCommand.MAP_UP, location)
                            }
                        }
                    },
                    contentScale = ContentScale.FillBounds)
            }
        }
    }
}
