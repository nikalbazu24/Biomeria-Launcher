package com.biomeria.launcher.engine.java

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons

/** Sizes of the floating map, from a corner minimap to a large overview. */
private val mapSizes = listOf(96, 128, 160, 200, 240)

/**
 * Map over the game for single-screen consoles (and anyone who prefers it on top). The player
 * picks its size with − and +, which is remembered; ⤢ opens the full interactive map with zoom,
 * centring and markers.
 */
@Composable
internal fun FloatingMap(
    bridge: DualScreenBridge,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("biomeria_java", Context.MODE_PRIVATE) }
    var step by remember { mutableIntStateOf(preferences.getInt("floating-map-size", 1).coerceIn(0, mapSizes.lastIndex)) }
    fun resize(delta: Int) {
        step = (step + delta).coerceIn(0, mapSizes.lastIndex)
        preferences.edit().putInt("floating-map-size", step).apply()
    }
    if (expanded) {
        val close = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            val side = minOf(maxHeight * .9f, maxWidth * .7f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(side * 1.25f, side).clip(RoundedCornerShape(6.dp))
                    .border(3.dp, Color(0xFF0B0E11), RoundedCornerShape(6.dp))) {
                    TerrainMapPane(bridge, Modifier.fillMaxSize(), compact = false)
                }
                BlockButton({ onExpanded(false) }, Modifier.focusRequester(close), BlockButtons.neutral) {
                    Text("Cerrar mapa (B)", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        return
    }
    val size by animateDpAsState(mapSizes[step].dp, label = "map")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(size).clip(RoundedCornerShape(4.dp)).border(3.dp, Color(0xFF0B0E11), RoundedCornerShape(4.dp))) {
            TerrainMapPane(bridge, Modifier.fillMaxSize(), compact = true)
        }
        Row(Modifier.alpha(.85f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MapControl("−", "Achicar el mapa", enabled = step > 0) { resize(-1) }
            MapControl("+", "Agrandar el mapa", enabled = step < mapSizes.lastIndex) { resize(1) }
            MapControl("⤢", "Abrir el mapa completo") { onExpanded(true) }
        }
    }
}

@Composable
private fun MapControl(label: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    BlockButton(onClick, Modifier.size(36.dp).semantics { contentDescription = description }, BlockButtons.neutral, enabled,
        PaddingValues(0.dp)) {
        Text(label, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
    }
}
