package com.biomeria.launcher.engine.java

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Ink = Color(0xFF0B0E11)

private enum class CelebrationPhase { DROP, BURST, CARD, PILL }

/**
 * Watches for advancements sent by the mod and celebrates each one on the top screen:
 * a block in the advancement's colour drops, breaks into pieces, shows a card and leaves a
 * small notice at the top right. With [AchievementStyle.VANILLA] Minecraft's own toast is used.
 */
@Composable
internal fun AchievementHost(
    bridge: DualScreenBridge?,
    style: AchievementStyle,
    instanceId: String,
    gameDir: java.io.File? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val queue = remember { mutableStateListOf<Achievement>() }
    LaunchedEffect(instanceId, gameDir) {
        val folder = gameDir ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { AchievementLog.saveModNames(context.applicationContext, instanceId, readModNames(folder)) }
        }
    }
    LaunchedEffect(bridge, style) {
        val active = bridge ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { active.celebrateAchievements(style != AchievementStyle.VANILLA) } }
        if (style == AchievementStyle.VANILLA) return@LaunchedEffect
        while (true) {
            val fresh = withContext(Dispatchers.IO) {
                active.takeAchievements().onEach { AchievementLog.record(context.applicationContext, instanceId, it) }
            }
            queue += fresh
            delay(400)
        }
    }
    val current = queue.firstOrNull() ?: return
    AchievementCelebration(current, small = style == AchievementStyle.SMALL, hurry = queue.size > 1, modifier = modifier) {
        queue.remove(current)
    }
}

/** Plays one celebration from the start; [onDone] runs when it has fully left the screen. */
@Composable
internal fun AchievementCelebration(
    current: Achievement,
    small: Boolean = false,
    hurry: Boolean = false,
    modifier: Modifier = Modifier,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val style = if (small) AchievementStyle.SMALL else AchievementStyle.FULL
    val finished by rememberUpdatedState(onDone)
    var phase by remember(current) {
        mutableStateOf(if (style == AchievementStyle.SMALL) CelebrationPhase.PILL
            else if (reduceMotion) CelebrationPhase.CARD else CelebrationPhase.DROP)
    }
    val drop = remember(current) { Animatable(-1f) }
    val burst = remember(current) { Animatable(0f) }
    LaunchedEffect(current) {
        if (phase == CelebrationPhase.DROP) {
            drop.animateTo(0f, spring(dampingRatio = .42f, stiffness = 260f))
            delay(160)
            phase = CelebrationPhase.BURST
            launch { burst.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
            delay(120)
            phase = CelebrationPhase.CARD
        }
        if (phase == CelebrationPhase.CARD) {
            delay(3000)
            phase = CelebrationPhase.PILL
        }
        delay(if (hurry) 1600 else 4200)
        finished()
    }

    Box(modifier.fillMaxSize().semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = "${current.kind.banner}: ${current.title}"
    }) {
        val density = LocalDensity.current
        if (phase == CelebrationPhase.DROP) {
            val block = 72.dp
            Box(Modifier.align(Alignment.TopCenter).padding(top = 120.dp).size(block)
                .graphicsLayer {
                    translationY = drop.value * with(density) { 200.dp.toPx() }
                    rotationZ = drop.value * 14f
                }
                .background(current.kind.color).border(4.dp, Ink)
                .drawBehind {
                    drawRect(Color.White.copy(alpha = .25f), size = Size(size.width, 8.dp.toPx()))
                    drawRect(Color.Black.copy(alpha = .3f), Offset(0f, size.height - 10.dp.toPx()), Size(size.width, 10.dp.toPx()))
                }, contentAlignment = Alignment.Center) {
                Text("?", color = Ink, fontFamily = PixelFont, fontSize = 36.sp)
            }
        }
        if (phase == CelebrationPhase.BURST || (phase == CelebrationPhase.CARD && burst.value < 1f)) {
            val kind = current.kind
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, 120.dp.toPx() + 36.dp.toPx())
                val t = burst.value
                val piece = 10.dp.toPx() * (1f - t * .6f)
                for (i in 0 until kind.pieces) {
                    val angle = (i.toFloat() / kind.pieces) * 2 * PI + (i % 3) * .3
                    val distance = (90 + (i * 37 % 70) + if (kind == AchievementKind.CHALLENGE) 70 else 0).dp.toPx() * t
                    val at = center + Offset((cos(angle) * distance).toFloat(), (sin(angle) * distance).toFloat() + 40.dp.toPx() * t * t)
                    val color = if (kind == AchievementKind.CHALLENGE && i % 2 == 1) kind.sparkle else kind.color
                    val alpha = (1f - t).coerceIn(0f, 1f)
                    drawRect(Ink.copy(alpha = alpha), at - Offset(piece / 2 + 2, piece / 2 + 2), Size(piece + 4, piece + 4))
                    drawRect(color.copy(alpha = alpha), at - Offset(piece / 2, piece / 2), Size(piece, piece))
                }
            }
        }
        if (phase == CelebrationPhase.CARD) AchievementCard(current, reduceMotion, Modifier.align(Alignment.TopCenter).padding(top = 96.dp))
        if (phase == CelebrationPhase.PILL) AchievementPill(current, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 14.dp))
    }
}

@Composable
private fun AchievementCard(achievement: Achievement, still: Boolean, modifier: Modifier) {
    val kind = achievement.kind
    val appear = remember(achievement) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(achievement) { appear.animateTo(1f, spring(dampingRatio = .55f, stiffness = 420f)) }
    val pulse = rememberInfiniteTransition(label = "logro")
    val glow by pulse.animateFloat(.35f, if (still) .35f else .85f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "brillo")
    val shine = remember(achievement) { Animatable(if (still) 2f else -.4f) }
    LaunchedEffect(achievement) { delay(200); shine.animateTo(1.4f, tween(1100, easing = FastOutSlowInEasing)) }
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.widthIn(max = 420.dp).graphicsLayer {
            val scale = .86f + .14f * appear.value
            scaleX = scale; scaleY = scale; alpha = appear.value.coerceIn(0f, 1f)
        }
            .drawBehind {
                drawRoundRect(kind.color.copy(alpha = glow * .55f), Offset(-10.dp.toPx(), -10.dp.toPx()),
                    Size(size.width + 20.dp.toPx(), size.height + 20.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))
            }
            .clip(shape).background(Ink).border(3.dp, kind.color, shape)
            .drawWithContent {
                drawContent()
                val x = size.width * shine.value
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .18f), Color.Transparent),
                    startX = x - size.width * .15f, endX = x + size.width * .15f))
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val frame = when (kind) {
            AchievementKind.TASK -> RoundedCornerShape(4.dp)
            AchievementKind.GOAL -> RoundedCornerShape(16.dp)
            AchievementKind.CHALLENGE -> CircleShape
        }
        Box(Modifier.size(64.dp).clip(frame).background(Color(0xFF1A2026)).border(4.dp, kind.color, frame),
            contentAlignment = Alignment.Center) {
            val icon = rememberAchievementIcon(achievement.icon)
            if (icon != null) Image(icon, null, Modifier.size(38.dp), filterQuality = FilterQuality.None)
            else Box(Modifier.size(26.dp).background(kind.color).border(3.dp, Ink))
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(kind.banner, color = kind.color, fontFamily = PixelFont, fontSize = 13.sp, letterSpacing = 1.sp)
            Text(achievement.title, color = Color(0xFFF2F5EF), fontFamily = PixelFont, fontSize = 24.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
            if (achievement.description.isNotBlank()) Text(achievement.description, color = Color(0xFFC3CDD2), fontSize = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun AchievementPill(achievement: Achievement, modifier: Modifier) {
    val enter = remember(achievement) { Animatable(0f) }
    LaunchedEffect(achievement) { enter.animateTo(1f, tween(250)) }
    Row(
        modifier.graphicsLayer { translationX = (1f - enter.value) * 60f; alpha = enter.value }
            .background(Ink.copy(alpha = .9f), RoundedCornerShape(4.dp)).border(2.dp, achievement.kind.color, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = rememberAchievementIcon(achievement.icon)
        if (icon != null) Image(icon, null, Modifier.size(20.dp), filterQuality = FilterQuality.None)
        else Box(Modifier.size(12.dp).background(achievement.kind.color))
        Text(achievement.title, color = Color(0xFFF2F5EF), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
        if (achievement.total > 0) Text("· ${achievement.done} de ${achievement.total}", color = Color(0xFFA9B6BD), fontSize = 12.sp,
            style = TextStyle(fontWeight = FontWeight.Bold))
    }
}
