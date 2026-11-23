package com.biomeria.launcher.engine.java

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.delay

/** One first-time hint: a title and a sentence with the controller's buttons drawn as keys. */
internal data class BiomeriaTip(val title: String, val parts: List<TipPart>)

private fun line(vararg parts: String) = parts.map { if (it.startsWith("[")) TipPart(it.trim('[', ']'), true) else TipPart(it) }

/** Biomeria's controls with the default buttons, one at a time, in place of Minecraft's keyboard tutorial. */
internal val biomeriaTips = listOf(
    BiomeriaTip("Muévete y mira", line("Camina con el stick izquierdo y mira con el derecho.")),
    BiomeriaTip("Salta y agáchate", line("[A]", "salta,", "[B]", "o", "[R3]", "te agachan y", "[L3]", "corre.")),
    BiomeriaTip("Rompe y coloca", line("[R2]", "rompe o ataca;", "[L2]", "usa o coloca.")),
    BiomeriaTip("Cambia de objeto", line("[LB]", "y", "[RB]", "recorren la barra rápida;", "[Y]", "cambia de mano.")),
    BiomeriaTip("Abre tu inventario", line("Pulsa", "[X]", "y, con doble pantalla, aparece en la pantalla de abajo.")),
    BiomeriaTip("El menú de Biomeria", line("[Select]", "abre el menú de Biomeria y", "[Start]", "pausa el juego."))
)

private const val TIPS_PREFERENCES = "biomeria_tips"
private const val TIPS_SHOWN = "shown"

/**
 * Shows the six hints once per device, while playing: each stays a few seconds and the next waits a
 * little, so they never pile up. Turning "Consejos de Biomeria" off stops them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BiomeriaTipHost(enabled: Boolean, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(TIPS_PREFERENCES, Context.MODE_PRIVATE) }
    var shown by remember { mutableIntStateOf(preferences.getInt(TIPS_SHOWN, 0)) }
    var current by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(enabled, playing, shown) {
        current = null
        if (!enabled || !playing || shown >= biomeriaTips.size) return@LaunchedEffect
        delay(if (shown == 0) 4_000L else 20_000L)
        current = shown
        delay(8_000L)
        current = null
        delay(400L)
        preferences.edit().putInt(TIPS_SHOWN, shown + 1).apply()
        shown += 1
    }
    AnimatedVisibility(current != null, modifier, enter = fadeIn() + slideInVertically { -it / 3 }, exit = fadeOut()) {
        val index = current ?: shown.coerceAtMost(biomeriaTips.lastIndex)
        val tip = biomeriaTips[index]
        Column(Modifier.widthIn(max = 340.dp).background(Color(0xF00B0E11), RoundedCornerShape(6.dp))
            .border(3.dp, Color(0xFF7BC74D), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(12.dp).background(Color(0xFF7BC74D)).border(2.dp, Color(0xFF0B0E11)))
                Text("CONSEJO DE BIOMERIA", Modifier.weight(1f), color = Color(0xFFA6E07E), fontFamily = PixelFont, fontSize = 13.sp)
                Text("${index + 1} de ${biomeriaTips.size}", color = Color(0xFFB9C4CA), fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.labelSmall)
            }
            Text(tip.title, color = Color(0xFFF2F5EF), fontFamily = PixelFont, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tip.parts.forEach { part ->
                    if (part.key) PadKey(part.text)
                    else Text(part.text, color = Color(0xFFDCE3E6), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Se apagan en Opciones › HUD", color = Color(0xFFB9C4CA), fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
