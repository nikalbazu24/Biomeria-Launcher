package com.biomeria.launcher.engine.java

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val JOYSTICK_TRAVEL = .33f

@Composable
internal fun TouchJoystick(onMove: (Float, Float) -> Unit) {
    val move by rememberUpdatedState(onMove)
    var direction by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        Modifier.size(110.dp)
            .semantics { contentDescription = "Joystick de movimiento" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val travel = minOf(size.width, size.height) * JOYSTICK_TRAVEL

                    fun update(position: Offset) {
                        val relative = (position - center) / travel
                        direction = relative / relative.getDistance().coerceAtLeast(1f)
                        move(direction.x, direction.y)
                    }

                    try {
                        update(down.position)
                        while (true) {
                            val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (pointer == null || pointer.isConsumed) break
                            pointer.consume()
                            if (!pointer.pressed) break
                            update(pointer.position)
                        }
                    } finally {
                        direction = Offset.Zero
                        move(0f, 0f)
                    }
                }
            }
    ) {
        val baseRadius = size.minDimension * .48f
        val thumbCenter = center + direction * (size.minDimension * JOYSTICK_TRAVEL)
        drawCircle(Color.Black.copy(alpha = .45f), radius = baseRadius)
        drawCircle(Color.White.copy(alpha = .36f), radius = baseRadius, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = .2f), radius = size.minDimension * .29f, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = .32f), radius = 15.dp.toPx(), center = thumbCenter)
        drawCircle(Color.White.copy(alpha = .70f), radius = 15.dp.toPx(), center = thumbCenter, style = Stroke(1.dp.toPx()))
    }
}
