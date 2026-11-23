package com.biomeria.launcher.engine.java

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.components.PanelShape
import kotlin.math.roundToInt

/** One touch action key: where its center sits, measured in dp from the bottom-right corner, and its size. */
internal data class TouchKeyPlace(val right: Float, val bottom: Float, val scale: Float = 1f)

internal enum class TouchKey(val label: String, val description: String, val default: TouchKeyPlace) {
    INVENTORY("E", "Inventario", TouchKeyPlace(135.5f, 62.5f)),
    JUMP("Salto", "Saltar", TouchKeyPlace(80.5f, 62.5f)),
    CHAT("Chat", "Abrir el teclado", TouchKeyPlace(25.5f, 62.5f)),
    USE("Usar", "Usar objeto", TouchKeyPlace(80.5f, 19.5f)),
    ATTACK("Golpe", "Atacar", TouchKeyPlace(25.5f, 19.5f));
}

private const val KEY_WIDTH = 51f
private const val KEY_HEIGHT = 39f

/** Saved positions and sizes of the touch keys, shared by every world. */
internal class TouchLayoutStore(context: Context) {
    private val preferences = context.getSharedPreferences("biomeria_touch_layout", Context.MODE_PRIVATE)

    fun load(): Map<TouchKey, TouchKeyPlace> = TouchKey.entries.associateWith { key ->
        preferences.getString(key.name, null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 3 }?.let { TouchKeyPlace(it[0], it[1], it[2].coerceIn(.6f, 1.6f)) } ?: key.default
    }

    fun save(layout: Map<TouchKey, TouchKeyPlace>) {
        preferences.edit().apply {
            layout.forEach { (key, place) -> putString(key.name, "${place.right},${place.bottom},${place.scale}") }
        }.apply()
    }
}

/** Joystick on the left and the action keys wherever the player placed them. */
@Composable
internal fun TouchControls(
    input: GameInput,
    layout: Map<TouchKey, TouchKeyPlace>,
    modifier: Modifier,
    onChat: () -> Unit
) {
    Box(modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(14.dp)) {
        Box(Modifier.align(Alignment.BottomStart)) { TouchJoystick(input::touchMovement) }
        TouchKey.entries.forEach { key ->
            val place = layout[key] ?: key.default
            PlacedKey(place, Modifier.align(Alignment.BottomEnd)) { size ->
                TouchButton(key.label, key.description, size) { down ->
                    when (key) {
                        TouchKey.INVENTORY -> input.inventoryKey(down)
                        TouchKey.JUMP -> input.key(32, down)
                        TouchKey.CHAT -> if (down) onChat()
                        TouchKey.USE -> input.mouseButton(1, down)
                        TouchKey.ATTACK -> input.mouseButton(0, down)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlacedKey(place: TouchKeyPlace, modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    val density = LocalDensity.current
    val width = KEY_WIDTH * place.scale
    val height = KEY_HEIGHT * place.scale
    val offset = with(density) {
        IntOffset(-(place.right - width / 2).dp.roundToPx(), -(place.bottom - height / 2).dp.roundToPx())
    }
    content(modifier.offset { offset }.size(width.dp, height.dp))
}

/** Full-screen editor: drag a key to move it, pick one and change its size. Game input is paused. */
@Composable
internal fun TouchLayoutEditor(
    initial: Map<TouchKey, TouchKeyPlace>,
    onDone: (Map<TouchKey, TouchKeyPlace>) -> Unit
) {
    var layout by remember { mutableStateOf(initial) }
    var selected by remember { mutableStateOf<TouchKey?>(null) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) {
        // Swallow touches on empty space so nothing reaches the game.
        awaitEachGesture { awaitFirstDown().consume(); waitForUpOrCancellation()?.consume() }
    }) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).padding(14.dp)) {
            val maxRight = maxWidth.value
            val maxBottom = maxHeight.value
            TouchKey.entries.forEach { key ->
                val place = layout[key] ?: key.default
                val chosen = selected == key
                PlacedKey(place, Modifier.align(Alignment.BottomEnd)) { size ->
                    Box(
                        size.background(if (chosen) Color(0xB33C6E1E) else Color.Black.copy(alpha = .55f), PanelShape)
                            .border(2.dp, if (chosen) Color(0xFF7BC74D) else Color(0xCC7BC74D), PanelShape)
                            .pointerInput(key) {
                                detectDragGestures(onDragStart = { selected = key }) { change, drag ->
                                    change.consume()
                                    val current = layout[key] ?: key.default
                                    val dx = with(density) { drag.x.toDp().value }
                                    val dy = with(density) { drag.y.toDp().value }
                                    val halfW = KEY_WIDTH * current.scale / 2
                                    val halfH = KEY_HEIGHT * current.scale / 2
                                    layout = layout + (key to current.copy(
                                        right = (current.right - dx).coerceIn(halfW, maxRight - halfW),
                                        bottom = (current.bottom - dy).coerceIn(halfH, maxBottom - halfH)))
                                }
                            }
                            .pointerInput(key) {
                                awaitEachGesture { awaitFirstDown(requireUnconsumed = false); selected = key }
                            },
                        contentAlignment = Alignment.Center
                    ) { Text(key.label, color = Color.White, fontSize = (11 * place.scale).sp) }
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout).padding(top = 10.dp)
                .widthIn(max = 560.dp).fillMaxWidth(.9f)
                .background(Color(0xF00B0E11), PanelShape).border(2.dp, Color(0xFF7BC74D), PanelShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val key = selected
            Column(Modifier.weight(1f)) {
                Text(if (key == null) "Arrastra una tecla para moverla" else "Tecla: ${key.label} · ${((layout[key]?.scale ?: 1f) * 100).roundToInt()} %",
                    color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                if (key != null) {
                    Slider(layout[key]?.scale ?: 1f, { value ->
                        val current = layout[key] ?: key.default
                        layout = layout + (key to current.copy(scale = (value * 20).roundToInt() / 20f))
                    }, valueRange = .6f..1.6f)
                } else {
                    Text("Elige una para cambiar su tamaño.", color = Color(0xFFB9C4CA), style = MaterialTheme.typography.bodySmall)
                }
            }
            LauncherButton("Restablecer", { layout = TouchKey.entries.associateWith { it.default }; selected = null })
            LauncherButton("Listo", { onDone(layout) }, primary = true)
        }
    }
}

@Composable
internal fun TouchButton(
    label: String, description: String, modifier: Modifier = Modifier.size(51.dp, 39.dp), onHold: (Boolean) -> Unit
) {
    val action by rememberUpdatedState(onHold)
    Box(
        modifier.background(Color.Black.copy(alpha = .48f), PanelShape)
            .border(1.dp, Color.White.copy(alpha = .35f), PanelShape)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    action(true)
                    try {
                        waitForUpOrCancellation()?.consume()
                    } finally {
                        action(false)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}
