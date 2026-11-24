package com.biomeria.launcher.engine.java

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.ui.components.BiomeriaMark
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.components.BlockColors
import com.biomeria.launcher.ui.components.PanelShape
import com.biomeria.launcher.ui.theme.Biomeria
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

private fun block(color: Long, dark: Long, ink: Long = 0xFF0E1A08) =
    BlockColors(Color(color), Color(color).copy(alpha = 1f).let { lerpLight(it) }, Color(dark), Color(ink))

private fun lerpLight(color: Color) = Color(
    red = color.red + (1 - color.red) * .35f, green = color.green + (1 - color.green) * .35f,
    blue = color.blue + (1 - color.blue) * .35f, alpha = 1f
)

internal class PauseActions(
    val resume: () -> Unit,
    val options: () -> Unit,
    val advancements: () -> Unit,
    val share: () -> Unit,
    val biomeriaMenu: () -> Unit,
    val vanilla: () -> Unit,
    val saveAndQuit: () -> Unit
)

/** Biomeria's pause menu over the paused world, on the top screen: lively colours, the time, a tip and the character. */
@Composable
internal fun BiomeriaPauseMenu(
    title: String,
    version: String,
    sessionStart: Long,
    bridge: DualScreenBridge?,
    actions: PauseActions,
    saving: Boolean
) {
    val palette = Biomeria.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    val minutes = ((now - sessionStart) / 60_000).coerceAtLeast(0)
    val tip = remember { biomeriaTips.random() }
    val glow = rememberInfiniteTransition(label = "pausa")
    val shift by glow.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "luz")
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) { in 5..11 -> "Buenos días"; in 12..19 -> "Buenas tardes"; else -> "Buenas noches" }

    Row(
        Modifier.fillMaxSize().background(Color(0x99060809)).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && (event.key == Key.ButtonB || event.key == Key.ButtonStart)) {
                actions.resume(); true
            } else false
        }
    ) {
        Column(
            Modifier.fillMaxHeight().widthIn(min = 260.dp, max = 330.dp).fillMaxWidth(.36f)
                .background(Brush.verticalGradient(listOf(Color(0xF20B1A10), Color(0xF20B0E11))))
                .drawBehind {
                    val strip = 5.dp.toPx()
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF7BC74D), Color(0xFF4FB3D9), Color(0xFFF07D4A)),
                        startY = -size.height * shift, endY = size.height * (2 - shift)),
                        Offset(size.width - strip, 0f), Size(strip, size.height))
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BiomeriaMark(Modifier.size(28.dp, 30.dp))
                Column {
                    Text("Juego en pausa", fontFamily = PixelFont, fontSize = TypeScale.section, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("$title · $version", color = palette.textMuted, style = PanelText.detail,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(2.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                PauseButton("▶  Volver al juego", BlockButtons.primary, Modifier.focusRequester(focus), onClick = actions.resume)
                PauseButton("⚙  Opciones", block(0xFF4FB3D9, 0xFF2E86B5, 0xFF08202B), onClick = actions.options)
                PauseButton("★  Logros", block(0xFFF5E663, 0xFFC9B93A, 0xFF2A2508), onClick = actions.advancements)
                PauseButton("⇄  Jugar con amigos", block(0xFFB48CE0, 0xFF7E5BAE, 0xFF1C1028), onClick = actions.share)
                PauseButton("▦  Menú de Biomeria", BlockButtons.neutral, onClick = actions.biomeriaMenu)
                PauseButton("Menú original de Minecraft", BlockButtons.neutral, onClick = actions.vanilla)
            }
            PauseButton(if (saving) "Guardando el mundo…" else "Guardar y salir", BlockButtons.danger, enabled = !saving,
                onClick = actions.saveAndQuit)
            Text("A elegir · B o Start volver", color = palette.textMuted, style = PanelText.detail)
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(greeting, fontFamily = PixelFont, fontSize = TypeScale.title, color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoCard("Esta sesión", if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min",
                    Color(0xFF7BC74D), Modifier.weight(1f))
                InfoCard("Hora", String.format(java.util.Locale.ROOT, "%02d:%02d", hour, Calendar.getInstance().get(Calendar.MINUTE)),
                    Color(0xFF4FB3D9), Modifier.weight(1f))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xE6101519), PanelShape)
                    .border(3.dp, Color(0xFF7BC74D), PanelShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CONSEJO", fontFamily = PixelFont, fontSize = TypeScale.detail, color = Color(0xFFA6E07E))
                    Text(tip.title, fontFamily = PixelFont, fontSize = TypeScale.section, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(tip.parts.joinToString(" ") { it.text }, color = Color(0xFFDCE3E6), style = PanelText.body)
                }
                if (bridge != null) {
                    val look = rememberCharacterLook()
                    val bob by glow.animateFloat(0f, 6f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "salto")
                    Box(Modifier.fillMaxHeight().widthIn(min = 110.dp).background(Color(0xCC101519), PanelShape)
                        .border(3.dp, Color(0xFF2C343A), PanelShape).padding(10.dp), contentAlignment = Alignment.Center) {
                        CharacterFigure(look, bridge, back = false, height = 150.dp, modifier = Modifier.offset(y = (-bob).dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseButton(label: String, colors: BlockColors, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    BlockButton(onClick, modifier.fillMaxWidth().heightIn(min = 46.dp), colors, enabled,
        PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        Text(label, Modifier.fillMaxWidth(), fontWeight = FontWeight.ExtraBold, style = PanelText.body,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoCard(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.background(Color(0xE6101519), PanelShape).border(3.dp, color, PanelShape).padding(12.dp)) {
        Text(label, color = color, fontFamily = PixelFont, fontSize = TypeScale.detail)
        Text(value, color = Color.White, fontFamily = PixelFont, fontSize = TypeScale.title, fontWeight = FontWeight.Bold)
    }
}
