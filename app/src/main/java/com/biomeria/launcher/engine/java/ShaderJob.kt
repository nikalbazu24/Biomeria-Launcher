package com.biomeria.launcher.engine.java

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.*

internal enum class ShaderPhase { APPLYING, CANCELLING, DONE, CANCELLED, LATER }

internal class ShaderJobState(val target: String, val previous: String, val phase: ShaderPhase, val startedAt: Long)

/**
 * Changing shaders: writes Iris' settings, asks the game to reload them and keeps everything paused,
 * with a notice on both screens, until the game answers. Cancelling goes back to the previous shaders.
 */
internal object ShaderJob {
    var state by mutableStateOf<ShaderJobState?>(null)
        private set
    /** Goes up after every change, so every shader card reads the settings again. */
    var revision by mutableIntStateOf(0)
        private set
    val busy: Boolean get() = state?.phase.let { it == ShaderPhase.APPLYING || it == ShaderPhase.CANCELLING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cancelRequested = false

    fun start(controller: ShaderController, pack: ShaderPack?) {
        val directory = controller.gameDirectory ?: return
        val before = controller.settings ?: return
        if (busy) return
        val previous = before.active
        if (previous?.file == pack?.file) return
        val target = pack?.title ?: "Sin shaders"
        val previousName = previous?.title ?: "Sin shaders"
        cancelRequested = false
        state = ShaderJobState(target, previousName, ShaderPhase.APPLYING, SystemClock.elapsedRealtime())
        controller.status = "Aplicando shaders…"
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { writeShaderChoice(directory, pack?.file) } }
            controller.settings = before.copy(enabled = pack != null, current = pack?.file ?: before.current)
            val bridge = controller.bridge
            if (bridge == null || !bridge.reloadShaders()) {
                finish(controller, ShaderPhase.LATER, "Guardado · se aplica al volver a abrir el juego")
                return@launch
            }
            val applied = waitForGame(bridge) { cancelRequested && bridge.cancelShaderReload() }
            if (cancelRequested) {
                // Back to what was there before; if the game had already started, it reloads the old ones.
                state = ShaderJobState(target, previousName, ShaderPhase.CANCELLING, SystemClock.elapsedRealtime())
                withContext(Dispatchers.IO) { runCatching { writeShaderChoice(directory, previous?.file) } }
                controller.settings = before
                if (applied != null && bridge.reloadShaders()) waitForGame(bridge) { false }
                finish(controller, ShaderPhase.CANCELLED, "Cancelado · sigues con $previousName")
                return@launch
            }
            when (applied) {
                true -> finish(controller, ShaderPhase.DONE, if (pack == null) "Shaders apagados" else "${pack.title} activado")
                else -> finish(controller, ShaderPhase.LATER, "Se aplicará la próxima vez que abras el juego")
            }
        }
    }

    fun cancel() {
        if (state?.phase == ShaderPhase.APPLYING) cancelRequested = true
    }

    /** Waits for the game's answer: true or false once it reloaded, null if it never did (or [takenBack] cancelled it first). */
    private suspend fun waitForGame(bridge: DualScreenBridge, takenBack: () -> Boolean): Boolean? {
        val until = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < until) {
            if (takenBack()) return null
            withContext(Dispatchers.IO) { bridge.shadersApplied() }?.let { return it }
            delay(120)
        }
        return null
    }

    private suspend fun finish(controller: ShaderController, phase: ShaderPhase, message: String) {
        val now = state ?: return
        controller.status = message
        revision++
        state = ShaderJobState(now.target, now.previous, phase, now.startedAt)
        delay(if (phase == ShaderPhase.DONE) 1400 else 2200)
        state = null
    }
}

/** Four blocks lighting up in turn, Biomeria's "working on it". */
@Composable
internal fun BlockSpinner(color: Color, modifier: Modifier = Modifier.size(40.dp), still: Boolean = false) {
    val time = rememberInfiniteTransition(label = "spinner")
    val t by time.animateFloat(0f, 4f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val cell = size.minDimension * .42f
        val gap = size.minDimension - cell * 2
        listOf(Offset(0f, 0f), Offset(cell + gap, 0f), Offset(cell + gap, cell + gap), Offset(0f, cell + gap)).forEachIndexed { index, at ->
            val phase = ((t - index + 4f) % 4f)
            val on = if (still) 1f else (1f - (phase / 1.6f).coerceIn(0f, 1f)) * .7f + .3f
            val grow = cell * (.55f + .45f * on)
            drawRoundRect(color.copy(alpha = on), Offset(at.x + (cell - grow) / 2, at.y + (cell - grow) / 2), Size(grow, grow),
                CornerRadius(cell * .12f))
        }
    }
}

/** A bar with a light running through it: shader loading has no real percentage, so it does not pretend to. */
@Composable
private fun WorkingBar(color: Color, done: Boolean, modifier: Modifier) {
    val time = rememberInfiniteTransition(label = "bar")
    val x by time.animateFloat(-.35f, 1.05f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing)), label = "x")
    Canvas(modifier.clip(RoundedCornerShape(50)).background(Kit.ink).border(2.dp, Kit.line, RoundedCornerShape(50))) {
        val radius = CornerRadius(size.height / 2)
        if (done) drawRoundRect(color, cornerRadius = radius)
        else drawRoundRect(color, Offset(size.width * x, 0f), Size(size.width * .32f, size.height), radius)
    }
}

@Composable
private fun elapsedSeconds(since: Long): Int {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
    return ((now - since) / 1000).toInt()
}

/** The notice over the game: "Aplicando shaders", how long it has taken, and Cancelar (B). */
@Composable
internal fun ShaderJobOverlay() {
    val job = ShaderJob.state
    var last by remember { mutableStateOf(job) }
    if (job != null) last = job
    val shown = last
    AnimatedVisibility(job != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(260))) {
        if (shown == null) return@AnimatedVisibility
        Box(Modifier.fillMaxSize().background(Color(0xA8080C10)).clickable(remember { MutableInteractionSource() }, null) {},
            contentAlignment = Alignment.Center) {
            val pop = remember { Animatable(.92f) }
            LaunchedEffect(Unit) { pop.animateTo(1f, spring(.62f, 420f)) }
            val accent = when (shown.phase) {
                ShaderPhase.DONE -> Kit.grass
                ShaderPhase.CANCELLED, ShaderPhase.CANCELLING -> Kit.ember
                ShaderPhase.LATER -> Kit.gold
                ShaderPhase.APPLYING -> Kit.lilac
            }
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(.62f).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .background(Color(0xFF101418), RoundedCornerShape(16.dp)).border(3.dp, Kit.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BlockSpinner(accent, Modifier.size(34.dp), still = !ShaderJob.busy)
                    Text(when (shown.phase) {
                        ShaderPhase.APPLYING -> "Aplicando shaders"
                        ShaderPhase.CANCELLING -> "Volviendo a ${shown.previous}"
                        ShaderPhase.DONE -> "Shaders listos"
                        ShaderPhase.CANCELLED -> "Cambio cancelado"
                        ShaderPhase.LATER -> "Shaders guardados"
                    }, color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.title, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text(when (shown.phase) {
                    ShaderPhase.APPLYING -> "Cambiando a ${shown.target}. El juego queda en pausa hasta que esté listo; puede tardar unos segundos."
                    ShaderPhase.CANCELLING -> "El juego ya había empezado a cargarlos. Vuelve a ${shown.previous} y sigue."
                    ShaderPhase.DONE -> "${shown.target} ya está activo. El juego sigue en un momento."
                    ShaderPhase.CANCELLED -> "Sigues con ${shown.previous}."
                    ShaderPhase.LATER -> "El juego no pudo cargarlos ahora. ${shown.target} se usará la próxima vez que abras el juego."
                }, color = Kit.muted, fontWeight = FontWeight.Bold, fontSize = TypeScale.body, lineHeight = TypeScale.body * 1.35f)
                WorkingBar(accent, !ShaderJob.busy, Modifier.fillMaxWidth().height(16.dp))
                if (ShaderJob.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Llevas ${elapsedSeconds(shown.startedAt)} s", Modifier.weight(1f), color = Kit.muted,
                        fontWeight = FontWeight.Bold, fontSize = TypeScale.detail)
                    if (shown.phase == ShaderPhase.APPLYING) CancelButton("Cancelar y volver a ${shown.previous}")
                }
            }
        }
    }
}

@Composable
private fun CancelButton(label: String) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Kit.ember).clickable(role = Role.Button) { ShaderJob.cancel() }
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PadKey("B")
        Text(label, color = Color(0xFF2A0D02), fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.body, maxLines = 1)
    }
}

/** The lower screen while shaders load: everything behind is blocked, with the same Cancelar. */
@Composable
internal fun ShaderJobLowerOverlay() {
    val job = ShaderJob.state
    AnimatedVisibility(job != null && ShaderJob.busy, enter = fadeIn(tween(200)) + scaleIn(initialScale = 1.03f),
        exit = fadeOut(tween(240)) + scaleOut(targetScale = 1.02f)) {
        Box(Modifier.fillMaxSize().background(Color(0xEE0B0E11)).clickable(remember { MutableInteractionSource() }, null) {},
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp)) {
                BlockSpinner(Kit.lilac, Modifier.size(44.dp))
                Text("Esperando los shaders", color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.title,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("El juego está en pausa. Vuelve en unos segundos.", color = Kit.muted, fontWeight = FontWeight.Bold,
                    fontSize = TypeScale.body, textAlign = TextAlign.Center)
                if (ShaderJob.state?.phase == ShaderPhase.APPLYING) CancelButton("Cancelar")
            }
        }
    }
}
