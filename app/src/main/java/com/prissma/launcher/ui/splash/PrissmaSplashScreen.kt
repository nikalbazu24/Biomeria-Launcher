package com.prissma.launcher.ui.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Matriz pixelada de la letra "P" (5 columnas x 7 filas)
private val P_MATRIX = listOf(
    listOf(1, 1, 1, 1, 0),
    listOf(1, 0, 0, 0, 1),
    listOf(1, 0, 0, 0, 1),
    listOf(1, 1, 1, 1, 0),
    listOf(1, 0, 0, 0, 0),
    listOf(1, 0, 0, 0, 0),
    listOf(1, 0, 0, 0, 0)
)

private val PrissmaColors = listOf(
    Color(0xFF00F0FF), // Cian
    Color(0xFF8A2BE2), // Púrpura
    Color(0xFFFF007F), // Magenta
    Color(0xFFFF8C00)  // Naranja
)

private data class PixelNode(
    val col: Int,
    val row: Int,
    val startAngle: Float,
    val startDistance: Float,
    val color: Color
)

@Composable
fun PrissmaSplashScreen(
    onSplashFinished: () -> Unit
) {
    // 1. Generar nodos para cada bloque de la "P" con posiciones iniciales dispersas
    val nodes = remember {
        val random = Random(42)
        val list = mutableListOf<PixelNode>()
        for (r in P_MATRIX.indices) {
            for (c in P_MATRIX[r].indices) {
                if (P_MATRIX[r][c] == 1) {
                    val colorIndex = ((r + c) % PrissmaColors.size)
                    list.add(
                        PixelNode(
                            col = c,
                            row = r,
                            startAngle = random.nextFloat() * 360f,
                            startDistance = 180f + random.nextFloat() * 240f,
                            color = PrissmaColors[colorIndex]
                        )
                    )
                }
            }
        }
        list
    }

    // Estados de animación
    val assemblyProgress = remember { Animatable(0f) } // 0f = disperso, 1f = formado
    val flashAlpha = remember { Animatable(1f) }
    val titleAlpha = remember { Animatable(0f) }
    val subtitleAlpha = remember { Animatable(0f) }
    val screenAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // Fase 1 (0.0s - 0.5s): Destello inicial y comienzo de convergencia
        launch {
            flashAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing)
            )
        }
        // Fase 2 (0.1s - 0.9s): Construcción pixelada de la "P"
        assemblyProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing)
        )
        // Aparición de "PRISSMA"
        titleAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 300)
        )
        // Fase 3 (1.2s - 1.5s): Aparición de "LAUNCHER"
        subtitleAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 250)
        )
        delay(350)
        // Salida suave hacia la pantalla principal
        screenAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 250)
        )
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C12))
            .graphicsLayer { alpha = screenAlpha.value },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Lienzo de la "P" prismática + partículas
            Canvas(modifier = Modifier.size(160.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val progress = assemblyProgress.value
                val inverseProgress = 1f - progress

                // Destello radial inicial (0.0s)
                if (flashAlpha.value > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = flashAlpha.value * 0.9f),
                                Color(0xFF00F0FF).copy(alpha = flashAlpha.value * 0.4f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = size.minDimension * 0.8f
                        ),
                        radius = size.minDimension * 0.8f,
                        center = center
                    )
                }

                // Dimensiones de cada píxel de la matriz (5x7)
                val blockSize = 18.dp.toPx()
                val gap = 2.dp.toPx()
                val totalWidth = 5 * blockSize + 4 * gap
                val totalHeight = 7 * blockSize + 6 * gap
                val originX = center.x - totalWidth / 2f
                val originY = center.y - totalHeight / 2f

                // Gradiente prismático unificado una vez ensamblado
                val prismBrush = Brush.linearGradient(
                    colors = PrissmaColors,
                    start = Offset(originX, originY),
                    end = Offset(originX + totalWidth, originY + totalHeight)
                )

                nodes.forEach { node ->
                    val targetX = originX + node.col * (blockSize + gap)
                    val targetY = originY + node.row * (blockSize + gap)

                    val rad = Math.toRadians(node.startAngle.toDouble())
                    val offsetX = (cos(rad) * node.startDistance * inverseProgress).toFloat()
                    val offsetY = (sin(rad) * node.startDistance * inverseProgress).toFloat()

                    val currentX = targetX + offsetX
                    val currentY = targetY + offsetY

                    // Mientras viaja es una partícula cuadrada pequeña; al llegar toma tamaño completo
                    val currentSize = blockSize * (0.35f + 0.65f * progress)

                    if (progress < 0.95f) {
                        drawRoundRect(
                            color = node.color.copy(alpha = 0.4f + 0.6f * progress),
                            topLeft = Offset(currentX, currentY),
                            size = Size(currentSize, currentSize),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                    } else {
                        drawRect(
                            brush = prismBrush,
                            topLeft = Offset(targetX, targetY),
                            size = Size(blockSize, blockSize)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Texto principal: PRISSMA
            Text(
                text = "PRISSMA",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 6.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = titleAlpha.value
                    translationY = (1f - titleAlpha.value) * 12f
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Subtítulo: LAUNCHER
            Text(
                text = "LAUNCHER",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = subtitleAlpha.value
                }
            )
        }
    }
}
