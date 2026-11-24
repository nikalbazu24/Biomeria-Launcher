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

/** Every Minecraft option, plus the mods' own settings, in Biomeria's look: sections on the left, cards on the right. */
@Composable
internal fun BiomeriaOptionsScreen(gameDirectory: File, bridge: DualScreenBridge?, guide: OptionGuideState? = null, onClose: () -> Unit) {
    val palette = Biomeria.colors
    val live = rememberLiveGameOptions(gameDirectory, bridge) ?: return
    val shaders = rememberShaderController(gameDirectory, bridge)
    val scope = rememberCoroutineScope()
    var group by remember { mutableStateOf(OptionGroup2.VIDEO) }
    var picking by remember { mutableStateOf(false) }
    var mods by remember { mutableStateOf<List<ModConfigFile>?>(null) }
    var modIndex by remember { mutableIntStateOf(0) }
    var modChanges by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var modStatus by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    LaunchedEffect(group) {
        if (group == OptionGroup2.MODS && mods == null) mods = withContext(Dispatchers.IO) { readModConfigs(gameDirectory) }
    }
    val status = if (picking) null else modStatus ?: live.status
    // Tell the guide on the lower screen what is being touched and what everything is set to.
    fun help(key: String) { guide?.key = key }
    LaunchedEffect(live.values) { guide?.values = live.values }
    LaunchedEffect(shaders.settings) { guide?.shader = shaders.settings?.active?.title }
    LaunchedEffect(group) { help(gameOptionGroups[group]?.firstOrNull()?.key?.takeIf { group != OptionGroup2.VIDEO } ?: "fov") }

    Box(Modifier.fillMaxSize().background(Color(0xF20B0E11)).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
        val groups = OptionGroup2.entries
        when (event.key) {
            Key.ButtonB -> { if (picking) picking = false else onClose(); true }
            // With the guide below, Select is handled by the activity (back to quick settings).
            Key.ButtonL1 -> { if (!picking) group = groups[(group.ordinal + groups.size - 1) % groups.size]; true }
            Key.ButtonR1 -> { if (!picking) group = groups[(group.ordinal + 1) % groups.size]; true }
            else -> false
        }
    }) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxHeight().width(212.dp).background(Color(0xFF0E1317)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Opciones", fontFamily = PixelFont, fontSize = TypeScale.title, color = Color.White, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OptionGroup2.entries.forEachIndexed { index, entry ->
                        val selected = entry == group
                        BlockButton({ group = entry }, Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                            if (selected) block(entry.color, entry.color, 0xFF0B0E11) else BlockButtons.neutral,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Box(Modifier.size(30.dp).background(if (selected) Color(0x220B0E11) else Color(entry.color), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center) { PixelGlyph(entry.glyph, Color(0xFF0B0E11), Modifier.size(20.dp)) }
                            Spacer(Modifier.width(10.dp))
                            Text(entry.label, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        }
                    }
                }
                BlockButton(onClose, Modifier.fillMaxWidth(), BlockButtons.primary) {
                    Text("Listo", fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(6.dp))
                    PadKey("B")
                }
                Text("L1 y R1 cambian de sección", color = palette.textMuted, style = PanelText.detail)
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PixelGlyph(group.glyph, Color(group.color), Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.label, fontFamily = PixelFont, fontSize = TypeScale.title, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(group.detail, color = palette.textMuted, style = PanelText.detail, maxLines = 1)
                    }
                    status?.let { Text(it, color = Color(0xFFA6E07E), fontWeight = FontWeight.ExtraBold, style = PanelText.detail,
                        maxLines = 2, modifier = Modifier.widthIn(max = 220.dp)) }
                }
                if (group != OptionGroup2.MODS) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (group == OptionGroup2.VIDEO) {
                            Row(Modifier.fillMaxWidth().height(184.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FovCard(fovDegrees(live.values["fov"]), { live.change("fov", fovRaw(it)); help("fov") },
                                    Modifier.weight(1.15f).fillMaxHeight().helpOn("fov", ::help))
                                ShaderCard(shaders, { picking = true; help("shaders") }, Modifier.weight(1f).fillMaxHeight().helpOn("shaders", ::help))
                            }
                        }
                        gameOptionGroups.getValue(group).chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { option -> OptionCell(option, live.values[option.key], live::change,
                                    Modifier.weight(1f).heightIn(min = 76.dp).helpOn(option.key, ::help)) }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    val list = mods
                    when {
                        list == null -> Text("Buscando las opciones de tus mods…", color = palette.textMuted)
                        list.isEmpty() -> Text("Este mundo no tiene mods con opciones que Biomeria pueda leer.", color = palette.textMuted)
                        else -> {
                            val current = list[modIndex.coerceIn(0, list.lastIndex)]
                            Row(Modifier.fillMaxWidth().kitCard().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BlockButton({ modIndex = (modIndex - 1 + list.size) % list.size; modChanges = emptyMap() }) { Text("‹") }
                                Column(Modifier.weight(1f)) {
                                    Text(current.modName, fontFamily = PixelFont, fontSize = TypeScale.section, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("${modIndex + 1} de ${list.size} · se aplica al volver a abrir el juego",
                                        color = palette.textMuted, style = PanelText.detail, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                                BlockButton({ modIndex = (modIndex + 1) % list.size; modChanges = emptyMap() }) { Text("›") }
                                BlockButton({
                                    saveModConfig(current, modChanges)
                                    mods = readModConfigs(gameDirectory)
                                    modChanges = emptyMap()
                                    modStatus = "${current.modName} guardado · se aplica al volver a abrir el juego"
                                }, colors = BlockButtons.primary, enabled = modChanges.isNotEmpty()) { Text("Guardar", fontWeight = FontWeight.ExtraBold) }
                            }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                current.settings.forEach { setting ->
                                    val value = modChanges[setting.path] ?: setting.value
                                    ModRow(setting, value) { modChanges = modChanges + (setting.path to it) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (picking) ShaderPicker(shaders, onClose = { picking = false }) { pack -> scope.launch { shaders.choose(pack) } }
    }
}

/** Reports [key] to the guide when this part gets the controller's focus or a finger touches it. */
private fun Modifier.helpOn(key: String, onHelp: (String) -> Unit): Modifier = this
    .onFocusChanged { if (it.hasFocus) onHelp(key) }
    .pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onHelp(key)
        }
    }

/** One option as a card: name and value on top, the control under it. */
@Composable
private fun OptionCell(option: GameOption, raw: String?, onChange: (String, String) -> Unit, modifier: Modifier = Modifier) {
    when (option) {
        is GameOption.Toggle -> {
            val on = raw == "true"
            Row(modifier.kitCard(Kit.raised).clickable(role = Role.Switch) { onChange(option.key, (!on).toString()) }
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(option.label, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                KitSwitch(on)
            }
        }
        is GameOption.Number -> {
            val value = raw?.trim('"')?.toDoubleOrNull()?.coerceIn(option.min, option.max) ?: option.min
            var dragging by remember(option.key) { mutableStateOf<Float?>(null) }
            val shown = dragging?.toDouble() ?: value
            val intervals = ((option.max - option.min) / option.step).roundToInt()
            fun snap(v: Double) = (option.min + ((v - option.min) / option.step).roundToInt() * option.step).coerceIn(option.min, option.max)
            Column(modifier.kitCard(Kit.raised).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(option.label, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(option.format(snap(shown)), color = Color(0xFFF5E663), fontFamily = PixelFont, fontSize = TypeScale.body)
                }
                Slider(shown.toFloat(), { dragging = it },
                    Modifier.fillMaxWidth().height(30.dp).semantics { contentDescription = option.label },
                    valueRange = option.min.toFloat()..option.max.toFloat(),
                    steps = if (intervals in 2..60) intervals - 1 else 0,
                    onValueChangeFinished = {
                        dragging?.let { onChange(option.key, formatOptionNumber(snap(it.toDouble()), option.decimals)) }
                        dragging = null
                    }, colors = goldSlider())
            }
        }
        is GameOption.Choice -> {
            val index = option.values.indexOfFirst { it.first == raw }.coerceAtLeast(0)
            Column(modifier.kitCard(Kit.raised).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                Text(option.label, color = Color.White, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Segmented(option.values, option.values[index].first, { it.second }, { onChange(option.key, it.first) },
                    Color(0xFF7BC74D), Modifier.fillMaxWidth(), keyOf = { it.first })
            }
        }
    }
}

@Composable
private fun ModRow(setting: ModSetting, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).kitCard(Kit.raised).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(setting.label, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        when (setting.type) {
            ModValueType.BOOLEAN -> {
                val on = value == "true"
                KitSwitch(on, Modifier.clickable(role = Role.Switch) { onChange((!on).toString()) })
            }
            ModValueType.NUMBER -> {
                val number = value.toDoubleOrNull() ?: 0.0
                val integer = '.' !in value
                val step = if (integer) 1.0 else .1
                fun show(next: Double) = if (integer) next.toLong().toString()
                    else String.format(java.util.Locale.ROOT, "%.2f", next).trimEnd('0').trimEnd('.')
                BlockButton({ onChange(show(number - step)) }) { Text("−", fontWeight = FontWeight.ExtraBold) }
                Text(value, Modifier.widthIn(min = 72.dp), color = Color(0xFFF5E663), fontFamily = PixelFont, fontSize = TypeScale.body,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                BlockButton({ onChange(show(number + step)) }) { Text("+", fontWeight = FontWeight.ExtraBold) }
            }
            ModValueType.TEXT -> Text(value, color = Color(0xFF9AA6AD), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp))
        }
    }
}
