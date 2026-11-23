package com.biomeria.launcher.engine.java

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.data.LaunchReports
import com.biomeria.launcher.data.LogLevel
import com.biomeria.launcher.data.classifyLogLine
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.components.ControlShape
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.components.LogFilter
import com.biomeria.launcher.ui.components.LogView
import com.biomeria.launcher.ui.components.PanelShape
import com.biomeria.launcher.ui.components.BiomeriaMark
import com.biomeria.launcher.ui.components.filterLog
import com.biomeria.launcher.ui.components.gamepadClick
import com.biomeria.launcher.ui.launch.GameHudControls
import com.biomeria.launcher.ui.launch.PanelPreferenceControls
import com.biomeria.launcher.ui.theme.PixelFont
import com.biomeria.launcher.ui.theme.Biomeria
import kotlinx.coroutines.launch

internal enum class GameMenuTab(val label: String, val color: Color) {
    GAME("Partida", Color(0xFF7BC74D)),
    CHARACTER("Personaje", Color(0xFFE0B48C)),
    PERFORMANCE("Rendimiento", Color(0xFFF5E663)),
    HUD("Controles y HUD", Color(0xFF4FB3D9)),
    LOG("Registro", Color(0xFFF07D4A)),
    EXIT("Salir del mundo", Color(0xFFFF8A6B))
}

private fun browsableTabs(hasPanel: Boolean) = listOfNotNull(GameMenuTab.GAME, GameMenuTab.CHARACTER.takeIf { hasPanel },
    GameMenuTab.PERFORMANCE, GameMenuTab.HUD, GameMenuTab.LOG)

/** What the game menu can do; the activity owns the game, the menu only asks for actions. */
internal class GameMenuActions(
    val resume: () -> Unit,
    val inventory: () -> Unit,
    val capture: () -> Unit,
    val keyboard: () -> Unit,
    val minecraftPause: () -> Unit,
    val lan: () -> Unit,
    val internet: () -> Unit,
    val exit: () -> Unit,
    /** Saves the world and closes Minecraft properly; null when this version has no Biomeria mod. */
    val saveAndQuit: (() -> Unit)? = null
)

/**
 * The Biomeria menu over the game: a rail of sections on the left and the chosen one on
 * the right. B returns to the game, L1 and R1 switch sections.
 */
@Composable
internal fun BiomeriaGameMenu(
    title: String,
    version: String,
    ready: Boolean,
    tab: GameMenuTab,
    onTab: (GameMenuTab) -> Unit,
    hud: GameHudSettings,
    onHud: (GameHudSettings) -> Unit,
    logLines: List<String>,
    technical: String,
    heapMb: Int,
    renderScale: RenderScale,
    renderer: JavaRenderer,
    canOpenInventory: Boolean,
    hasPanel: Boolean,
    actions: GameMenuActions,
    saving: Boolean = false,
    bridge: DualScreenBridge? = null
) {
    val palette = Biomeria.colors
    val browsableTabs = browsableTabs(hasPanel)
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { resumeFocus.requestFocus() } }
    Row(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
            val index = browsableTabs.indexOf(tab).coerceAtLeast(0)
            when (event.key) {
                Key.ButtonL1 -> { onTab(browsableTabs[(index + browsableTabs.size - 1) % browsableTabs.size]); true }
                Key.ButtonR1 -> { onTab(browsableTabs[(index + 1) % browsableTabs.size]); true }
                else -> false
            }
        }
    ) {
        Column(
            Modifier.fillMaxHeight().width(236.dp).background(Color(0xF0090C0F))
                .drawBehind { drawRect(palette.surfaceElevated, androidx.compose.ui.geometry.Offset(size.width - 2.dp.toPx(), 0f), Size(2.dp.toPx(), size.height)) }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(Modifier.padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BiomeriaMark(Modifier.size(24.dp, 26.dp))
                Column {
                    Text(title, fontFamily = PixelFont, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("Minecraft $version", color = palette.textMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
            BlockButton(actions.resume, Modifier.fillMaxWidth().focusRequester(resumeFocus), BlockButtons.primary,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (ready) "Volver al juego" else "Ocultar menú", fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(4.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                browsableTabs.forEach { item -> MenuTab(item, tab == item) { onTab(item) } }
            }
            BlockButton({ onTab(GameMenuTab.EXIT) }, Modifier.fillMaxWidth(), BlockButtons.danger,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Salir del mundo", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
            }
            Text("A elegir · B volver · L1/R1 secciones", color = palette.textMuted, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp))
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().background(Color(0xD90D1115)).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!ready) {
                Column(Modifier.fillMaxWidth().background(palette.surfaceCard, PanelShape)
                    .border(2.dp, palette.surfaceElevated, PanelShape).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Iniciando Minecraft…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = palette.brandAccent, trackColor = palette.surfaceElevated)
                    Text("El primer arranque tarda más. El juego aparecerá solo.", color = palette.textMuted,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (tab != GameMenuTab.CHARACTER) {
                Text(if (tab == GameMenuTab.GAME) "Atajos" else tab.label, style = MaterialTheme.typography.headlineSmall)
            }
            when (tab) {
                GameMenuTab.GAME -> GameTab(hud, onHud, canOpenInventory, hasPanel, ready, actions)
                GameMenuTab.CHARACTER -> {
                    val activity = androidx.compose.ui.platform.LocalContext.current as? JavaGameActivity
                    CharacterEditor(bridge, onUpload = { activity?.pickSkinImage() }, onDone = { onTab(GameMenuTab.GAME) },
                        modifier = Modifier.weight(1f).fillMaxWidth())
                }
                GameMenuTab.PERFORMANCE -> PerformanceTab(hud, onHud, heapMb, renderScale, renderer)
                GameMenuTab.HUD -> HudTab(hud, onHud)
                GameMenuTab.LOG -> LogTab(logLines, technical)
                GameMenuTab.EXIT -> ExitTab(actions, saving, onBack = { onTab(GameMenuTab.GAME) })
            }
        }
    }
}

@Composable
private fun MenuTab(tab: GameMenuTab, selected: Boolean, onClick: () -> Unit) {
    val palette = Biomeria.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(ControlShape)
            .background(if (selected || focused) palette.surfaceElevated else Color.Transparent)
            .drawBehind { if (selected) drawRect(tab.color, size = Size(4.dp.toPx(), size.height)) }
            .border(if (focused) 3.dp else 0.dp, if (focused) palette.gold else Color.Transparent, ControlShape)
            .gamepadClick(onClick = onClick)
            .selectable(selected, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(10.dp).background(tab.color))
        Text(tab.label, color = if (selected || focused) palette.textPrimary else palette.textMuted,
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun GameTab(
    hud: GameHudSettings,
    onHud: (GameHudSettings) -> Unit,
    canOpenInventory: Boolean,
    hasPanel: Boolean,
    ready: Boolean,
    actions: GameMenuActions
) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShortcutTile("Mapa", if (hud.showMinimap) "Ocultar el minimapa" else "Mostrar el minimapa", "R3",
                Color(0xFF7BC74D), Modifier.weight(1f), enabled = hasPanel) { onHud(hud.copy(showMinimap = !hud.showMinimap)) }
            ShortcutTile("Inventario", "Abre tu inventario", "Y", Color(0xFFE0B48C), Modifier.weight(1f),
                enabled = ready && canOpenInventory, onClick = actions.inventory)
            ShortcutTile("Captura", "Guarda una foto del juego", "F2", Color(0xFF4FB3D9), Modifier.weight(1f),
                enabled = ready, onClick = actions.capture)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ShortcutTile("Teclado", "Escribe en el chat o en un campo", "L3", Color(0xFFF5E663), Modifier.weight(1f),
                enabled = ready, onClick = actions.keyboard)
            ShortcutTile("Pausa de Minecraft", "Guardar y salir, opciones del juego", "Esc", Color(0xFFA9B6BD),
                Modifier.weight(1f), enabled = ready, onClick = actions.minecraftPause)
            ShortcutTile("Doble pantalla", if (hud.dualScreen) "Activada: toca para apagar" else "Inventario y mapa abajo", "—",
                Color(0xFFF07D4A), Modifier.weight(1f), enabled = hasPanel) { onHud(hud.copy(dualScreen = !hud.dualScreen)) }
        }
        Row(
            Modifier.fillMaxWidth().background(Biomeria.colors.surfaceCard, PanelShape)
                .border(2.dp, Biomeria.colors.surfaceElevated, PanelShape).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(12.dp).background(Biomeria.colors.engineBedrock))
            Column(Modifier.weight(1f)) {
                Text("Jugar juntos", fontWeight = FontWeight.ExtraBold)
                Text("Abre este mundo a tu red local o crea una sala por internet.", color = Biomeria.colors.textMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
            LauncherButton("Red local", actions.lan, enabled = ready && hasPanel && canOpenInventory)
            BlockButton(actions.internet, colors = BlockButtons.ocean, enabled = ready && hasPanel) {
                Text("Por internet", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (!hasPanel) Text("El mapa, la doble pantalla y jugar juntos necesitan Minecraft 1.21.4 con Fabric.",
            color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ShortcutTile(
    title: String, detail: String, key: String, color: Color, modifier: Modifier,
    enabled: Boolean = true, onClick: () -> Unit
) {
    BlockButton(onClick, modifier.heightIn(min = 88.dp), BlockButtons.neutral, enabled,
        PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(12.dp).background(color))
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(key, Modifier.background(Color(0xFF1A2026), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color(0xFFD6DEE2), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(detail, color = Biomeria.colors.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

@Composable
private fun PerformanceTab(hud: GameHudSettings, onHud: (GameHudSettings) -> Unit, heapMb: Int, renderScale: RenderScale, renderer: JavaRenderer) {
    val palette = Biomeria.colors
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().background(palette.surfaceCard, PanelShape).border(2.dp, palette.surfaceElevated, PanelShape)
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Se aplica ahora", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
            Text("Límite de FPS", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                javaFpsLimits.forEach { limit ->
                    ChoiceChip(if (limit == 0) "Del juego" else limit.toString(), hud.fpsLimit == limit) { onHud(hud.copy(fpsLimit = limit)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ahorro en segundo plano", fontWeight = FontWeight.Bold)
                    Text("Baja a 15 FPS cuando sales de la app", color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(hud.batterySaver, { onHud(hud.copy(batterySaver = it)) })
            }
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF12171C), PanelShape)
            .border(2.dp, palette.borderSubtle, PanelShape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Al volver a abrir el mundo", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(if (heapMb % 1024 == 0) "RAM: ${heapMb / 1024} GB" else "RAM: $heapMb MB")
                InfoChip("Resolución: ${renderScale.percent} %")
                InfoChip("Gráficos: " + renderer.label)
            }
            Text("Estos se eligen antes de entrar, en la pantalla de preparación del mundo.",
                color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HudTab(hud: GameHudSettings, onHud: (GameHudSettings) -> Unit) {
    val palette = Biomeria.colors
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().background(palette.surfaceCard, PanelShape)
            .border(2.dp, palette.surfaceElevated, PanelShape).padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("En pantalla", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
            GameHudControls(hud, onHud)
            Text("La opacidad de pausa controla el botón del menú. Cierra el menú para ver cómo queda.",
                color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.weight(1f).fillMaxHeight().background(palette.surfaceCard, PanelShape)
            .border(2.dp, palette.surfaceElevated, PanelShape).padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tu mando en partida", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
            Text("Stick izquierdo para moverte, stick derecho para la cámara. Select abre este menú.",
                color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
            if (hud.dualScreen) {
                Text("Doble pantalla", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp))
                PanelPreferenceControls(hud, onHud)
            }
            Text("Los botones se cambian en Controles y HUD, en la pantalla de preparación.",
                color = palette.textMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LogTab(lines: List<String>, technical: String) {
    val palette = Biomeria.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val shown = filterLog(lines.toList(), filter, query)
    val errors = lines.count { classifyLogLine(it) == LogLevel.ERROR }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (errors == 1) "1 error" else "$errors errores",
                Modifier.background(if (errors > 0) palette.engineRust else palette.brandAccent, RoundedCornerShape(3.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = palette.ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogFilter.entries.forEach { ChoiceChip(it.label, filter == it) { filter = it } }
            }
            LogSearch(query, { query = it }, Modifier.width(200.dp))
        }
        LogView(shown, Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 120.dp, max = 400.dp), listState,
            emptyText = if (lines.isEmpty()) "Esperando la salida del motor Java…" else "Nada coincide con este filtro.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LauncherButton("Copiar", { clipboard.setText(AnnotatedString(shown.joinToString("\n"))); status = "Registro copiado." })
            LauncherButton(if (sharing) "Subiendo…" else "Compartir enlace", {
                if (!sharing) {
                    sharing = true
                    status = "Subiendo el registro…"
                    scope.launch {
                        LaunchReports(context).upload(lines.joinToString("\n"))
                            .onSuccess { url -> clipboard.setText(AnnotatedString(url)); status = "Enlace copiado: $url" }
                            .onFailure { status = it.message ?: "No se pudo subir el registro." }
                        sharing = false
                    }
                }
            })
            Text(status ?: "El enlace sube el registro a mclo.gs. Tu sesión siempre aparece como [token oculto].",
                Modifier.weight(1f), color = palette.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        Text(technical, color = Color(0xFF8F9CA3), fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LogSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val palette = Biomeria.colors
    BasicTextField(
        query, onQuery, singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.textPrimary),
        cursorBrush = SolidColor(palette.brandAccent),
        modifier = modifier.semantics { contentDescription = "Buscar en el registro" },
        decorationBox = { field ->
            Row(Modifier.heightIn(min = 36.dp).background(palette.ink, ControlShape).border(2.dp, palette.surfaceElevated, ControlShape)
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Search, null, Modifier.size(14.dp), tint = palette.textMuted)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Buscar en el registro", color = palette.textMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    field()
                }
            }
        }
    )
}

@Composable
private fun ExitTab(actions: GameMenuActions, saving: Boolean, onBack: () -> Unit) {
    val palette = Biomeria.colors
    var waited by remember { mutableIntStateOf(0) }
    LaunchedEffect(saving) {
        while (saving) {
            kotlinx.coroutines.delay(1000)
            waited++
        }
    }
    Column(Modifier.widthIn(max = 560.dp).background(palette.surfaceCard, PanelShape).border(2.dp, Color(0xFF4A2620), PanelShape)
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val saveAndQuit = actions.saveAndQuit
        when {
            saving -> {
                Text("Guardando el mundo y cerrando Minecraft…", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = palette.brandAccent, trackColor = palette.surfaceElevated)
                Text(if (waited < 60) "Volverás a Biomeria cuando termine. Los mundos grandes tardan más."
                    else "Minecraft está tardando. Puedes seguir esperando o salir ahora, aunque se pierda lo último.",
                    color = palette.textMuted, style = MaterialTheme.typography.bodyMedium)
                if (waited >= 60) BlockButton(actions.exit, colors = BlockButtons.danger) {
                    Text("Salir ahora", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                }
            }
            saveAndQuit != null -> {
                Text("Biomeria le pedirá a Minecraft que guarde el mundo y se cierre, igual que al cerrar su ventana.",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BlockButton(saveAndQuit, colors = BlockButtons.primary) {
                        Text("Guardar y salir", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                    }
                    LauncherButton("Seguir jugando", onBack)
                    BlockButton(actions.exit, colors = BlockButtons.danger) {
                        Text("Salir sin guardar", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            else -> {
                Text("Antes de salir, guarda en Minecraft: abre la Pausa de Minecraft y elige «Guardar y salir al título».",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text("Al volver al título, sal del juego desde ahí y Biomeria se abrirá sola. Salir ahora cierra el juego de inmediato.",
                    color = palette.textMuted, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BlockButton(actions.minecraftPause, colors = BlockButtons.primary) {
                        Text("Abrir la pausa para guardar", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                    }
                    BlockButton(actions.exit, colors = BlockButtons.danger) {
                        Text("Salir ahora", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
                    }
                    LauncherButton("Seguir jugando", onBack)
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = Biomeria.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        Modifier.heightIn(min = 36.dp).clip(ControlShape)
            .background(if (selected) palette.textPrimary else palette.surfaceElevated)
            .border(if (focused) 3.dp else 2.dp, if (focused) palette.gold else if (selected) palette.textPrimary else palette.borderSubtle, ControlShape)
            .gamepadClick(onClick = onClick)
            .selectable(selected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) palette.ink else Color(0xFFD6DEE2), fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun InfoChip(text: String) {
    Text(text, Modifier.background(Color(0xFF1A2026), ControlShape).padding(horizontal = 12.dp, vertical = 8.dp),
        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
}

/** The only button over the game: the Biomeria block plus the Select hint. */
@Composable
internal fun GameMenuPill(opacity: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BlockButton(onClick, modifier.semantics { contentDescription = "Abrir el menú de Biomeria" }
        .alpha(opacity.coerceIn(.12f, 1f)), BlockButtons.neutral,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        BiomeriaMark(Modifier.size(20.dp, 22.dp))
        Spacer(Modifier.width(8.dp))
        Text("SELECT", Modifier.background(Color(0xFF34404A), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            color = Color(0xFFF2F5EF), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}
