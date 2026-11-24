package com.biomeria.launcher.engine.java

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.theme.PixelFont
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** What the full options on the top screen tell the guide on the lower screen. */
internal class OptionGuideState {
    var open by mutableStateOf(false)
    var key by mutableStateOf("fov")
    var values by mutableStateOf<Map<String, String>>(emptyMap())
    var shader by mutableStateOf<String?>(null)
}

private enum class Demo { FOV, SHADERS, CHUNKS, LEAVES, FPS, VSYNC, LIGHT, SCALE, CLOUDS, PARTICLES, CREATURES, SOUND, CHAT, WAVE, SWITCH }

private class Help(val title: String, val what: String, val change: String, val best: String, val demo: Demo)

private val helps: Map<String, Help> = mapOf(
    "fov" to Help("Campo de visión", "Cuánto ves hacia los lados. Con más grados entra más mundo en la pantalla, pero todo se ve más chico y lejano.",
        "Súbelo si sientes que ves muy poco al explorar. Bájalo si te mareas.", "70° a 90°", Demo.FOV),
    "shaders" to Help("Shaders", "Cambian la luz del juego: sombras, reflejos en el agua y cielos más bonitos.",
        "Úsalos para pasear o sacar fotos. Para jugar mucho rato, mejor uno liviano o apagados.", "MakeUp Ultra Fast o Complementary", Demo.SHADERS),
    "renderDistance" to Help("Distancia de renderizado", "Cuántos trozos de mundo (chunks) se dibujan a tu alrededor. Más chunks: ves más lejos.",
        "Bájala si el juego va lento o la consola se calienta.", "8 a 12 chunks", Demo.CHUNKS),
    "simulationDistance" to Help("Distancia de simulación", "Hasta dónde el mundo sigue vivo: cultivos que crecen, máquinas y criaturas que se mueven.",
        "Bájala si tienes granjas grandes y el juego se pone lento.", "6 a 8 chunks", Demo.CHUNKS),
    "graphicsMode" to Help("Gráficos", "Rápidos: hojas sólidas. Detallados: hojas con huecos. Fabulosos: mejor agua y vidrio, pero pesa mucho.",
        "Detallados es el equilibrio. Rápidos si buscas más FPS.", "Detallados", Demo.LEAVES),
    "maxFps" to Help("FPS máximos", "Cuántas imágenes por segundo puede dibujar el juego. Más FPS: se mueve más suave.",
        "Bájalo para ahorrar batería; súbelo si tu consola va sobrada.", "60 con batería, 120 enchufada", Demo.FPS),
    "enableVsync" to Help("Sincronización vertical", "Evita que la imagen se vea cortada en dos al girar rápido la cámara.",
        "Actívala si ves la imagen cortada; puede sentirse un poco más lenta.", "Apagada, con límite de FPS", Demo.VSYNC),
    "gamma" to Help("Brillo", "Qué tan claras se ven las zonas oscuras, como cuevas y noches.",
        "Súbelo si juegas de noche o no ves en las cuevas.", "50 % a 70 %", Demo.LIGHT),
    "guiScale" to Help("Tamaño de la interfaz", "El tamaño de la barra rápida, los corazones y los menús de Minecraft.",
        "Agrándalo si te cuesta leer; achícalo para ver más mundo.", "Automático o 3", Demo.SCALE),
    "renderClouds" to Help("Nubes", "Rápidas: nubes planas. Detalladas: con volumen. No: el cielo limpio.",
        "Apágalas si quieres unos FPS extra.", "Rápidas", Demo.CLOUDS),
    "particles" to Help("Partículas", "El humo, las chispas, las burbujas y los trocitos al romper bloques.",
        "Ponlas en Menos si hay mucho lío en pantalla y el juego baja.", "Todas o Menos", Demo.PARTICLES),
    "entityShadows" to Help("Sombras de criaturas", "La sombrita redonda bajo animales, monstruos y objetos tirados.",
        "Apágalas para ganar un poco de rendimiento.", "Encendidas", Demo.SWITCH),
    "biomeBlendRadius" to Help("Mezcla de biomas", "Qué tan suave cambia el color del pasto y el agua entre un bioma y otro.",
        "Bájala si el mundo tarda en cargar.", "3×3 o 5×5", Demo.SCALE),
    "mipmapLevels" to Help("Niveles de mipmap", "Suaviza las texturas lejanas para que no se vean con ruido.",
        "Bájalo si usas un paquete de texturas pixelado y lo prefieres nítido.", "2 a 4", Demo.SCALE),
    "entityDistanceScaling" to Help("Distancia de criaturas", "Hasta qué distancia se dibujan los animales y monstruos.",
        "Bájala si hay muchas criaturas cerca y el juego se pone lento.", "75 % a 100 %", Demo.CREATURES),
    "bobView" to Help("Balanceo al caminar", "La cámara sube y baja un poquito mientras caminas.",
        "Apágalo si te marea.", "A tu gusto", Demo.WAVE),
    "soundCategory_master" to Help("Volumen general", "El volumen de todo el juego a la vez.", "Úsalo antes que los demás.", "70 % a 100 %", Demo.SOUND),
    "soundCategory_music" to Help("Música", "La música de fondo de Minecraft.", "Bájala si quieres escuchar tu propia música.", "A tu gusto", Demo.SOUND),
    "soundCategory_record" to Help("Tocadiscos y bloques musicales", "Los discos y los bloques de notas.", "Bájalo si un vecino tiene una granja de música.", "A tu gusto", Demo.SOUND),
    "soundCategory_weather" to Help("Clima", "La lluvia y los truenos.", "Bájalo si la lluvia te molesta.", "A tu gusto", Demo.SOUND),
    "soundCategory_block" to Help("Bloques", "Romper, poner y pisar bloques, puertas y cofres.", "Bájalo si construyes mucho rato.", "A tu gusto", Demo.SOUND),
    "soundCategory_hostile" to Help("Criaturas hostiles", "Zombis, esqueletos, creepers y compañía.", "Súbelo para oírlos llegar antes.", "100 %", Demo.SOUND),
    "soundCategory_neutral" to Help("Criaturas amistosas", "Vacas, ovejas, aldeanos y mascotas.", "Bájalo si tienes una granja ruidosa.", "A tu gusto", Demo.SOUND),
    "soundCategory_player" to Help("Jugadores", "Tus pasos, golpes y los de tus amigos.", "Bájalo si te distrae en multijugador.", "A tu gusto", Demo.SOUND),
    "soundCategory_ambient" to Help("Ambiente", "Los sonidos de cuevas y del agua.", "Súbelo para más ambiente al explorar.", "A tu gusto", Demo.SOUND),
    "showSubtitles" to Help("Subtítulos", "Escribe en pantalla qué sonido hubo y de dónde viene.", "Actívalos si juegas sin sonido.", "Si juegas en silencio", Demo.CHAT),
    "mouseSensitivity" to Help("Sensibilidad de la cámara", "Qué tan rápido gira la cámara al mover el dedo o el stick.",
        "Bájala si apuntar te cuesta; súbela si giras muy lento.", "80 % a 110 %", Demo.WAVE),
    "invertYMouse" to Help("Invertir cámara vertical", "Al subir el stick, la cámara mira hacia abajo, como en aviones.", "Solo si estás acostumbrado.", "Apagado", Demo.SWITCH),
    "toggleCrouch" to Help("Agacharse: alternar", "Un toque te agacha y otro te levanta, sin mantener apretado.", "Útil en bordes y para construir.", "A tu gusto", Demo.SWITCH),
    "toggleSprint" to Help("Correr: alternar", "Un toque y corres hasta que pares.", "Útil para viajes largos.", "Encendido en consola", Demo.SWITCH),
    "autoJump" to Help("Salto automático", "Saltas solo al chocar con un bloque de altura uno.", "Apágalo si saltas sin querer al construir.", "Encendido para jugar con el dedo", Demo.SWITCH),
    "chatVisibility" to Help("Chat", "Qué mensajes se ven en pantalla.", "Ocúltalo para fotos o videos.", "Visible", Demo.CHAT),
    "chatOpacity" to Help("Opacidad del texto", "Qué tan sólido se ve el texto del chat.", "Bájala para que el chat tape menos.", "100 %", Demo.CHAT),
    "textBackgroundOpacity" to Help("Fondo del texto", "La franja oscura detrás de los mensajes.", "Súbela si te cuesta leer sobre el pasto o la nieve.", "50 %", Demo.CHAT),
    "chatScale" to Help("Tamaño del chat", "El tamaño de la letra del chat.", "Agrándalo si lees de lejos.", "100 %", Demo.CHAT),
    "chatColors" to Help("Colores en el chat", "Permite mensajes con colores.", "Apágalo si prefieres todo en blanco.", "Encendido", Demo.SWITCH),
    "chatLinks" to Help("Enlaces en el chat", "Permite tocar enlaces que mandan otros jugadores.", "Apágalo si juegas con niños pequeños.", "A tu criterio", Demo.SWITCH),
    "screenEffectScale" to Help("Efectos de distorsión", "La pantalla ondulada de la náusea y los portales.", "Bájalo si te mareas.", "Bajo si te mareas", Demo.WAVE),
    "fovEffectScale" to Help("Efectos de campo de visión", "Cuánto se abre la vista al correr o con velocidad.", "Bájalo si el zoom al correr te molesta.", "A tu gusto", Demo.FOV),
    "darknessEffectScale" to Help("Pulso de oscuridad", "El oscurecer que causa el Warden y los sensores sculk.", "Bájalo si te molesta el parpadeo.", "Bajo si te molesta", Demo.LIGHT),
    "hideLightningFlashes" to Help("Ocultar destellos de rayos", "Quita el fogonazo blanco de los rayos.", "Actívalo si los destellos te molestan.", "Encendido para ojos sensibles", Demo.SWITCH),
    "highContrast" to Help("Alto contraste", "Botones y bordes más marcados en los menús de Minecraft.", "Actívalo si te cuesta distinguir los botones.", "A tu gusto", Demo.SWITCH),
    "reducedDebugInfo" to Help("Información de depuración reducida", "Esconde coordenadas y datos técnicos de la pantalla F3.", "Actívalo para jugar sin pistas.", "Apagado", Demo.SWITCH)
)

private fun helpFor(key: String): Help = helps[key] ?: Help(key, "Cambia cómo funciona esta parte del juego.",
    "Pruébalo y mira la diferencia arriba.", "El valor de fábrica", Demo.SWITCH)

/** The current value of an option, written the way the options screen shows it. */
private fun shownValue(key: String, guide: OptionGuideState): String {
    if (key == "fov") return "${fovDegrees(guide.values["fov"])}°"
    if (key == "shaders") return guide.shader ?: "Apagados"
    val raw = guide.values[key] ?: return "—"
    return when (val option = gameOptionGroups.values.flatten().firstOrNull { it.key == key }) {
        is GameOption.Number -> raw.trim('"').toDoubleOrNull()?.let { option.format(it) } ?: raw
        is GameOption.Choice -> option.values.firstOrNull { it.first == raw }?.second ?: raw
        is GameOption.Toggle -> if (raw == "true") "Encendido" else "Apagado"
        null -> raw
    }
}

/** 0…1 for where the option sits in its range, used by the demonstrations. */
private fun level(key: String, guide: OptionGuideState): Float {
    if (key == "fov") return (fovDegrees(guide.values["fov"]) - 30) / 80f
    if (key == "shaders") return if (guide.shader != null) 1f else 0f
    val raw = guide.values[key]?.trim('"') ?: return .5f
    return when (val option = gameOptionGroups.values.flatten().firstOrNull { it.key == key }) {
        is GameOption.Number -> raw.toDoubleOrNull()?.let { ((it - option.min) / (option.max - option.min)).toFloat().coerceIn(0f, 1f) } ?: .5f
        is GameOption.Choice -> {
            val index = option.values.indexOfFirst { it.first.trim('"') == raw }.coerceAtLeast(0)
            val position = if (option.values.size > 1) index / (option.values.size - 1f) else 1f
            // Particles count down: "Todas" is the first choice.
            if (key == "particles") 1f - position else if (key == "renderClouds") listOf("false", "fast", "true").indexOf(raw).coerceAtLeast(0) / 2f else position
        }
        is GameOption.Toggle -> if (raw == "true") 1f else 0f
        null -> .5f
    }
}

/**
 * The lower screen while the full options are open above: the option being touched, what it does,
 * when to change it, what suits the Thor, and a small moving demonstration of its current value.
 */
@Composable
internal fun OptionGuidePage(guide: OptionGuideState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val key = guide.key
    val help = helpFor(key)
    val value = shownValue(key, guide)
    Column(modifier.background(Color(0xFF101418))) {
        Row(Modifier.fillMaxWidth().background(Kit.ink).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PixelGlyph(Glyph.EYE, Kit.lilac, Modifier.size(26.dp))
            Text("Guía de ajustes", Modifier.weight(1f), color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.title,
                fontWeight = FontWeight.Bold, maxLines = 1)
            BlockButton(onBack, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Volver a ajustes rápidos", fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.body, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                PadKey("Select")
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1.1f).fillMaxHeight().kitCard().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Así se ve", Modifier.weight(1f), color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.section,
                        fontWeight = FontWeight.Bold)
                    Text(value, color = Kit.gold, fontFamily = PixelFont, fontSize = TypeScale.section, maxLines = 1)
                }
                DemoCanvas(help.demo, level(key, guide), key, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)))
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(help.title, color = Kit.text, fontFamily = PixelFont, fontSize = TypeScale.title, fontWeight = FontWeight.Bold,
                    lineHeight = TypeScale.title * 1.1f)
                Text("Ahora: $value", color = Kit.gold, fontFamily = PixelFont, fontSize = TypeScale.section, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                GuideBlock("Qué hace", help.what, Kit.line)
                GuideBlock("Cuándo cambiarlo", help.change, Kit.line)
                GuideBlock("Recomendado en la Thor", help.best, Kit.grass)
            }
        }
        Row(Modifier.fillMaxWidth().background(Kit.ink).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HintPair("✛", "Elige un ajuste arriba")
            HintPair("A", "Cambiarlo")
            HintPair("Select", "Volver")
        }
    }
}

@Composable
private fun GuideBlock(label: String, text: String, edge: Color) {
    Row(Modifier.fillMaxWidth().kitCard()) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(edge, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = Kit.muted, fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.detail)
            Text(text, color = Kit.text, fontWeight = FontWeight.Bold, fontSize = TypeScale.body, lineHeight = TypeScale.body * 1.35f)
        }
    }
}

@Composable
private fun HintPair(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PadKey(key)
        Text(label, color = Kit.text, fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.detail, maxLines = 1)
    }
}

@Composable
private fun DemoCanvas(demo: Demo, level: Float, key: String, modifier: Modifier) {
    val time by rememberInfiniteTransition(label = "demo").animateFloat(0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "t")
    Canvas(modifier.background(Kit.ink)) {
        when (demo) {
            Demo.FOV -> drawFov(30f + level * 80f)
            Demo.SHADERS -> drawShaders(level > .5f)
            Demo.CHUNKS -> drawChunks(level)
            Demo.LEAVES -> drawLeaves(level)
            Demo.FPS -> drawFps(time, level)
            Demo.VSYNC -> drawVsync(level > .5f, time)
            Demo.LIGHT -> drawLight(level)
            Demo.SCALE -> drawScale(level)
            Demo.CLOUDS -> drawClouds(level, time)
            Demo.PARTICLES -> drawParticles(level, time)
            Demo.CREATURES -> drawCreatures(level)
            Demo.SOUND -> drawSound(level, time)
            Demo.CHAT -> drawChat(level, key)
            Demo.WAVE -> drawWave(level, time)
            Demo.SWITCH -> drawSwitch(level > .5f)
        }
    }
}

private val grassTop = Color(0xFF6FBF45)
private val grassDark = Color(0xFF3F8A2A)
private val stone = Color(0xFF8A8F94)
private val wood = Color(0xFFC9955A)
private val skin = Color(0xFFC99A76)

private fun DrawScope.block(color: Color, x: Float, y: Float, side: Float) =
    drawRect(color, Offset(x - side / 2, y - side / 2), Size(side, side))

private fun DrawScope.drawFov(degrees: Float) {
    val eye = Offset(size.width / 2, size.height * .9f)
    val reach = size.height * .8f
    val spread = (reach * tan(Math.toRadians((degrees / 2).toDouble()))).toFloat().coerceAtMost(size.width * .48f)
    val cone = Path().apply { moveTo(eye.x, eye.y); lineTo(eye.x - spread, eye.y - reach); lineTo(eye.x + spread, eye.y - reach); close() }
    drawPath(cone, Kit.grass.copy(alpha = .28f))
    drawPath(cone, Kit.grass, style = Stroke(3.dp.toPx()))
    val side = size.minDimension * .1f
    listOf(Triple(.15f, .3f, grassTop), Triple(.5f, .22f, wood), Triple(.85f, .28f, stone), Triple(.3f, .55f, Kit.sky)).forEach { (x, y, c) ->
        block(c, size.width * x, size.height * y, side)
    }
    block(skin, eye.x, eye.y, side)
}

private fun DrawScope.drawShaders(on: Boolean) {
    val half = size.width / 2
    val horizon = size.height * .6f
    drawRect(Color(0xFF7DBBE8), Offset.Zero, Size(half, horizon))
    drawRect(Color(0xFF5C9A3A), Offset(0f, horizon), Size(half, size.height - horizon))
    drawRect(Color(0xFFF3B26A), Offset(half, 0f), Size(half, horizon * .55f))
    drawRect(Color(0xFFF7D68F), Offset(half, horizon * .55f), Size(half, horizon * .45f))
    drawRect(Color(0xFF3F6E2A), Offset(half, horizon), Size(half, size.height - horizon))
    drawRect(Color(0x55000000), Offset(half + half * .3f, horizon), Size(half * .4f, size.height * .12f))
    val glow = if (on) Kit.gold else Color.White.copy(alpha = .25f)
    drawRect(glow, Offset(if (on) half else 0f, 0f), Size(half, 6.dp.toPx()))
}

private fun DrawScope.drawChunks(level: Float) {
    val columns = 21
    val rows = 13
    val cell = minOf(size.width / columns, size.height / rows)
    val left = (size.width - cell * columns) / 2
    val top = (size.height - cell * rows) / 2
    val radius = (1 + level * 9).roundToInt()
    for (y in 0 until rows) for (x in 0 until columns) {
        val distance = max(abs(x - columns / 2), abs(y - rows / 2))
        if (distance <= radius) {
            val color = if (distance == 0) Kit.gold else grassTop.copy(alpha = (1f - distance * .07f).coerceAtLeast(.25f))
            drawRect(color, Offset(left + x * cell + 1, top + y * cell + 1), Size(cell - 2, cell - 2))
        }
    }
}

private fun DrawScope.drawLeaves(level: Float) {
    val side = size.minDimension * .42f
    listOf(.27f, .73f).forEachIndexed { index, x ->
        val cx = size.width * x
        val top = size.height * .18f
        drawRect(Color(0xFF6B4A2A), Offset(cx - side * .08f, top + side), Size(side * .16f, size.height * .2f))
        val fancy = index == 1 || level > .5f
        val cells = 6
        val cell = side / cells
        for (i in 0 until cells * cells) {
            val hole = fancy && index == 1 && (i * 7) % 5 < 2
            drawRect(if (hole) Kit.ink else if (i % 3 == 0) grassDark else Color(0xFF4C9A33),
                Offset(cx - side / 2 + (i % cells) * cell, top + (i / cells) * cell), Size(cell, cell))
        }
        val chosen = (index == 0 && level < .5f) || (index == 1 && level >= .5f)
        if (chosen) drawRect(Kit.gold, Offset(cx - side / 2 - 6, top - 6), Size(side + 12, side + 12), style = Stroke(3.dp.toPx()))
    }
}

private fun DrawScope.drawFps(time: Float, level: Float) {
    val side = size.minDimension * .16f
    val from = size.width * .2f
    val to = size.width * .8f
    val bounce = if (time < .5f) time * 2 else 2 - time * 2
    val steps = (4 + level * 40).roundToInt()
    val choppy = (bounce * 4).roundToInt() / 4f
    val smooth = (bounce * steps).roundToInt() / steps.toFloat()
    drawRect(Kit.ember, Offset(from + (to - from) * choppy - side / 2, size.height * .3f - side / 2), Size(side, side))
    drawRect(Kit.grass, Offset(from + (to - from) * smooth - side / 2, size.height * .7f - side / 2), Size(side, side))
    drawLine(Kit.line, Offset(from, size.height * .5f), Offset(to, size.height * .5f), 2.dp.toPx())
}

private fun DrawScope.drawVsync(on: Boolean, time: Float) {
    drawRect(Color(0xFF5AA7E0), Offset.Zero, Size(size.width, size.height / 2))
    drawRect(Color(0xFF5C9A3A), Offset(0f, size.height / 2), Size(size.width, size.height / 2))
    val side = size.minDimension * .25f
    val x = size.width * (.3f + .4f * sin(time * 2 * PI).toFloat().let { (it + 1) / 2 })
    val tear = if (on) 0f else side * .45f
    drawRect(stone, Offset(x - side / 2, size.height / 2 - side), Size(side, side))
    drawRect(stone, Offset(x - side / 2 - tear, size.height / 2), Size(side, side))
    if (!on) drawLine(Kit.ember, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx())
}

private fun DrawScope.drawLight(level: Float) {
    drawRect(Color.Black)
    drawRect(Color(0xFF5C6A74).copy(alpha = .15f + level * .7f))
    val side = size.minDimension * .2f
    block(stone.copy(alpha = .3f + level * .7f), size.width * .25f, size.height * .65f, side)
    block(wood.copy(alpha = .3f + level * .7f), size.width * .72f, size.height * .55f, side)
    drawCircle(Kit.gold, size.minDimension * .05f, Offset(size.width * .5f, size.height * .3f))
}

private fun DrawScope.drawScale(level: Float) {
    val slots = 9
    val slot = size.width / 14f * (.6f + level * .7f)
    val left = (size.width - slot * slots) / 2
    val top = size.height * .6f
    for (i in 0 until slots) {
        drawRect(Color(0xFF8B8B8B), Offset(left + i * slot, top), Size(slot - 3, slot - 3))
        drawRect(Color(0xFF373737), Offset(left + i * slot, top), Size(slot - 3, slot - 3), style = Stroke(2.dp.toPx()))
    }
    repeat(10) { drawRect(Color(0xFFE2524C), Offset(left + it * slot * .45f, top - slot * .6f), Size(slot * .38f, slot * .38f)) }
}

private fun DrawScope.drawClouds(level: Float, time: Float) {
    drawRect(Color(0xFF6FB6E8))
    drawRect(Color(0xFF5C9A3A), Offset(0f, size.height * .78f), Size(size.width, size.height * .22f))
    if (level < .25f) return
    val shift = size.width * .15f * sin(time * 2 * PI).toFloat()
    val thick = level > .75f
    listOf(Triple(.1f, .2f, .35f), Triple(.55f, .35f, .3f)).forEach { (x, y, w) ->
        val left = size.width * x + shift
        val top = size.height * y
        val width = size.width * w
        val height = size.height * .08f
        if (thick) drawRect(Color(0xFFD9E4EA), Offset(left, top + height), Size(width, height * .6f))
        drawRect(Color.White, Offset(left, top), Size(width, height))
    }
}

private fun DrawScope.drawParticles(level: Float, time: Float) {
    val count = (3 + level * 22).roundToInt()
    val side = size.minDimension * .03f
    val base = Offset(size.width / 2, size.height * .8f)
    repeat(count) { i ->
        val phase = (time + i * .137f) % 1f
        val angle = (i * 47 % 360) * PI / 180
        val reach = size.minDimension * .55f * phase
        val x = base.x + (kotlin.math.cos(angle) * reach).toFloat()
        val y = base.y - abs(sin(angle).toFloat()) * reach - phase * size.height * .2f
        drawRect(if (i % 2 == 0) Kit.ember else Kit.muted, Offset(x, y), Size(side, side), alpha = 1f - phase)
    }
    block(stone, base.x, base.y + size.minDimension * .06f, size.minDimension * .14f)
}

private fun DrawScope.drawCreatures(level: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val radius = size.minDimension * (.12f + level * .36f)
    drawCircle(Kit.sky, radius, center, style = Stroke(3.dp.toPx()))
    block(skin, center.x, center.y, size.minDimension * .07f)
    listOf(-.25f to -.2f, .3f to -.15f, -.1f to .3f, .38f to .32f, -.42f to -.36f).forEach { (dx, dy) ->
        val position = Offset(center.x + size.width * dx, center.y + size.height * dy)
        val seen = (position - center).getDistance() <= radius
        block(Kit.grass.copy(alpha = if (seen) 1f else .2f), position.x, position.y, size.minDimension * .06f)
    }
}

private fun DrawScope.drawSound(level: Float, time: Float) {
    val center = Offset(size.width * .3f, size.height / 2)
    val side = size.minDimension * .2f
    drawRect(Kit.muted, Offset(center.x - side / 2, center.y - side / 4), Size(side / 2, side / 2))
    val cone = Path().apply {
        moveTo(center.x, center.y - side / 4); lineTo(center.x + side / 2, center.y - side / 2)
        lineTo(center.x + side / 2, center.y + side / 2); lineTo(center.x, center.y + side / 4); close()
    }
    drawPath(cone, Kit.muted)
    val waves = (level * 4).roundToInt()
    repeat(waves) { i ->
        val pulse = ((time + i * .25f) % 1f)
        drawArc(Kit.sky.copy(alpha = 1f - pulse * .6f), -40f, 80f, false,
            Offset(center.x + side * .2f - (i + 1) * side * .45f, center.y - (i + 1) * side * .45f - side * .2f),
            Size((i + 1) * side * .9f + side * .4f, (i + 1) * side * .9f + side * .4f), style = Stroke(4.dp.toPx()))
    }
    if (waves == 0) drawLine(Kit.ember, Offset(center.x + side * .7f, center.y - side / 2), Offset(center.x + side * 1.4f, center.y + side / 2), 5.dp.toPx())
}

private fun DrawScope.drawChat(level: Float, key: String) {
    drawRect(Color(0xFF5C9A3A))
    val scale = if (key == "chatScale") .6f + level * .8f else 1f
    val line = size.height * .09f * scale
    val ink = if (key == "chatOpacity") level else 1f
    val back = if (key == "textBackgroundOpacity") level else .5f
    val hidden = key == "chatVisibility" && level > .9f
    if (hidden) return
    listOf(.7f, .5f, .62f).forEachIndexed { i, width ->
        val top = size.height * .3f + i * (line + 6)
        drawRect(Color.Black.copy(alpha = back), Offset(size.width * .06f, top), Size(size.width * width, line))
        drawRect(Color.White.copy(alpha = ink), Offset(size.width * .08f, top + line * .3f), Size(size.width * (width - .06f), line * .4f))
    }
}

private fun DrawScope.drawWave(level: Float, time: Float) {
    val amplitude = size.height * .25f * level
    val path = Path()
    val steps = 40
    for (i in 0..steps) {
        val x = size.width * i / steps
        val y = size.height / 2 + amplitude * sin((i / steps.toFloat() * 2 + time) * 2 * PI).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, Kit.sky, style = Stroke(5.dp.toPx()))
}

private fun DrawScope.drawSwitch(on: Boolean) {
    val width = size.minDimension * .7f
    val height = width * .5f
    val left = (size.width - width) / 2
    val top = (size.height - height) / 2
    drawRoundRect(if (on) Kit.grass else Color(0xFF3A454F), Offset(left, top), Size(width, height),
        androidx.compose.ui.geometry.CornerRadius(height / 2))
    drawCircle(Kit.text, height * .4f, Offset(if (on) left + width - height / 2 else left + height / 2, top + height / 2))
}
