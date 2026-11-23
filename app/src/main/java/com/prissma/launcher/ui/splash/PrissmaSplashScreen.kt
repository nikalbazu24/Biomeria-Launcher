package com.prissma.launcher.ui.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prissma.launcher.R
import com.prissma.launcher.ui.components.PrissmaMark
import com.prissma.launcher.ui.theme.PixelFont
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SPLASH_MS = 2600L
private const val BAR_BLOCKS = 10

/**
 * Biomeria splash: the block drops and bounces, the title rises, the three biomes pop in
 * and the bar fills one block at a time. Any tap or button skips it, and it is shortened
 * to a brief fade when Android's animations are turned off.
 */
@Composable
fun PrissmaSplashScreen(onSplashFinished: () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var finished by remember { mutableStateOf(false) }
    val finish = {
        if (!finished) {
            finished = true
            onSplashFinished()
        }
    }
    val drop = remember { Animatable(if (reduceMotion) 0f else -1f) }
    val title = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val tags = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val bar = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            bar.snapTo(1f)
            delay(600)
            finish()
            return@LaunchedEffect
        }
        launch { drop.animateTo(0f, spring(dampingRatio = .42f, stiffness = Spring.StiffnessLow)) }
        launch { delay(650); title.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
        launch { delay(900); tags.animateTo(1f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessMediumLow)) }
        launch { delay(700); bar.animateTo(1f, tween((SPLASH_MS - 900).toInt(), easing = LinearEasing)) }
        delay(SPLASH_MS)
        finish()
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val dropPx = with(LocalDensity.current) { 260.dp.toPx() }
    val bobPx = with(LocalDensity.current) { 8.dp.toPx() }
    val floating = rememberInfiniteTransition(label = "float")
    val bob by floating.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val pan by floating.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "pan")
    val rise by floating.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "rise")

    Box(
        Modifier.fillMaxSize().background(Prissma.colors.ink)
            .focusRequester(focus).focusable()
            .onKeyEvent { if (it.type == KeyEventType.KeyUp) { finish(); true } else false }
            .clickable(remember { MutableInteractionSource() }, indication = null) { finish() }
            .semantics { contentDescription = "Biomeria. Toca para continuar." },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painterResource(R.drawable.prissma_valley), null, Modifier.fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.08f; scaleY = 1.08f
                    translationX = -pan * size.width * .03f
                },
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            0f to Color(0xC8080A0D), .7f to Color(0x80080A0D), 1f to Color(0x40080A0D)
        )))
        // Leaves, bubbles and embers drifting up: one for each biome.
        Canvas(Modifier.fillMaxSize()) {
            val specks = listOf(
                Triple(.14f, 0f, Color(0xFF7BC74D)), Triple(.33f, .35f, Color(0xFF8FD3EC)),
                Triple(.52f, .7f, Color(0xFFA6E07E)), Triple(.68f, .2f, Color(0xFFF5B14A)),
                Triple(.84f, .55f, Color(0xFFF07D4A)), Triple(.93f, .85f, Color(0xFF8FD3EC))
            )
            val px = 7.dp.toPx()
            specks.forEach { (x, offset, color) ->
                val t = (rise + offset) % 1f
                val y = size.height * (1.05f - t * .8f)
                val a = when { t < .1f -> t / .1f; t > .85f -> (1f - t) / .15f; else -> 1f }
                drawRect(color.copy(alpha = a * .9f), Offset(size.width * x, y), Size(px, px))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PrissmaMark(Modifier.size(120.dp, 132.dp).graphicsLayer {
                translationY = drop.value * dropPx - (if (drop.value == 0f) bob * bobPx else 0f)
                rotationZ = drop.value * 8f
                alpha = 1f + drop.value * .9f
            })
            Spacer(Modifier.height(14.dp))
            Text(
                "Biomeria", Modifier.alpha(title.value).graphicsLayer { translationY = (1f - title.value) * 40f },
                fontFamily = PixelFont, fontSize = 60.sp, fontWeight = FontWeight.Bold, color = Prissma.colors.textPrimary
            )
            Text(
                "Tres mundos de Minecraft en un solo lugar",
                Modifier.alpha(title.value).padding(top = 6.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = Color(0xFFE3E9EC), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.graphicsLayer { scaleX = tags.value; scaleY = tags.value; alpha = tags.value.coerceIn(0f, 1f) },
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BiomeTag("Java", Prissma.colors.engineJava, Prissma.colors.onBrandAccent)
                BiomeTag("Bedrock", Prissma.colors.engineBedrock, Color(0xFF08202B))
                BiomeTag("Starling", Prissma.colors.engineRust, Color(0xFF2B0E05))
            }
            Spacer(Modifier.height(26.dp))
            BlockBar(bar.value)
        }
    }
}

@Composable
private fun BiomeTag(label: String, color: Color, onColor: Color) {
    Text(
        label, Modifier.background(color, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = onColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold
    )
}

@Composable
private fun BlockBar(progress: Float) {
    val filled = (progress * BAR_BLOCKS).toInt().coerceIn(0, BAR_BLOCKS)
    Row(
        Modifier.background(Prissma.colors.ink).border(2.dp, Prissma.colors.borderSubtle).padding(4.dp)
            .semantics { contentDescription = "Cargando" },
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(BAR_BLOCKS) { index ->
            Box(Modifier.size(22.dp, 14.dp).background(if (index < filled) Prissma.colors.brandAccent else Color(0xFF232B33)))
        }
    }
}
