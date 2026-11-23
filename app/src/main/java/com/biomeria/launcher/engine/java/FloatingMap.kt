package com.biomeria.launcher.engine.java

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.delay

/**
 * The small map over the game for single-screen consoles. Its corner, size, see-through look,
 * zoom, marks and coordinates come from "Opciones del mapa". Touching it opens the big map.
 */
@Composable
internal fun FloatingMap(bridge: DualScreenBridge, hud: GameHudSettings, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.size(hud.mapSize.side.dp).alpha(if (hud.mapSoft) .6f else 1f).clip(shape)
        .background(Color(0xFF101C23)).border(3.dp, Color(0xFF0B0E11), shape)
        .clickable(onClickLabel = "Abrir el mapa grande", onClick = onOpen)
        .semantics { contentDescription = "Mapa flotante. Toca para abrir el mapa grande" }) {
        TerrainMapPane(bridge, Modifier.fillMaxSize(), compact = true, compactSpan = hud.mapZoom.span,
            compactMarks = hud.mapMarks, compactCoords = hud.mapCoords)
    }
}

private enum class MapScreenTab(val label: String) { MAP("Mapa"), OPTIONS("Opciones del mapa") }

/** One row of "Opciones del mapa": a switch or a set of choices. */
private class MapOption(
    val label: String,
    val detail: String,
    val choices: List<String>,
    val selected: Int,
    val toggle: Boolean,
    val select: (Int) -> Unit
)

/**
 * The big map for single-screen consoles, over the dimmed game: the map with marks and creatures,
 * and the floating map's options. It takes the controller while it is open.
 */
@Composable
internal fun BiomeriaMapScreen(
    bridge: DualScreenBridge,
    hud: GameHudSettings,
    onHud: (GameHudSettings) -> Unit,
    axes: GamepadAxes?,
    registerKeys: (((Int) -> Unit)?) -> Unit,
    onMenu: () -> Unit,
    onClose: () -> Unit
) {
    var tab by remember { mutableStateOf(MapScreenTab.MAP) }
    var terrainPad by remember { mutableStateOf<TerrainPadInput?>(null) }
    var focusRow by remember { mutableIntStateOf(0) }

    val options = listOf(
        MapOption("Mapa flotante", "Un mapa chico siempre visible mientras juegas", listOf("No", "Sí"),
            if (hud.showMinimap) 1 else 0, true) { onHud(hud.copy(showMinimap = it == 1)) },
        MapOption("Esquina", "Cámbiala si te tapa algo del juego", TipCorner.entries.map { it.label },
            hud.mapCorner.ordinal, false) { onHud(hud.copy(mapCorner = TipCorner.entries[it])) },
        MapOption("Tamaño", "Qué tanto espacio ocupa", FloatingMapSize.entries.map { it.label },
            hud.mapSize.ordinal, false) { onHud(hud.copy(mapSize = FloatingMapSize.entries[it])) },
        MapOption("Transparencia", "Suave deja ver el juego por detrás", listOf("Sólido", "Suave"),
            if (hud.mapSoft) 1 else 0, false) { onHud(hud.copy(mapSoft = it == 1)) },
        MapOption("Zoom del mapa flotante", "Cuánto terreno muestra", FloatingMapZoom.entries.map { it.label },
            hud.mapZoom.ordinal, false) { onHud(hud.copy(mapZoom = FloatingMapZoom.entries[it])) },
        MapOption("Criaturas", "Solo en tus mundos, nunca en servidores", listOf("No", "Sí"),
            if (hud.showEntities) 1 else 0, true) { onHud(hud.copy(showEntities = it == 1)) },
        MapOption("Marcas", "Casa, mina y otros lugares que marcaste", listOf("No", "Sí"),
            if (hud.mapMarks) 1 else 0, true) { onHud(hud.copy(mapMarks = it == 1)) },
        MapOption("Coordenadas bajo el mapa", "X y Z de dónde estás", listOf("No", "Sí"),
            if (hud.mapCoords) 1 else 0, true) { onHud(hud.copy(mapCoords = it == 1)) }
    )

    fun switchTab() { tab = if (tab == MapScreenTab.MAP) MapScreenTab.OPTIONS else MapScreenTab.MAP }
    fun step(option: MapOption, delta: Int) {
        val next = if (option.toggle) 1 - option.selected else (option.selected + delta).mod(option.choices.size)
        option.select(next)
    }

    val keys by rememberUpdatedState { code: Int ->
        val pad = terrainPad
        val managing = pad?.managingWaypoints?.invoke() == true
        when {
            code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1 -> if (!managing) switchTab()
            tab == MapScreenTab.MAP && managing -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> pad?.action?.invoke(PanelPadAction.UP)
                KeyEvent.KEYCODE_DPAD_DOWN -> pad?.action?.invoke(PanelPadAction.DOWN)
                KeyEvent.KEYCODE_DPAD_LEFT -> pad?.action?.invoke(PanelPadAction.LEFT)
                KeyEvent.KEYCODE_DPAD_RIGHT -> pad?.action?.invoke(PanelPadAction.RIGHT)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> pad?.action?.invoke(PanelPadAction.PICK)
                KeyEvent.KEYCODE_BUTTON_B -> pad?.action?.invoke(PanelPadAction.BACK)
                KeyEvent.KEYCODE_BUTTON_X -> pad?.action?.invoke(PanelPadAction.TRANSFER)
                KeyEvent.KEYCODE_BUTTON_Y -> pad?.action?.invoke(PanelPadAction.SPLIT)
                KeyEvent.KEYCODE_BUTTON_THUMBR -> pad?.action?.invoke(PanelPadAction.MARK)
                KeyEvent.KEYCODE_BUTTON_START -> pad?.action?.invoke(PanelPadAction.SEARCH)
                else -> Unit
            }
            code == KeyEvent.KEYCODE_BUTTON_B -> onClose()
            tab == MapScreenTab.MAP -> when (code) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> pad?.action?.invoke(PanelPadAction.MARK)
                KeyEvent.KEYCODE_BUTTON_R2 -> pad?.action?.invoke(PanelPadAction.TRANSFER)
                KeyEvent.KEYCODE_BUTTON_L2 -> pad?.action?.invoke(PanelPadAction.SPLIT)
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_THUMBR -> pad?.action?.invoke(PanelPadAction.PICK)
                KeyEvent.KEYCODE_BUTTON_Y -> pad?.action?.invoke(PanelPadAction.SEARCH)
                KeyEvent.KEYCODE_DPAD_UP -> pad?.pan?.invoke(0f, -1f)
                KeyEvent.KEYCODE_DPAD_DOWN -> pad?.pan?.invoke(0f, 1f)
                KeyEvent.KEYCODE_DPAD_LEFT -> pad?.pan?.invoke(-1f, 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> pad?.pan?.invoke(1f, 0f)
                else -> Unit
            }
            else -> when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> focusRow = (focusRow - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_DOWN -> focusRow = (focusRow + 1).coerceAtMost(options.lastIndex)
                KeyEvent.KEYCODE_DPAD_LEFT -> step(options[focusRow], -1)
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> step(options[focusRow], 1)
                else -> Unit
            }
        }
        Unit
    }
    DisposableEffect(Unit) {
        registerKeys { keys(it) }
        onDispose { registerKeys(null) }
    }
    // Either stick moves the map while it is open.
    val latestAxes by rememberUpdatedState(axes)
    LaunchedEffect(tab) {
        if (tab != MapScreenTab.MAP) return@LaunchedEffect
        while (true) {
            val now = latestAxes
            if (now != null) {
                val x = now.moveX + now.lookX
                val y = now.moveY + now.lookY
                if (kotlin.math.abs(x) > .2f || kotlin.math.abs(y) > .2f) {
                    if (terrainPad?.managingWaypoints?.invoke() != true) terrainPad?.pan?.invoke(x * .5f, y * .5f)
                }
            }
            delay(40)
        }
    }

    val density = LocalDensity.current
    val scaled = remember(density, hud.textSize, hud.interfaceSize) {
        Density(density.density * hud.interfaceSize.scale, density.fontScale * hud.textSize.scale)
    }
    CompositionLocalProvider(LocalDensity provides scaled) {
    Box(Modifier.fillMaxSize().background(Color(0x9E080A0C))
        .clickable(remember { MutableInteractionSource() }, null) {}.safeDrawingPadding().padding(10.dp)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF101418), RoundedCornerShape(6.dp))
            .border(3.dp, Color.Black, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF0B0E11)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BlockButton(onClose, Modifier.heightIn(min = 40.dp), BlockButtons.neutral,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text("‹ Volver al juego", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    PadKey("B")
                }
                Box(Modifier.width(2.dp).height(28.dp).background(Color(0xFF232B33)))
                PadKey("LB")
                MapScreenTab.entries.forEach { entry ->
                    BlockButton({ tab = entry }, Modifier.heightIn(min = 38.dp),
                        if (tab == entry) BlockButtons.primary else BlockButtons.neutral,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                        Text(entry.label, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
                PadKey("RB")
                Spacer(Modifier.weight(1f))
                BlockButton(onMenu, Modifier.heightIn(min = 38.dp), BlockButtons.neutral,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text("Menú de Biomeria", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                when (tab) {
                    MapScreenTab.MAP -> TerrainMapPane(bridge, Modifier.fillMaxSize(), compact = false, controllerActive = true,
                        onPadInput = { terrainPad = it })
                    MapScreenTab.OPTIONS -> MapOptionsPage(options, focusRow, hud, { focusRow = it }, Modifier.fillMaxSize())
                }
            }
            Row(Modifier.fillMaxWidth().background(Color(0xFF0B0E11)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Consejo", color = Color(0xFFF5E663), fontFamily = PixelFont, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
                val parts = if (tab == MapScreenTab.MAP) listOf("Mueve el mapa con los sticks, acerca con", "[R2]", "y aleja con", "[L2]",
                    ". Con", "[A]", "marcas dónde estás y con", "[X]", "vuelves a ti.")
                else listOf("Elige con la cruceta y cambia con", "[A]", ". Vuelve al juego con", "[B]")
                parts.forEach { part ->
                    if (part.startsWith("[")) PadKey(part.trim('[', ']'))
                    else Text(part, color = Color(0xFFDCE3E6), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    }
}

@Composable
private fun MapOptionsPage(options: List<MapOption>, focusRow: Int, hud: GameHudSettings, onFocus: (Int) -> Unit, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF161B21), RoundedCornerShape(6.dp))
            .border(3.dp, Color(0xFF232B33), RoundedCornerShape(6.dp)).padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Opciones del mapa", color = Color(0xFFF2F5EF), fontFamily = PixelFont, fontSize = 22.sp)
            options.forEachIndexed { index, option ->
                val focused = index == focusRow
                Row(Modifier.fillMaxWidth().background(Color(0xFF1E252C), RoundedCornerShape(5.dp))
                    .border(2.dp, if (focused) Color(0xFFF5E663) else Color(0xFF1E252C), RoundedCornerShape(5.dp))
                    .then(if (option.toggle) Modifier.clickable(role = Role.Switch) { onFocus(index); option.select(1 - option.selected) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(option.label, color = Color(0xFFF2F5EF), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyMedium)
                        Text(option.detail, color = Color(0xFFA9B6BD), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                    if (option.toggle) {
                        val on = option.selected == 1
                        Box(Modifier.size(60.dp, 32.dp).background(if (on) Color(0xFF7BC74D) else Color(0xFF3A454F), RoundedCornerShape(4.dp))
                            .border(3.dp, Color(0xFF0B0E11), RoundedCornerShape(4.dp)).padding(3.dp),
                            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                            Box(Modifier.size(22.dp).background(Color(0xFFF2F5EF), RoundedCornerShape(2.dp))
                                .border(2.dp, Color(0xFF0B0E11), RoundedCornerShape(2.dp)))
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            option.choices.forEachIndexed { choice, label ->
                                BlockButton({ onFocus(index); option.select(choice) }, Modifier.heightIn(min = 36.dp),
                                    if (choice == option.selected) BlockButtons.primary else BlockButtons.neutral,
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 2.dp)) {
                                    Text(label, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Preview: where the floating map sits on the screen.
        Column(Modifier.width(260.dp).fillMaxHeight().background(Color(0xFF161B21), RoundedCornerShape(6.dp))
            .border(3.dp, Color(0xFF232B33), RoundedCornerShape(6.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Así se verá en el juego", color = Color(0xFFF2F5EF), fontFamily = PixelFont, fontSize = 17.sp)
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF6DB8EA)).border(3.dp, Color(0xFF0B0E11), RoundedCornerShape(4.dp))) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.45f).background(Color(0xFF6FB04A)))
                if (hud.showMinimap) {
                    val side = maxWidth * (hud.mapSize.side / 740f)
                    Box(Modifier.align(when (hud.mapCorner) {
                        TipCorner.TOP_START -> Alignment.TopStart
                        TipCorner.TOP_END -> Alignment.TopEnd
                        TipCorner.BOTTOM_START -> Alignment.BottomStart
                        TipCorner.BOTTOM_END -> Alignment.BottomEnd
                    }).padding(5.dp).size(side).alpha(if (hud.mapSoft) .6f else 1f)
                        .background(Color(0xFF3B8A35), RoundedCornerShape(2.dp)).border(2.dp, Color(0xFF0B0E11), RoundedCornerShape(2.dp)))
                }
            }
            Text("El cuadro verde es el mapa flotante. Cámbialo de esquina, tamaño y transparencia y lo ves aquí al tiro.",
                color = Color(0xFFA9B6BD), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    }
}
