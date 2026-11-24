package com.biomeria.launcher.engine.java

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** What the window shows on its right side. */
private sealed interface OptionsPage {
    data class Group(val group: OptionGroup2) : OptionsPage
    class Mod(val mod: InstalledMod) : OptionsPage
    data object Shaders : OptionsPage
}

private val OptionsPage.depth: Int get() = if (this is OptionsPage.Group) 0 else 1
private val OptionsPage.memoryKey: String get() = when (this) {
    is OptionsPage.Group -> "g:" + group.name
    is OptionsPage.Mod -> "m:" + mod.id
    OptionsPage.Shaders -> "shaders"
}

private val windowShape = RoundedCornerShape(18.dp)
private val cellShape = RoundedCornerShape(10.dp)

/** Mod files are written one after another, off the main thread, and finish even if the window closes. */
private val modSaving = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val modSaveLock = Mutex()

/**
 * Every Minecraft option and the mods' own settings, as a window over the paused game: sections on the
 * left, one row per option on the right, and a gold cursor that always shows where the controller is.
 */
@Composable
internal fun BiomeriaOptionsScreen(gameDirectory: File, bridge: DualScreenBridge?, guide: OptionGuideState? = null, onClose: () -> Unit) {
    val live = rememberLiveGameOptions(gameDirectory, bridge) ?: return
    val shaders = rememberShaderController(gameDirectory, bridge)
    var page by remember { mutableStateOf<OptionsPage>(OptionsPage.Group(OptionGroup2.VIDEO)) }
    var mods by remember { mutableStateOf<List<InstalledMod>?>(null) }
    LaunchedEffect(gameDirectory) { mods = withContext(Dispatchers.IO) { runCatching { readInstalledMods(gameDirectory) }.getOrDefault(emptyList()) } }
    val group = when (val now = page) {
        is OptionsPage.Group -> now.group
        is OptionsPage.Mod -> OptionGroup2.MODS
        OptionsPage.Shaders -> OptionGroup2.VIDEO
    }
    val rail = remember { OptionGroup2.entries.associateWith { FocusRequester() } }
    val memory = remember { mutableStateMapOf<String, Int>() }
    val nav = remember { WindowNav() }

    fun go(next: OptionsPage) {
        nav.takeFocus = true
        page = next
    }
    fun back() = when (page) {
        is OptionsPage.Mod -> go(OptionsPage.Group(OptionGroup2.MODS))
        OptionsPage.Shaders -> go(OptionsPage.Group(OptionGroup2.VIDEO))
        else -> onClose()
    }
    fun shift(by: Int) {
        val all = OptionGroup2.entries
        go(OptionsPage.Group(all[(group.ordinal + by + all.size) % all.size]))
    }

    // The guide below follows what is on screen here.
    LaunchedEffect(live.values) { guide?.values = live.values }
    LaunchedEffect(shaders.settings) { guide?.shader = shaders.settings?.active?.title }
    LaunchedEffect(mods) { guide?.mods = mods.orEmpty() }
    LaunchedEffect(page) {
        val now = page
        guide?.mode = when {
            now is OptionsPage.Mod -> GuideMode.MOD
            now is OptionsPage.Group && now.group == OptionGroup2.MODS -> GuideMode.MODS
            else -> GuideMode.OPTION
        }
        if (now is OptionsPage.Mod) { guide?.mod = now.mod; guide?.modSetting = null }
        if (now == OptionsPage.Shaders) guide?.key = "shaders"
    }

    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(340, easing = FastOutSlowInEasing)) }

    Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        val mine = event.key == Key.ButtonB || event.key == Key.ButtonL1 || event.key == Key.ButtonR1
        if (mine && event.type == KeyEventType.KeyUp) when (event.key) {
            Key.ButtonB -> back()
            Key.ButtonL1 -> shift(-1)
            else -> shift(1)
        }
        mine
    }) {
        // The game stays in sight behind, darker; touching it closes the window.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(Color(0xA8080C10))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val width = minOf(maxWidth * .9f, 1180.dp)
            Column(Modifier.fillMaxSize().padding(bottom = maxHeight * .04f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(width).graphicsLayer { alpha = shown.value; translationY = (1 - shown.value) * -24f }) { PausedBadge() }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.width(width).weight(1f)
                    .graphicsLayer {
                        alpha = shown.value
                        translationY = (1 - shown.value) * 48.dp.toPx()
                        val scale = .96f + .04f * shown.value
                        scaleX = scale; scaleY = scale
                    }
                    .shadow(24.dp, windowShape).clip(windowShape).background(Color(0xFF101418)).border(3.dp, Kit.line, windowShape)
                    .clickable(remember { MutableInteractionSource() }, null) {}) {
                    Rail(group, mods, rail, nav, onPick = { go(OptionsPage.Group(it)) }, onClose = onClose)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        PageHeader(page, group, live.status, ::back)
                        AnimatedContent(page, Modifier.weight(1f).fillMaxWidth(), transitionSpec = {
                            val forward = if (targetState.depth != initialState.depth) targetState.depth > initialState.depth
                                else ((targetState as? OptionsPage.Group)?.group?.ordinal ?: 0) >= ((initialState as? OptionsPage.Group)?.group?.ordinal ?: 0)
                            val side = if (forward) 1 else -1
                            (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { side * it / 8 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(240)) { -side * it / 10 } + fadeOut(tween(150)))
                        }, label = "page") { shownPage ->
                            val cells = remember(shownPage) { CellFocus(shownPage.memoryKey, memory, nav) }
                            val left = rail.getValue(group)
                            when (shownPage) {
                                is OptionsPage.Group -> when (shownPage.group) {
                                    OptionGroup2.MODS -> ModsPage(mods, cells, left, guide) { go(OptionsPage.Mod(it)) }
                                    else -> GamePage(shownPage.group, live, shaders, cells, left, guide) { go(OptionsPage.Shaders) }
                                }
                                is OptionsPage.Mod -> ModPage(shownPage.mod, cells, left, guide)
                                OptionsPage.Shaders -> ShadersPage(shaders, cells, left, guide) { pack ->
                                    ShaderJob.start(shaders, pack)
                                    go(OptionsPage.Group(OptionGroup2.VIDEO))
                                }
                            }
                        }
                        Footer(page)
                    }
                }
            }
        }
    }
}

/** Where the controller goes: into the page after a change, and back to the rail with ←. */
private class WindowNav {
    var takeFocus = true
    var entry by mutableStateOf<FocusRequester?>(null)
}

/** One focus target per row of a page, remembering the last row used so coming back lands there. */
private class CellFocus(val key: String, private val memory: MutableMap<String, Int>, val nav: WindowNav) {
    private val requesters = mutableListOf<FocusRequester>()
    fun at(index: Int): FocusRequester {
        while (requesters.size <= index) requesters += FocusRequester()
        return requesters[index]
    }
    val start: Int get() = memory[key] ?: 0
    /** Starts on [index] the first time this page opens. */
    fun prefer(index: Int) { if (key !in memory) memory[key] = index }
    fun remember(index: Int) { memory[key] = index; nav.entry = at(index) }
}

/** Puts the controller on the page's remembered row when the page opens after a controller move. */
@Composable
private fun TakeFocus(cells: CellFocus, count: Int) {
    LaunchedEffect(cells, count) {
        if (count == 0) return@LaunchedEffect
        val index = cells.start.coerceIn(0, count - 1)
        cells.nav.entry = cells.at(index)
        if (!cells.nav.takeFocus) return@LaunchedEffect
        delay(40)
        if (runCatching { cells.at(index).requestFocus() }.isSuccess) cells.nav.takeFocus = false
    }
}

/**
 * A row the controller can stop on: a gold frame with a glow and an arrow slides in when it is chosen,
 * ← and → change its value when it has one, and A (or a tap) acts on it.
 */
@Composable
private fun Modifier.padCell(
    requester: FocusRequester, left: FocusRequester?, onFocus: () -> Unit,
    onStep: ((Int) -> Unit)? = null, onClick: (() -> Unit)? = null, accent: Color = Kit.gold
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "glow")
    return this
        .drawWithContent {
            if (glow > 0f) {
                val grow = 5.dp.toPx() * glow
                drawRoundRect(accent.copy(alpha = .18f * glow), Offset(-grow, -grow), Size(size.width + grow * 2, size.height + grow * 2),
                    CornerRadius(14.dp.toPx()))
            }
            drawContent()
            if (glow > 0f) {
                val stroke = 3.dp.toPx()
                drawRoundRect(accent.copy(alpha = glow), Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
                    CornerRadius(10.dp.toPx()), style = Stroke(stroke))
                val tip = -4.dp.toPx() - (1 - glow) * 8.dp.toPx()
                val half = 7.dp.toPx()
                drawPath(Path().apply {
                    moveTo(tip, size.height / 2)
                    lineTo(tip - 9.dp.toPx(), size.height / 2 - half)
                    lineTo(tip - 9.dp.toPx(), size.height / 2 + half)
                    close()
                }, accent.copy(alpha = glow))
            }
        }
        .focusRequester(requester)
        .focusProperties { if (left != null) this.left = left }
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus()
        }
        .onKeyEvent { event ->
            val step = onStep ?: return@onKeyEvent false
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> { step(-1); true }
                Key.DirectionRight -> { step(1); true }
                else -> false
            }
        }
        .clickable(remember { MutableInteractionSource() }, null) {
            runCatching { requester.requestFocus() }
            onFocus()
            onClick?.invoke()
        }
}

// ---------------------------------------------------------------- Frame of the window

@Composable
private fun PausedBadge() {
    val busy = ShaderJob.busy
    Row(Modifier.background(Color(0xE6080C10), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(2) { Box(Modifier.size(5.dp, 16.dp).background(Kit.gold, RoundedCornerShape(1.dp))) }
        }
        Text(if (busy) "Juego en pausa · aplicando shaders" else "Juego en pausa", color = Kit.text, fontFamily = PixelFont,
            fontSize = TypeScale.section, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Rail(
    group: OptionGroup2, mods: List<InstalledMod>?, rail: Map<OptionGroup2, FocusRequester>, nav: WindowNav,
    onPick: (OptionGroup2) -> Unit, onClose: () -> Unit
) {
    Column(Modifier.width(204.dp).fillMaxHeight().background(Color(0xFF0E1317)).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ajustes", Modifier.weight(1f), color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.title,
                fontWeight = FontWeight.Bold, maxLines = 1)
            PadKey("LB")
            PadKey("RB")
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OptionGroup2.entries.forEach { entry ->
                val count = when (entry) {
                    OptionGroup2.MODS -> mods?.size
                    OptionGroup2.VIDEO -> gameOptionGroups[entry].orEmpty().size + 2
                    else -> gameOptionGroups[entry].orEmpty().size
                }
                RailItem(entry, entry == group, count, rail.getValue(entry), nav) { onPick(entry) }
            }
        }
        Row(Modifier.fillMaxWidth().clip(cellShape).background(Kit.raised).clickable(onClick = onClose)
            .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Volver al juego", Modifier.weight(1f), color = Kit.text, fontWeight = FontWeight.ExtraBold,
                fontSize = TypeScale.body, maxLines = 1)
            PadKey("B")
        }
    }
}

@Composable
private fun RailItem(group: OptionGroup2, selected: Boolean, count: Int?, requester: FocusRequester, nav: WindowNav, onPick: () -> Unit) {
    val accent = Color(group.color)
    val back by animateColorAsState(if (selected) accent else Kit.raised, tween(240), label = "rail")
    val ink by animateColorAsState(if (selected) Kit.ink else Kit.text, tween(240), label = "ink")
    val shift by animateDpAsState(if (selected) 6.dp else 0.dp, spring(.7f, 500f), label = "shift")
    Row(Modifier.fillMaxWidth().offset(x = shift)
        .focusProperties { right = nav.entry ?: FocusRequester.Default }
        .padCell(requester, null, onFocus = {}, onClick = onPick)
        .background(back, cellShape).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).background(if (selected) Color(0x220B0E11) else accent, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center) { PixelGlyph(group.glyph, Kit.ink, Modifier.size(20.dp)) }
        Text(group.label, Modifier.weight(1f), color = ink, fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.body,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        count?.let { Text("$it", color = if (selected) Kit.ink else Kit.muted, fontWeight = FontWeight.Bold, fontSize = TypeScale.detail) }
    }
}

@Composable
private fun PageHeader(page: OptionsPage, group: OptionGroup2, status: String?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (page) {
            is OptionsPage.Group -> {
                PixelGlyph(group.glyph, Color(group.color), Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.label, color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.title, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(group.detail, color = Kit.muted, fontWeight = FontWeight.Bold, fontSize = TypeScale.detail, maxLines = 1)
                }
            }
            is OptionsPage.Mod -> {
                BackChip("Mods", onBack)
                ModBadge(page.mod, 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(page.mod.name, color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.title, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(page.mod.version?.let { "v$it" }, "${page.mod.settingCount} ajustes").joinToString(" · "),
                        color = Kit.muted, fontWeight = FontWeight.Bold, fontSize = TypeScale.detail, maxLines = 1)
                }
            }
            OptionsPage.Shaders -> {
                BackChip("Imagen", onBack)
                Text("Elige tus shaders", Modifier.weight(1f), color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.title,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        val note = when {
            page is OptionsPage.Mod || (page is OptionsPage.Group && page.group == OptionGroup2.MODS) -> "Se aplican al volver a abrir el juego"
            page == OptionsPage.Shaders -> "El juego espera mientras se aplican"
            else -> status ?: "Los cambios se aplican solos"
        }
        Text(note, Modifier.widthIn(max = 230.dp), color = if (status != null && page is OptionsPage.Group && page.group != OptionGroup2.MODS) Kit.grass else Kit.muted,
            fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.detail, maxLines = 2, textAlign = TextAlign.End)
    }
}

@Composable
private fun BackChip(to: String, onBack: () -> Unit) {
    Row(Modifier.clip(cellShape).background(Kit.raised).pointerInput(Unit) { detectTapGestures { onBack() } }
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadKey("B")
        Text("‹ $to", color = Kit.text, fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.body, maxLines = 1)
    }
}

@Composable
private fun Footer(page: OptionsPage) {
    val hints = when {
        page == OptionsPage.Shaders -> listOf("A" to "Aplicar", "✛" to "Moverte", "B" to "Volver")
        page is OptionsPage.Mod -> listOf("A" to "Cambiar", "✛" to "Moverte · ← → ajustar", "B" to "Volver a Mods")
        page is OptionsPage.Group && page.group == OptionGroup2.MODS -> listOf("A" to "Abrir mod", "✛" to "Moverte", "LB RB" to "Sección", "B" to "Cerrar")
        else -> listOf("A" to "Cambiar", "✛" to "Moverte · ← → ajustar", "LB RB" to "Sección", "B" to "Cerrar")
    }
    Row(Modifier.fillMaxWidth().background(Kit.ink).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        hints.forEach { (key, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadKey(key)
                Text(label, color = Kit.text, fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.detail, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- Rows and their controls

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 18.dp, top = 8.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** The look of every row: name and one line on what it does on the left, its control on the right. */
@Composable
private fun RowBody(label: String, detail: String?, modifier: Modifier, leading: (@Composable () -> Unit)? = null,
                    trailing: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 62.dp).background(Kit.card, cellShape).border(2.dp, Kit.line, cellShape)
        .padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Kit.text, fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.body, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = TypeScale.body * 1.2f)
            detail?.let { Text(it, color = Kit.muted, fontWeight = FontWeight.Bold, fontSize = TypeScale.detail, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = TypeScale.detail * 1.3f) }
        }
        trailing()
    }
}

/** A track that fills up to the value; tap or drag it to choose. */
@Composable
private fun ValueBar(fraction: Float, onPick: (Float) -> Unit, modifier: Modifier = Modifier, accent: Color = Kit.grass) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(160), label = "bar")
    Canvas(modifier.width(190.dp).height(30.dp)
        .pointerInput(Unit) { detectTapGestures { onPick((it.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onPick((change.position.x / size.width).coerceIn(0f, 1f)) } }) {
        val track = 10.dp.toPx()
        val top = (size.height - track) / 2
        val knob = 14.dp.toPx()
        val x = knob / 2 + (size.width - knob) * shown
        drawRoundRect(Kit.ink, Offset(0f, top), Size(size.width, track), CornerRadius(track / 2))
        drawRoundRect(accent, Offset(0f, top), Size(x, track), CornerRadius(track / 2))
        drawRoundRect(Color(0x66000000), Offset(x - knob / 2, 3.dp.toPx() + 2.dp.toPx()), Size(knob, size.height - 6.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawRoundRect(Kit.text, Offset(x - knob / 2, 3.dp.toPx()), Size(knob, size.height - 6.dp.toPx()), CornerRadius(4.dp.toPx()))
    }
}

@Composable
private fun ValueText(text: String, minWidth: androidx.compose.ui.unit.Dp = 74.dp) {
    Text(text, Modifier.widthIn(min = minWidth), color = Kit.gold, fontFamily = PixelFont, fontSize = TypeScale.section,
        textAlign = TextAlign.End, maxLines = 1)
}

/** A switch whose knob slides and whose colour fades. */
@Composable
private fun SlideSwitch(on: Boolean) {
    val at by animateFloatAsState(if (on) 1f else 0f, spring(.75f, 600f), label = "knob")
    val back by animateColorAsState(if (on) Kit.grass else Color(0xFF3A454F), tween(200), label = "switch")
    Box(Modifier.size(58.dp, 32.dp).background(back, RoundedCornerShape(16.dp)).padding(4.dp)) {
        Box(Modifier.offset(x = 26.dp * at).size(24.dp).background(Kit.text, RoundedCornerShape(12.dp)))
    }
    Text(if (on) "Sí" else "No", Modifier.width(34.dp), color = if (on) Kit.grass else Kit.muted, fontWeight = FontWeight.ExtraBold,
        fontSize = TypeScale.body, textAlign = TextAlign.End)
}

/** Choices side by side; the chosen one lights up. Touch picks; the controller uses ← →. */
@Composable
private fun ChoicePills(choices: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.background(Kit.ink, RoundedCornerShape(9.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        choices.forEachIndexed { index, choice ->
            val on = index == selected
            val back by animateColorAsState(if (on) Kit.sky else Color.Transparent, tween(200), label = "pill")
            Text(choice, Modifier.clip(RoundedCornerShape(7.dp)).background(back).pointerInput(index) { detectTapGestures { onPick(index) } }
                .padding(horizontal = 10.dp, vertical = 6.dp), color = if (on) Kit.ink else Kit.muted, fontWeight = FontWeight.ExtraBold,
                fontSize = TypeScale.detail, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- Minecraft's own options

private fun numberOf(option: GameOption.Number, raw: String?) = raw?.trim('"')?.toDoubleOrNull()?.coerceIn(option.min, option.max) ?: option.min
private fun snap(option: GameOption.Number, value: Double) =
    (option.min + ((value - option.min) / option.step).roundToInt() * option.step).coerceIn(option.min, option.max)

@Composable
private fun GamePage(
    group: OptionGroup2, live: LiveGameOptions, shaders: ShaderController, cells: CellFocus, left: FocusRequester,
    guide: OptionGuideState?, openShaders: () -> Unit
) {
    val options = gameOptionGroups[group].orEmpty()
    val extra = if (group == OptionGroup2.VIDEO) 2 else 0
    TakeFocus(cells, options.size + extra)
    fun focus(index: Int, key: String) { cells.remember(index); guide?.key = key }
    PageColumn {
        if (group == OptionGroup2.VIDEO) {
            val degrees = fovDegrees(live.values["fov"])
            RowBody("Campo de visión", shortHelp("fov"), Modifier.padCell(cells.at(0), left, { focus(0, "fov") },
                onStep = { live.change("fov", fovRaw((degrees + it * 5).coerceIn(30, 110))) }),
                leading = { FovCone(degrees, Modifier.size(76.dp, 52.dp)) }) {
                ValueBar((degrees - 30) / 80f, { live.change("fov", fovRaw((30 + it * 80).roundToInt() / 5 * 5)) })
                ValueText("$degrees°")
            }
            val settings = shaders.settings
            val active = settings?.active
            RowBody("Shaders", when {
                ShaderJob.busy -> "Aplicando…"
                settings == null -> "Leyendo…"
                !settings.irisInstalled -> "Instala Iris en el Taller para usar shaders"
                else -> active?.title ?: "Apagados"
            }, Modifier.padCell(cells.at(1), left, { focus(1, "shaders") }, onClick = openShaders, accent = Kit.gold),
                leading = { Box(Modifier.size(76.dp, 46.dp).background(shaderSky(active?.title), RoundedCornerShape(7.dp))) }) {
                Row(Modifier.background(Kit.lilac, RoundedCornerShape(18.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PadKey("A")
                    Text("Cambiar", color = Color(0xFF14081F), fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.body)
                }
            }
        }
        options.forEachIndexed { position, option ->
            val index = position + extra
            val raw = live.values[option.key]
            val focusIt = { focus(index, option.key) }
            when (option) {
                is GameOption.Toggle -> {
                    val on = raw == "true"
                    val flip = { live.change(option.key, (!on).toString()) }
                    RowBody(option.label, shortHelp(option.key), Modifier.padCell(cells.at(index), left, focusIt, { flip() }, flip)) { SlideSwitch(on) }
                }
                is GameOption.Number -> {
                    val value = numberOf(option, raw)
                    fun set(next: Double) = live.change(option.key, formatOptionNumber(snap(option, next), option.decimals))
                    RowBody(option.label, shortHelp(option.key), Modifier.padCell(cells.at(index), left, focusIt, { set(value + it * option.step) })) {
                        ValueBar(((value - option.min) / (option.max - option.min)).toFloat(), { set(option.min + it * (option.max - option.min)) })
                        ValueText(option.format(value))
                    }
                }
                is GameOption.Choice -> {
                    val at = option.values.indexOfFirst { it.first == raw }.coerceAtLeast(0)
                    fun pick(next: Int) = live.change(option.key, option.values[next.coerceIn(0, option.values.lastIndex)].first)
                    RowBody(option.label, shortHelp(option.key), Modifier.padCell(cells.at(index), left, focusIt, { pick(at + it) },
                        { pick((at + 1) % option.values.size) })) {
                        ChoicePills(option.values.map { it.second }, at, ::pick)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Shaders

@Composable
private fun ShadersPage(shaders: ShaderController, cells: CellFocus, left: FocusRequester, guide: OptionGuideState?, onChoose: (ShaderPack?) -> Unit) {
    val settings = shaders.settings
    val choices = if (settings?.irisInstalled == true) listOf<ShaderPack?>(null) + settings.packs else emptyList()
    val activeIndex = choices.indexOfFirst { it?.file == settings?.active?.file }.coerceAtLeast(0)
    remember(cells, activeIndex) { cells.prefer(activeIndex) }
    TakeFocus(cells, choices.size)
    PageColumn {
        when {
            settings == null -> Text("Buscando tus shaders…", color = Kit.muted, fontSize = TypeScale.body)
            !settings.irisInstalled -> Note("Para usar shaders, este mundo necesita el mod Iris. Instálalo desde el Taller de Biomeria y vuelve a abrir el juego.")
            else -> {
                if (settings.packs.isEmpty()) Note("No tienes shaders instalados. Búscalos en el Taller, sección Shaders.")
                choices.chunked(3).forEachIndexed { row, three ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        three.forEachIndexed { column, pack ->
                            val index = row * 3 + column
                            val using = pack?.file == settings.active?.file
                            Column(Modifier.weight(1f)
                                .padCell(cells.at(index), if (column == 0) left else null, { cells.remember(index); guide?.key = "shaders" },
                                    onClick = { onChoose(pack) })
                                .background(Kit.card, cellShape).border(2.dp, if (using) Kit.grass else Kit.line, cellShape).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.fillMaxWidth().height(78.dp).background(shaderSky(pack?.title), RoundedCornerShape(7.dp)))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(pack?.title ?: "Sin shaders", Modifier.weight(1f, fill = false), color = Kit.text, fontFamily = PixelFont,
                                        fontSize = TypeScale.body, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (using) Text("En uso", Modifier.background(Kit.grass, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                                        color = Kit.ink, fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.detail)
                                }
                                Text(if (pack == null) "Lo más rápido" else if (using) "Lo estás usando" else "A para aplicar", color = Kit.muted,
                                    fontWeight = FontWeight.Bold, fontSize = TypeScale.detail, maxLines = 1)
                            }
                        }
                        repeat(3 - three.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.fillMaxWidth().background(Kit.card, cellShape).border(2.dp, Kit.line, cellShape).padding(16.dp),
        color = Kit.text, fontWeight = FontWeight.Bold, fontSize = TypeScale.body, lineHeight = TypeScale.body * 1.35f)
}

// ---------------------------------------------------------------- Mods

@Composable
private fun ModsPage(mods: List<InstalledMod>?, cells: CellFocus, left: FocusRequester, guide: OptionGuideState?, onOpen: (InstalledMod) -> Unit) {
    TakeFocus(cells, mods?.size ?: 0)
    PageColumn {
        when {
            mods == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BlockSpinner(Kit.ember, Modifier.size(28.dp))
                Text("Buscando tus mods…", color = Kit.muted, fontWeight = FontWeight.Bold, fontSize = TypeScale.body)
            }
            mods.isEmpty() -> Note("Este mundo no tiene mods. Puedes agregarlos desde el Taller de Biomeria.")
            else -> mods.forEachIndexed { index, mod ->
                RowBody(mod.name, mod.description?.replace('\n', ' '), Modifier.padCell(cells.at(index), left,
                    { cells.remember(index); guide?.mod = mod }, onClick = { onOpen(mod) }),
                    leading = { ModBadge(mod, 44.dp) }) {
                    mod.version?.let { Text("v$it", Modifier.widthIn(max = 90.dp), color = Kit.muted, fontWeight = FontWeight.Bold,
                        fontSize = TypeScale.detail, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(if (mod.settingCount > 0) "${mod.settingCount} ajustes" else "Sin ajustes",
                        Modifier.background(Kit.ink, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        color = if (mod.settingCount > 0) Kit.text else Kit.muted, fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.detail)
                    Text("›", color = Kit.muted, fontFamily = PixelFont, fontSize = TypeScale.title)
                }
            }
        }
    }
}

private fun showNumber(value: Double, integer: Boolean) =
    if (integer) value.roundToInt().toString() else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

/** How one mod setting looks and moves. */
private class ModControl(val setting: ModSetting, val value: String) {
    val integer = setting.type == ModValueType.NUMBER && '.' !in setting.value && (setting.min ?: 0.0) % 1 == 0.0 && (setting.max ?: 0.0) % 1 == 0.0
    val number = value.toDoubleOrNull() ?: 0.0
    val ranged = setting.type == ModValueType.NUMBER && setting.min != null && setting.max != null && setting.max > setting.min
    val step: Double = run {
        val span = if (ranged) setting.max!! - setting.min!! else 0.0
        when {
            integer -> if (ranged) maxOf(1.0, (span / 40).roundToInt().toDouble()) else 1.0
            ranged -> span / 20
            else -> .1
        }
    }
    val level: Float? = if (ranged) ((number - setting.min!!) / (setting.max!! - setting.min)).toFloat().coerceIn(0f, 1f) else null
    val choices = setting.choices
    val choiceIndex = choices?.indexOfFirst { it.equals(value, true) }?.coerceAtLeast(0) ?: 0

    fun stepped(by: Int): String? = when (setting.type) {
        ModValueType.BOOLEAN -> (value != "true").toString()
        ModValueType.NUMBER -> {
            var next = number + by * step
            if (ranged) next = next.coerceIn(setting.min!!, setting.max!!)
            showNumber(next, integer)
        }
        ModValueType.TEXT -> choices?.let { it[(choiceIndex + by + it.size) % it.size] }
    }
    fun at(fraction: Float): String? = if (!ranged) null else {
        val raw = setting.min!! + fraction * (setting.max!! - setting.min)
        showNumber((setting.min + ((raw - setting.min) / step).roundToInt() * step).coerceIn(setting.min, setting.max), integer)
    }
    val shown: String get() = when (setting.type) {
        ModValueType.BOOLEAN -> if (value == "true") "Sí" else "No"
        else -> value
    }
}

@Composable
private fun ModPage(mod: InstalledMod, cells: CellFocus, left: FocusRequester, guide: OptionGuideState?) {
    var changed by remember(mod) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val rows = remember(mod) { mod.configs.flatMap { config -> config.settings.map { config to it } } }
    TakeFocus(cells, rows.size)
    fun explain(config: ModConfigFile, control: ModControl) {
        guide?.modSetting = GuideModSetting(control.setting.label, control.shown, control.setting.comment, control.setting.type,
            control.level, config.file.name, control.choices)
    }
    fun change(config: ModConfigFile, setting: ModSetting, value: String) {
        changed = changed + ("${config.file.path}|${setting.path}" to value)
        explain(config, ModControl(setting, value))
        modSaving.launch { modSaveLock.withLock { runCatching { saveModConfig(config, mapOf(setting.path to value)) } } }
    }
    PageColumn {
        if (rows.isEmpty()) Note("${mod.name} no tiene ajustes que Biomeria pueda leer. Funciona solo, o se configura dentro del juego.")
        var lastFile: File? = null
        rows.forEachIndexed { index, (config, setting) ->
            if (mod.configs.size > 1 && config.file != lastFile) {
                Text(config.file.name, Modifier.padding(top = if (index == 0) 0.dp else 6.dp), color = Kit.sky,
                    fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.detail)
            }
            lastFile = config.file
            val control = ModControl(setting, changed["${config.file.path}|${setting.path}"] ?: setting.value)
            val step: ((Int) -> Unit)? = if (setting.type == ModValueType.TEXT && control.choices == null) null
                else { by -> control.stepped(by)?.let { change(config, setting, it) } }
            val click: (() -> Unit)? = when {
                setting.type == ModValueType.BOOLEAN || control.choices != null -> { { control.stepped(1)?.let { change(config, setting, it) } } }
                else -> null
            }
            RowBody(setting.label, setting.comment?.take(120), Modifier.padCell(cells.at(index), left,
                { cells.remember(index); explain(config, control) }, step, click)) {
                when {
                    setting.type == ModValueType.BOOLEAN -> SlideSwitch(control.value == "true")
                    control.ranged -> {
                        ValueBar(control.level ?: 0f, { fraction -> control.at(fraction)?.let { change(config, setting, it) } })
                        ValueText(control.value)
                    }
                    control.choices != null && control.choices.size <= 4 && control.choices.sumOf { it.length } <= 28 ->
                        ChoicePills(control.choices, control.choiceIndex) { i -> change(config, setting, control.choices[i]) }
                    step != null -> {
                        Text("‹", color = Kit.muted, fontFamily = PixelFont, fontSize = TypeScale.section)
                        ValueText(control.value, 60.dp)
                        Text("›", color = Kit.muted, fontFamily = PixelFont, fontSize = TypeScale.section)
                    }
                    else -> Text(control.value, Modifier.widthIn(max = 220.dp), color = Kit.muted, fontWeight = FontWeight.Bold,
                        fontSize = TypeScale.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
