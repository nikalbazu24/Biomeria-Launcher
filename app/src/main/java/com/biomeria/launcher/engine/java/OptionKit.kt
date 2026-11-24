package com.biomeria.launcher.engine.java

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.ui.theme.PixelFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

// ---------------------------------------------------------------- Colours shared by both screens' menus

internal object Kit {
    val grass = Color(0xFF7BC74D)
    val gold = Color(0xFFF5E663)
    val sky = Color(0xFF4FB3D9)
    val lilac = Color(0xFFB48CE0)
    val ember = Color(0xFFF07D4A)
    val wood = Color(0xFFC9955A)
    val ink = Color(0xFF0B0E11)
    val card = Color(0xFF161B21)
    val raised = Color(0xFF1E252C)
    val line = Color(0xFF2C343A)
    val text = Color(0xFFF2F5EF)
    val muted = Color(0xFFA9B6BD)
}

/**
 * One type scale for every Biomeria menu in game: title, section, body and detail. Everything grows
 * together with the panel's text size, and nothing goes under the detail size.
 */
internal object TypeScale {
    val title = 22.sp
    val section = 18.sp
    val body = 15.sp
    val detail = 13.sp
}

/** Text styles on [TypeScale], in Biomeria's body font. */
internal object PanelText {
    val title: androidx.compose.ui.text.TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleLarge.copy(fontSize = TypeScale.title, lineHeight = 26.sp, letterSpacing = 0.sp)
    val section: androidx.compose.ui.text.TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleMedium.copy(fontSize = TypeScale.section, lineHeight = 22.sp, letterSpacing = 0.sp)
    val body: androidx.compose.ui.text.TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLarge.copy(fontSize = TypeScale.body, lineHeight = 20.sp, letterSpacing = 0.sp)
    val detail: androidx.compose.ui.text.TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelMedium.copy(fontSize = TypeScale.detail, lineHeight = 17.sp, letterSpacing = 0.sp)
}

// ---------------------------------------------------------------- Pixel glyphs

/** Small 8×8 pixel drawings for tabs and menu tiles, in Minecraft's blocky spirit. */
internal enum class Glyph(vararg rows: String) {
    BAG("..####..", ".#....#.", "########", "#..##..#", "#..##..#", "########", "#......#", "########"),
    TABLE("########", "#.#..#.#", "########", ".#....#.", ".#....#.", ".#....#.", ".#....#.", "........"),
    MAP("########", "#..#...#", "#.##.#.#", "#...##.#", "##....##", "#..#...#", "#.##...#", "########"),
    GEAR("...##...", ".#.##.#.", "..####..", "###..###", "###..###", "..####..", ".#.##.#.", "...##..."),
    CHEST("########", "#......#", "#......#", "###..###", "#..##..#", "#......#", "#......#", "########"),
    EYE("........", "..####..", ".#....#.", "#..##..#", "#..##..#", ".#....#.", "..####..", "........"),
    NOTE("....###.", "....#.#.", "....#...", "....#...", ".###....", "#..#....", "#..#....", ".##....."),
    PAD("........", ".######.", "#......#", "#.#..#.#", "###..#.#", "#.#....#", "#......#", ".######."),
    CHAT("########", "#......#", "#.####.#", "#......#", "#.###..#", "########", ".##.....", ".#......"),
    HAND("..#.#...", ".##.#.#.", ".######.", ".######.", "########", ".######.", "..####..", "..####.."),
    PUZZLE("..##....", ".####...", "########", "#......#", "#......#", "##....##", "#......#", "########"),
    SUN("#..#..#.", ".#.#.#..", "..###...", "####.###", "..###...", ".#.#.#..", "#..#..#.", "........"),
    TOOL(".....###", "....###.", "...###..", "..###...", ".#.#....", "#.#.....", ".#......", "........"),
    BLOCK("########", "#......#", "#.####.#", "#.#..#.#", "#.#..#.#", "#.####.#", "#......#", "########"),
    FOOD("..####..", ".#####..", "#######.", "#######.", ".######.", "..####..", "...##...", "........"),
    FLAME("...#....", "..##....", "..###.#.", ".#####..", ".######.", "########", ".######.", "..####.."),
    ALL("##.##.##", "##.##.##", "........", "##.##.##", "##.##.##", "........", "##.##.##", "##.##.##"),
    TEXT("########", "#..##..#", "...##...", "...##...", "...##...", "...##...", "...##...", "..####.."),
    SCREENS("########", "#......#", "#......#", "########", "........", "########", "#......#", "########"),
    BOLT("....###.", "...###..", "..###...", ".######.", "...###..", "..###...", ".##.....", "#......."),
    SHADER("#.#.#.#.", ".......#", "#.####..", "..####.#", "#.####..", "..####.#", "#.......", ".#.#.#.#");

    val pixels: List<String> = rows.toList()
}

@Composable
internal fun PixelGlyph(glyph: Glyph, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val cell = minOf(size.width, size.height) / 8f
        val left = (size.width - cell * 8) / 2
        val top = (size.height - cell * 8) / 2
        glyph.pixels.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '#') drawRect(color, Offset(left + x * cell, top + y * cell), Size(cell + .5f, cell + .5f))
            }
        }
    }
}

// ---------------------------------------------------------------- options.txt, applied live

/** Minecraft's options.txt with changes written a moment after the last touch and applied in game. */
internal class LiveGameOptions(private val file: GameOptionsFile, private val bridge: DualScreenBridge?) {
    var values by mutableStateOf(file.read())
        private set
    var status by mutableStateOf<String?>(null)
        internal set
    internal var pending by mutableStateOf<Map<String, String>>(emptyMap())

    /** Reads options.txt again, e.g. after the full options changed it. */
    fun reload() {
        if (pending.isEmpty()) values = file.read()
    }

    fun change(key: String, value: String) {
        values = values + (key to value)
        pending = pending + (key to value)
    }

    internal suspend fun flush() {
        if (pending.isEmpty()) return
        delay(400)
        val changes = pending
        pending = emptyMap()
        withContext(Dispatchers.IO) { runCatching { file.write(changes) } }
        if (bridge?.reloadOptions() == true) {
            status = "Aplicando…"
            repeat(20) {
                delay(150)
                bridge.optionsApplied()?.let { applied ->
                    status = if (applied) "Aplicado" else "Se aplicará la próxima vez que abras el juego"
                    return
                }
            }
            status = "Se aplicará la próxima vez que abras el juego"
        } else status = "Guardado · se aplica al volver a abrir el juego"
    }
}

@Composable
internal fun rememberLiveGameOptions(gameDirectory: File?, bridge: DualScreenBridge?): LiveGameOptions? {
    val options = remember(gameDirectory, bridge) { gameDirectory?.let { LiveGameOptions(GameOptionsFile(it), bridge) } }
    if (options != null) LaunchedEffect(options, options.pending) { options.flush() }
    return options
}

/** Minecraft stores the field of view as -1…1 around 70°: 30° is -1 and 110° is 1. */
internal fun fovDegrees(raw: String?): Int = ((raw?.trim('"')?.toDoubleOrNull() ?: 0.0).coerceIn(-1.0, 1.0) * 40 + 70).roundToInt()
internal fun fovRaw(degrees: Int): String = formatOptionNumber(((degrees.coerceIn(30, 110) - 70) / 40.0), true)

// ---------------------------------------------------------------- Field of view

/** The field of view as a cone seen from above: it opens and closes while the slider moves. */
@Composable
internal fun FovCone(degrees: Int, modifier: Modifier) {
    Canvas(modifier.background(Kit.ink, RoundedCornerShape(8.dp))) {
        val eye = Offset(size.width / 2, size.height * .88f)
        val reach = size.height * .78f
        val half = (degrees / 2f).coerceIn(10f, 60f)
        val spread = (reach * tan(Math.toRadians(half.toDouble()))).toFloat().coerceAtMost(size.width * .48f)
        val cone = Path().apply {
            moveTo(eye.x, eye.y)
            lineTo(eye.x - spread, eye.y - reach)
            lineTo(eye.x + spread, eye.y - reach)
            close()
        }
        drawPath(cone, Kit.grass.copy(alpha = .3f))
        drawPath(cone, Kit.grass, style = Stroke(3.dp.toPx()))
        val block = size.height * .13f
        listOf(.2f to .35f, .5f to .2f, .8f to .3f, .33f to .58f).forEachIndexed { index, (x, y) ->
            drawRect(listOf(Color(0xFF6FBF45), Color(0xFFC9955A), Color(0xFF8A8F94), Color(0xFF4FB3D9))[index],
                Offset(size.width * x - block / 2, size.height * y - block / 2), Size(block, block))
        }
        drawRect(Color(0xFFC99A76), Offset(eye.x - block / 2, eye.y - block / 2), Size(block, block))
    }
}

@Composable
internal fun FovSlider(degrees: Int, onDegrees: (Int) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Slider(
        value = dragging ?: degrees.toFloat(),
        onValueChange = { dragging = it },
        onValueChangeFinished = { dragging?.let { onDegrees(it.roundToInt()) }; dragging = null },
        valueRange = 30f..110f,
        steps = 15,
        modifier = modifier.semantics { contentDescription = "Campo de visión" },
        colors = goldSlider()
    )
}

@Composable
internal fun goldSlider() = SliderDefaults.colors(
    thumbColor = Kit.gold, activeTrackColor = Kit.gold, inactiveTrackColor = Color(0xFF34404A),
    activeTickColor = Color(0x00000000), inactiveTickColor = Color(0x00000000)
)

private val fovPresets = listOf("Normal" to 70, "Amplio" to 90, "Quake Pro" to 110)

/** Big field-of-view card: the cone, the degrees, a slider and three quick choices. */
@Composable
internal fun FovCard(degrees: Int, onDegrees: (Int) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier.kitCard().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Campo de visión", Modifier.weight(1f), color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.section,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text("$degrees°", color = Kit.gold, fontFamily = PixelFont, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!compact) FovCone(degrees, Modifier.size(112.dp, 76.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FovSlider(degrees, onDegrees, Modifier.fillMaxWidth())
                Segmented(fovPresets, fovPresets.firstOrNull { it.second == degrees }?.second, { it.first },
                    { onDegrees(it.second) }, Kit.gold, Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------- Small building blocks

internal fun Modifier.kitCard(color: Color = Kit.card, outline: Color = Kit.line) =
    this.background(color, RoundedCornerShape(10.dp)).border(2.dp, outline, RoundedCornerShape(10.dp))

/** Choices side by side in one dark track; the chosen one lights up in [accent]. */
@Composable
internal fun <T> Segmented(
    options: List<T>, selected: Any?, name: (T) -> String, onPick: (T) -> Unit, accent: Color, modifier: Modifier = Modifier,
    keyOf: (T) -> Any? = { if (it is Pair<*, *>) it.second else it }
) {
    Row(modifier.background(Kit.ink, RoundedCornerShape(8.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { option ->
            val on = keyOf(option) == selected
            Box(Modifier.weight(1f).heightIn(min = 38.dp).background(if (on) accent else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable(role = Role.RadioButton, onClick = { onPick(option) }).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center) {
                Text(name(option), color = if (on) Kit.ink else Kit.muted, fontWeight = FontWeight.ExtraBold,
                    style = PanelText.body, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A round switch, green when on. */
@Composable
internal fun KitSwitch(on: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(58.dp, 32.dp).background(if (on) Kit.grass else Color(0xFF3A454F), RoundedCornerShape(16.dp)).padding(4.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(24.dp).background(Kit.text, RoundedCornerShape(12.dp)))
    }
}

/** A menu tile: coloured square with its glyph, a title and one line saying what is inside. */
@Composable
internal fun MenuTile(
    title: String, detail: String, glyph: Glyph, color: Color, onClick: () -> Unit,
    modifier: Modifier = Modifier, focused: Boolean = false
) {
    Column(modifier.kitCard(outline = if (focused) Kit.gold else Kit.line)
        .then(if (focused) Modifier.border(3.dp, Kit.gold, RoundedCornerShape(10.dp)) else Modifier)
        .clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(44.dp).background(color, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            PixelGlyph(glyph, Kit.ink, Modifier.size(28.dp))
        }
        Text(title, color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.section, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(detail, color = Kit.muted, fontWeight = FontWeight.Bold, style = PanelText.detail, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Shaders (Iris)

internal data class ShaderPack(val file: String, val title: String)

internal data class ShaderSettings(val irisInstalled: Boolean, val enabled: Boolean, val current: String?, val packs: List<ShaderPack>) {
    val active: ShaderPack? get() = if (enabled) packs.firstOrNull { it.file == current } else null
}

private fun irisFile(gameDirectory: File) = File(gameDirectory, "config/iris.properties")

internal fun readShaderSettings(gameDirectory: File): ShaderSettings {
    val mods = File(gameDirectory, "mods").listFiles().orEmpty()
    val iris = mods.any { it.name.lowercase().let { name -> name.startsWith("iris") && name.endsWith(".jar") } }
    val properties = Properties()
    runCatching { irisFile(gameDirectory).inputStream().use(properties::load) }
    val packs = File(gameDirectory, "shaderpacks").listFiles().orEmpty()
        .filter { it.isDirectory || it.extension.equals("zip", ignoreCase = true) }
        .map { ShaderPack(it.name, prettyShaderName(it.name)) }
        .sortedBy { it.title.lowercase() }
    return ShaderSettings(iris, properties.getProperty("enableShaders", "true") == "true",
        properties.getProperty("shaderPack")?.takeIf { it.isNotBlank() }, packs)
}

private fun prettyShaderName(file: String): String = file.removeSuffix(".zip").removeSuffix(".ZIP")
    .replace('_', ' ').replace(Regex("\\s+"), " ").trim()

/** Writes the choice into Iris' own settings file, keeping everything else in it. null turns shaders off. */
internal fun writeShaderChoice(gameDirectory: File, pack: String?) {
    val file = irisFile(gameDirectory)
    file.parentFile?.mkdirs()
    val lines = runCatching { file.readLines() }.getOrDefault(emptyList()).toMutableList()
    fun put(key: String, value: String) {
        val index = lines.indexOfFirst { it.substringBefore('=').trim() == key }
        val line = "$key=$value"
        if (index >= 0) lines[index] = line else lines += line
    }
    put("enableShaders", (pack != null).toString())
    if (pack != null) put("shaderPack", pack.replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\="))
    file.writeText(lines.joinToString("\n", postfix = "\n"))
}

/** A sky-and-ground picture for a shader pack, different for each name, until we have its real screenshots. */
internal fun shaderSky(name: String?): Brush {
    if (name == null) return Brush.verticalGradient(0f to Color(0xFF5AA7E0), .6f to Color(0xFF9FD0F0), .6f to Color(0xFF5C9A3A), 1f to Color(0xFF3F6E2A))
    val skies = listOf(
        Color(0xFFF39A5A) to Color(0xFFF7CF7A), Color(0xFF24305E) to Color(0xFFC76B8F), Color(0xFF3AA0FF) to Color(0xFFA0E3FF),
        Color(0xFFFFB86B) to Color(0xFFFFEEC7), Color(0xFF2B1D4A) to Color(0xFF7B5BB8), Color(0xFF0F5E7A) to Color(0xFF79D3D0)
    )
    val (top, horizon) = skies[abs(name.hashCode()) % skies.size]
    return Brush.verticalGradient(0f to top, .58f to horizon, .58f to Color(0xFF4E8A33), 1f to Color(0xFF2B4D1D))
}

/** The shader state with what it takes to change it, shared by the top options and the lower panel. */
internal class ShaderController(private val gameDirectory: File?, private val bridge: DualScreenBridge?) {
    var settings by mutableStateOf<ShaderSettings?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    suspend fun load() {
        val directory = gameDirectory ?: return
        settings = withContext(Dispatchers.IO) { readShaderSettings(directory) }
    }

    suspend fun choose(pack: ShaderPack?) {
        val directory = gameDirectory ?: return
        val now = settings ?: return
        settings = now.copy(enabled = pack != null, current = pack?.file ?: now.current)
        status = "Cargando shaders…"
        withContext(Dispatchers.IO) { runCatching { writeShaderChoice(directory, pack?.file) } }
        if (bridge?.reloadShaders() != true) {
            status = "Guardado · se aplica al volver a abrir el juego"
            return
        }
        repeat(80) {
            delay(150)
            bridge.shadersApplied()?.let { applied ->
                status = if (applied) (if (pack == null) "Shaders apagados" else "${pack.title} activado")
                    else "Se aplicará la próxima vez que abras el juego"
                return
            }
        }
        status = "Se aplicará la próxima vez que abras el juego"
    }
}

@Composable
internal fun rememberShaderController(gameDirectory: File?, bridge: DualScreenBridge?): ShaderController {
    val controller = remember(gameDirectory, bridge) { ShaderController(gameDirectory, bridge) }
    LaunchedEffect(controller) { controller.load() }
    return controller
}

/** The shader in use, as a picture with its name; tap to choose another. */
@Composable
internal fun ShaderCard(controller: ShaderController, onOpen: () -> Unit, modifier: Modifier = Modifier, pictureHeight: Dp = 72.dp) {
    val settings = controller.settings
    val active = settings?.active
    Column(modifier.kitCard().clickable(role = Role.Button, onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shaders", Modifier.weight(1f), color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.section, fontWeight = FontWeight.Bold)
            Text("Cambiar", color = Kit.gold, fontWeight = FontWeight.ExtraBold, style = PanelText.body)
        }
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = pictureHeight)
            .background(shaderSky(active?.title), RoundedCornerShape(8.dp)))
        Text(when {
            settings == null -> "Leyendo…"
            !settings.irisInstalled -> "Instala Iris en el Taller para usar shaders"
            active == null -> "Apagados"
            else -> active.title
        }, color = Kit.muted, fontWeight = FontWeight.ExtraBold, style = PanelText.body, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** A window over the menu with every installed shader pack as a picture, plus "Sin shaders". */
@Composable
internal fun ShaderPicker(controller: ShaderController, onClose: () -> Unit, onChoose: (ShaderPack?) -> Unit) {
    val settings = controller.settings
    val quiet = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(quiet, null, onClick = onClose).padding(16.dp),
        contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight().kitCard(outline = Color(0xFF34404A))
            .clickable(remember { MutableInteractionSource() }, null) {}.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelGlyph(Glyph.SHADER, Kit.gold, Modifier.size(26.dp))
                Text("Shaders", Modifier.weight(1f), color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.title, fontWeight = FontWeight.Bold)
                controller.status?.let { Text(it, color = Kit.grass, fontWeight = FontWeight.ExtraBold, style = PanelText.body) }
            }
            when {
                settings == null -> Text("Buscando tus shaders…", color = Kit.muted)
                !settings.irisInstalled -> Text("Para usar shaders, este mundo necesita el mod Iris. Instálalo desde el Taller de Biomeria y vuelve a abrir el juego.",
                    color = Kit.text, style = PanelText.body)
                else -> {
                    val choices = listOf<ShaderPack?>(null) + settings.packs
                    LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(choices, key = { it?.file ?: "" }) { pack ->
                            val on = settings.active?.file == pack?.file
                            Column(Modifier.background(Kit.raised, RoundedCornerShape(10.dp))
                                .border(if (on) 4.dp else 2.dp, if (on) Kit.gold else Kit.line, RoundedCornerShape(10.dp))
                                .clickable(role = Role.RadioButton, onClick = { onChoose(pack) }).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.fillMaxWidth().height(84.dp).background(shaderSky(pack?.title), RoundedCornerShape(6.dp)))
                                Text(pack?.title ?: "Sin shaders", color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.body,
                                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (pack == null) "Lo más rápido" else if (on) "En uso" else "Tocar para usar", color = Kit.muted,
                                    fontWeight = FontWeight.Bold, style = PanelText.detail, maxLines = 1)
                            }
                        }
                    }
                    if (settings.packs.isEmpty()) Text("No tienes shaders instalados. Búscalos en el Taller, sección Shaders.",
                        color = Kit.muted, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                com.biomeria.launcher.ui.components.BlockButton(onClose, colors = com.biomeria.launcher.ui.components.BlockButtons.primary) {
                    Text("Listo", fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(6.dp))
                    PadKey("B")
                }
            }
        }
    }
}
