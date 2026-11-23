package com.biomeria.launcher.engine.java

import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.floor

@Composable
internal fun GameWidgets(hud: GameHudSettings, fps: Int?, bridge: DualScreenBridge?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Int?>(null) }
    var position by remember(bridge) { mutableStateOf<TerrainPosition?>(null) }
    LaunchedEffect(hud.showBattery, context) {
        if (hud.showBattery) {
            val manager = context.getSystemService(BatteryManager::class.java)
            while (true) {
                battery = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
                delay(30_000)
            }
        }
    }
    LaunchedEffect(hud.showCoordinates, bridge) {
        if (hud.showCoordinates && bridge != null) {
            while (true) {
                position = withContext(Dispatchers.IO) { bridge.readTerrainPosition() }
                delay(360)
            }
        }
    }
    // The FPS counter has its own small spot in the top-left corner (FpsCounter).
    val labels = buildList {
        if (hud.showCoordinates) position?.let { add("X ${floor(it.x).toInt()}   Y ${floor(it.y).toInt()}   Z ${floor(it.z).toInt()}") }
        if (hud.showBattery) battery?.let { add("Batería $it%") }
    }
    if (labels.isEmpty()) return

    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEach { label ->
            Text(label, Modifier.background(Color(0xff101921).copy(alpha = hud.widgetOpacity), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp), color = Color.White,
                fontSize = (12f * hud.widgetScale).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

/** Small FPS counter for the top-left corner of the game, kept apart from the other indicators. */
@Composable
internal fun FpsCounter(fps: Int?, modifier: Modifier = Modifier) {
    Text(
        "${fps ?: "—"} FPS",
        modifier.background(Color(0xB30B0E11), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color(0xFFE3E9EC), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1
    )
}
