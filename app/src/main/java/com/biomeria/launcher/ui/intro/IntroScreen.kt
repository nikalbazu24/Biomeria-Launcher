package com.biomeria.launcher.ui.intro

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.R
import com.biomeria.launcher.data.InstanceStorage
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.components.BiomeriaMark
import com.biomeria.launcher.ui.theme.PixelFont
import java.io.File

/** What the player picked as their first step; the hub carries it out after the intro. */
internal enum class IntroStart { CREATE_JAVA, OPEN_BEDROCK, EXPLORE }

internal data class IntroResult(val start: IntroStart, val signIn: Boolean)

internal object IntroPreferences {
    private const val PREFS = "biomeria_intro"
    fun done(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("done", false)
    fun markDone(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("done", true).apply()
    fun reset(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("done", false).apply()
}

private val Ink = Color(0xFF0B0E11)
private val Paper = Color(0xFFF2F5EF)
private val Muted = Color(0xFFC3CDD2)
private val Green = Color(0xFF7BC74D)
private val Gold = Color(0xFFF5E663)

private data class Biome(val name: String, val engine: String, val color: Color, val text: String, val image: Int)

private val biomes = listOf(
    Biome("Bosque", "Java", Green, "Minecraft Java con mods de Fabric o NeoForge, mapa con marcas y pantalla dual.", R.drawable.biomeria_valley),
    Biome("Océano", "Bedrock", Color(0xFF4FB3D9),
        "Abre la app oficial de Minecraft que ya tienes instalada para jugar con amigos de otras plataformas.", R.drawable.biome_ocean),
    Biome("Nether", "Starling", Color(0xFFF07D4A), "Un motor nativo hecho para consolas Android, liviano y pensado para correr fluido.",
        R.drawable.biome_nether)
)

private val accounts = listOf(
    Triple("Microsoft", "Tu cuenta oficial de Minecraft. La necesitas para servidores en línea.", "Microsoft"),
    Triple("Ely.by", "Cuenta alternativa con skins propias.", "Ely.by"),
    Triple("Sin conexión", "Juega solo o en tu red local. Puedes conectar una cuenta después.", "Offline")
)

private data class Head(val label: String, val hair: Color, val skin: Color, val eye: Color, val mouth: Color) {
    /** 8×8 face, row by row, as in the sketch. */
    fun cells(): List<Color> {
        val w = Paper
        return listOf(
            List(8) { hair }, List(8) { hair },
            listOf(hair) + List(6) { skin } + hair,
            List(8) { skin },
            listOf(skin, w, eye, skin, skin, eye, w, skin),
            listOf(skin, skin, skin, mouth, mouth, skin, skin, skin),
            listOf(skin, skin, mouth, mouth, mouth, mouth, skin, skin),
            List(8) { skin }
        ).flatten()
    }
}

private val heads = listOf(
    Head("Pelo castaño", Color(0xFF3A2A1E), Color(0xFFC68E6A), Color(0xFF3B5BA8), Color(0xFF8C5A3C)),
    Head("Pelo rubio", Color(0xFFE3C25B), Color(0xFFF0C8A0), Color(0xFF4C9A3F), Color(0xFFB97A5A)),
    Head("Pelo negro", Color(0xFF1B1B1F), Color(0xFF8D5A3B), Color(0xFF6B3E26), Color(0xFF5E3A24)),
    Head("Pelo rojo", Color(0xFFB04A2A), Color(0xFFE8B48E), Color(0xFF2E8FBF), Color(0xFFA8694A))
)

private val starts = listOf(
    Triple("Crear un mundo Java", "Con Fabric y mods de rendimiento recomendados.", Green),
    Triple("Abrir Minecraft Bedrock", "Usa la app oficial que ya tienes instalada.", Color(0xFF4FB3D9)),
    Triple("Explorar por mi cuenta", "Ir directo a Mis mundos.", Gold)
)
private val startLabels = listOf("Crear mi primer mundo", "Abrir Bedrock", "Entrar a Biomeria")
private val stepNames = listOf("Bienvenida", "Biomas", "Cuenta", "Perfil", "Mando y pantallas", "Primer paso")
private val stepColors = listOf(Green, Color(0xFF4FB3D9), Gold, Color(0xFFE0B48C), Color(0xFFF07D4A), Color(0xFFA6E07E))
private val optionCount = mapOf(1 to 3, 2 to 3, 3 to 4, 4 to 2, 5 to 3)

private fun validName(value: String) = value.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }.take(16)

/**
 * First-run introduction: welcome, the three biomes, account, profile, controller and screens,
 * and the first step. A continues, B goes back, Y skips, the D-pad moves between options.
 */
@Composable
internal fun IntroScreen(onFinished: (IntroResult) -> Unit) {
    val context = LocalContext.current
    val storage = remember(context) { InstanceStorage(context) }
    val saved = remember(storage) { storage.loadProfile() }
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    var step by rememberSaveable { mutableStateOf(0) }
    var done by rememberSaveable { mutableStateOf(false) }
    var biome by rememberSaveable { mutableStateOf(0) }
    var account by rememberSaveable {
        mutableStateOf(accounts.indexOfFirst { it.third.equals(saved.accountType, true) }.takeIf { it >= 0 } ?: 2)
    }
    var head by rememberSaveable { mutableStateOf(0) }
    var dual by rememberSaveable { mutableStateOf(0) }
    var start by rememberSaveable { mutableStateOf(0) }
    var name by rememberSaveable { mutableStateOf(validName(saved.playerName).ifBlank { "Jugador" }) }

    fun finish() {
        val chosen = accounts[account].third
        // A photo the player already chose stays; otherwise the pixel head becomes the profile picture.
        val avatar = if (saved.avatar != null) null else runCatching { headPhoto(context, heads[head]) }.getOrNull()
        runCatching {
            storage.saveProfile(saved.copy(playerName = name.ifBlank { "Jugador" }, accountType = chosen), avatar)
        }
        context.getSharedPreferences("biomeria_java", Context.MODE_PRIVATE).edit()
            .putBoolean("dual-screen-default", dual == 0).apply()
        IntroPreferences.markDone(context)
        onFinished(IntroResult(IntroStart.entries[start], signIn = chosen != "Offline"))
    }
    fun skip() {
        IntroPreferences.markDone(context)
        onFinished(IntroResult(IntroStart.EXPLORE, signIn = false))
    }
    fun next() = when {
        step < 5 -> step++
        !done -> done = true
        else -> finish()
    }
    fun back() {
        if (done) done = false else if (step > 0) step--
    }
    fun move(delta: Int) {
        val count = optionCount[step] ?: return
        if (done) return
        val wrap = { value: Int -> (value + delta + count) % count }
        when (step) {
            1 -> biome = wrap(biome)
            2 -> account = wrap(account)
            3 -> head = wrap(head)
            4 -> dual = wrap(dual)
            5 -> start = wrap(start)
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val background = when {
        step == 1 -> biome
        step == 5 && !done -> listOf(0, 1, 0)[start]
        else -> 0
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Ink).focusRequester(focus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ButtonA, Key.Enter, Key.NumPadEnter -> { next(); true }
                    Key.ButtonB, Key.Escape -> { back(); step > 0 || done }
                    Key.ButtonY -> { skip(); true }
                    Key.DirectionUp, Key.DirectionLeft -> { move(-1); optionCount.containsKey(step) }
                    Key.DirectionDown, Key.DirectionRight -> { move(1); optionCount.containsKey(step) }
                    else -> false
                }
            }
    ) {
        val narrow = maxWidth < 600.dp
        BiomeBackground(background, if (step == 0) .58f else .45f, reduceMotion)
        if (!reduceMotion) RisingBlocks()
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = if (narrow) 18.dp else 36.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BiomeriaMark(Modifier.size(28.dp, 31.dp))
                Text("Biomeria", fontFamily = PixelFont, fontSize = 22.sp, color = Paper)
                if (!narrow) Text("Primera vez", Modifier.background(Color(0x33F5E663), RoundedCornerShape(3.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp), color = Gold, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                BlockButton(::skip, colors = BlockButtons.neutral, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    KeyBadge("Y", Gold); Spacer(Modifier.width(8.dp))
                    Text("Saltar introducción", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(step * 2 + if (done) 1 else 0, transitionSpec = {
                    if (reduceMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 10 }) togetherWith fadeOut(tween(160))
                }, label = "paso") { key ->
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                        when (key / 2) {
                            0 -> Welcome(reduceMotion)
                            1 -> BiomesStep(biome, narrow) { biome = it }
                            2 -> AccountStep(account) { account = it }
                            3 -> ProfileStep(name, { name = validName(it) }, head) { head = it }
                            4 -> ControllerStep(dual, narrow) { dual = it }
                            else -> if (key % 2 == 1) DoneStep(name, heads[head], accounts[account].first, dual == 0, reduceMotion)
                                else StartStep(start) { start = it }
                        }
                    }
                }
            }
            Footer(step, done, narrow, onBack = ::back, onNext = ::next,
                nextLabel = when {
                    step == 0 -> "Comenzar"
                    step < 5 -> "Continuar"
                    !done -> "Terminar"
                    else -> startLabels[start]
                })
        }
    }
}

@Composable
private fun BiomeBackground(index: Int, dim: Float, still: Boolean) {
    val pan = rememberInfiniteTransition(label = "fondo")
    val shift by pan.animateFloat(0f, if (still) 0f else -1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse),
        label = "paneo")
    Crossfade(index, animationSpec = tween(if (still) 0 else 700), label = "bioma") { shown ->
        Image(painterResource(biomes[shown].image), null, Modifier.fillMaxSize().graphicsLayer {
            scaleX = 1.08f; scaleY = 1.08f; translationX = shift * size.width * .03f
        }, contentScale = ContentScale.Crop)
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
        Color.Black.copy(alpha = dim * .8f), Color.Black.copy(alpha = dim), Color.Black.copy(alpha = (dim + .3f).coerceAtMost(.95f))))))
}

/** Little blocks drifting up, like in the sketch. */
@Composable
private fun RisingBlocks() {
    val rise = rememberInfiniteTransition(label = "bloques")
    val colors = listOf(Green, Color(0xFF4FB3D9), Color(0xFFF07D4A), Gold, Green)
    Box(Modifier.fillMaxSize()) {
        colors.forEachIndexed { index, color ->
            val t by rise.animateFloat(0f, 1f, infiniteRepeatable(tween(7000 + index * 1300, delayMillis = index * 900, easing = LinearEasing)),
                label = "b$index")
            Box(Modifier.fillMaxSize().drawBehind {
                val x = size.width * (.12f + index * .19f)
                val y = size.height * (1.02f - t * .45f)
                val alpha = when {
                    t < .12f -> t / .12f * .8f
                    else -> (1f - t) * .9f
                }
                val side = 12.dp.toPx()
                drawRect(Ink.copy(alpha = alpha), Offset(x - 2, y - 2), Size(side + 4, side + 4))
                drawRect(color.copy(alpha = alpha), Offset(x, y), Size(side, side))
            })
        }
    }
}

@Composable
private fun Welcome(still: Boolean) {
    val float = rememberInfiniteTransition(label = "flotar")
    val y by float.animateFloat(0f, if (still) 0f else -10f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "y")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BiomeriaMark(Modifier.size(120.dp, 132.dp).graphicsLayer { translationY = y.dp.toPx() })
        Title("¡Bienvenido a Biomeria!", center = true)
        Body("Tu launcher para jugar Minecraft en Android: tres formas de jugar en un solo lugar.", center = true)
        Text("Te tomará un minuto. Puedes saltarla cuando quieras.", color = Muted, fontSize = 15.sp)
    }
}

@Composable
private fun BiomesStep(selected: Int, narrow: Boolean, onPick: (Int) -> Unit) {
    Title("Tres biomas, tres formas de jugar")
    Body("Cada mundo de tu biblioteca vive en uno de estos biomas. Muévete con la cruceta para conocerlos.")
    Spacer(Modifier.height(18.dp))
    val cards: @Composable (Modifier) -> Unit = { modifier ->
        biomes.forEachIndexed { index, item ->
            val on = index == selected
            val lift by animateDpAsState(if (on) (-8).dp else 0.dp, label = "subir")
            Column(modifier.offset(y = lift).optionSemantics(on, "${item.name}, motor ${item.engine}") { onPick(index) }
                .background(Ink.copy(alpha = if (on) .88f else .7f), RoundedCornerShape(6.dp))
                .border(3.dp, if (on) item.color else Color.White.copy(alpha = .12f), RoundedCornerShape(6.dp))
                .padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(22.dp).background(item.color).border(3.dp, Ink))
                    Text(item.name, fontFamily = PixelFont, fontSize = 24.sp, color = Paper)
                }
                Text("Motor ${item.engine}", color = item.color, fontWeight = FontWeight.ExtraBold)
                Text(item.text, color = Muted, fontSize = 15.sp)
            }
        }
    }
    if (narrow) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { cards(Modifier.fillMaxWidth()) }
    else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { cards(Modifier.weight(1f).heightIn(min = 190.dp)) }
}

@Composable
private fun AccountStep(selected: Int, onPick: (Int) -> Unit) {
    Title("¿Con qué cuenta juegas Java?")
    Body("Bedrock usa la cuenta de la app oficial. Podrás cambiar esto en Ajustes.")
    Spacer(Modifier.height(16.dp))
    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        accounts.forEachIndexed { index, (label, note) -> OptionRow(label, note, index == selected, Green) { onPick(index) } }
    }
}

@Composable
private fun ProfileStep(name: String, onName: (String) -> Unit, selected: Int, onPick: (Int) -> Unit) {
    Title("Tu perfil")
    Body("Así te verán en el launcher. Sin conexión, este nombre también es el de tu jugador.")
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(name, onName, Modifier.widthIn(max = 420.dp).fillMaxWidth(), singleLine = true,
        label = { Text("Nombre") }, supportingText = { Text("Hasta 16 letras, números o _", color = Muted) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        textStyle = MaterialTheme.typography.titleMedium.copy(color = Paper, fontWeight = FontWeight.ExtraBold),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold, unfocusedBorderColor = Color.White.copy(alpha = .3f),
            focusedContainerColor = Ink.copy(alpha = .8f), unfocusedContainerColor = Ink.copy(alpha = .8f),
            focusedLabelColor = Gold, unfocusedLabelColor = Muted, cursorColor = Gold))
    Spacer(Modifier.height(12.dp))
    Text("Aspecto", color = Paper, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        heads.forEachIndexed { index, item ->
            val on = index == selected
            val lift by animateDpAsState(if (on) (-6).dp else 0.dp, label = "subir")
            Box(Modifier.offset(y = lift).optionSemantics(on, item.label) { onPick(index) }
                .background(Ink.copy(alpha = .8f), RoundedCornerShape(5.dp))
                .border(3.dp, if (on) Gold else Color.White.copy(alpha = .12f), RoundedCornerShape(5.dp)).padding(10.dp)) {
                PixelHead(item, 64.dp)
            }
        }
    }
}

@Composable
private fun ControllerStep(selected: Int, narrow: Boolean, onPick: (Int) -> Unit) {
    val content: @Composable (Modifier, Modifier) -> Unit = { left, right ->
        Column(left, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("Tu mando")
            listOf(listOf("A") to "Jugar y elegir", listOf("B") to "Volver", listOf("X") to "Abrir el Taller",
                listOf("Y") to "Marcar favorito", listOf("LB", "RB") to "Cambiar de sección").forEach { (keys, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    keys.forEach { KeyBadge(it, keyColor(it)) }
                    Text(label, color = Paper, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
        }
        Column(right, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("Tus pantallas")
            Body("En consolas con dos pantallas, como la AYN Thor, Biomeria puede mostrar el mapa y las recetas abajo mientras juegas Java.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Sí, usar la segunda pantalla", "No por ahora").forEachIndexed { index, label ->
                    BlockButton({ onPick(index) }, Modifier.optionSemantics(index == selected, label) { onPick(index) },
                        if (index == selected) BlockButtons.primary else BlockButtons.neutral) {
                        Text(label, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
    if (narrow) Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { content(Modifier, Modifier) }
    else Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) { content(Modifier.weight(1f), Modifier.weight(1.2f)) }
}

@Composable
private fun StartStep(selected: Int, onPick: (Int) -> Unit) {
    Title("¿Por dónde empiezas?")
    Body("Elige una opción para tu primera partida.")
    Spacer(Modifier.height(16.dp))
    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        starts.forEachIndexed { index, (label, note, color) -> OptionRow(label, note, index == selected, color) { onPick(index) } }
    }
}

@Composable
private fun DoneStep(name: String, head: Head, account: String, dual: Boolean, still: Boolean) {
    val pop = remember { Animatable(if (still) 1f else .6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = .45f, stiffness = 300f)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }.border(4.dp, Ink)) { PixelHead(head, 96.dp) }
        Title("¡Todo listo, ${name.ifBlank { "Jugador" }}!", center = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Cuenta: $account")
            Chip("Pantalla dual: " + if (dual) "activada" else "desactivada")
        }
        if (account != "Sin conexión") Text("Al entrar te pediremos iniciar sesión con $account.", color = Muted, fontSize = 15.sp)
    }
}

@Composable
private fun Footer(step: Int, done: Boolean, narrow: Boolean, onBack: () -> Unit, onNext: () -> Unit, nextLabel: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        BlockButton(onBack, Modifier.graphicsLayer { alpha = if (step == 0 && !done) .45f else 1f }, BlockButtons.neutral,
            enabled = step > 0 || done) {
            KeyBadge("B", Color(0xFFE5484D)); Spacer(Modifier.width(8.dp)); Text("Atrás", fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.weight(1f))
        // A hotbar with one slot per step, as in the sketch.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { contentDescription = "Paso ${step + 1} de 6: ${if (done) "Listo" else stepNames[step]}" }) {
            stepNames.indices.forEach { index ->
                val current = index == step && !done
                val past = index < step || done
                Box(Modifier.size(if (narrow) 30.dp else 42.dp).background(Color(0xA63C4248))
                    .border(if (current) 4.dp else 2.dp, if (current) Paper else Color(0xFF1A2026)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (narrow) 12.dp else 16.dp).background(if (past || current) stepColors[index] else Color(0xFF2A333B)))
                }
            }
        }
        if (!narrow) Text(if (done) "¡Listo!" else "Paso ${step + 1} · ${stepNames[step]}", color = Paper, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        BlockButton(onNext, colors = BlockButtons.primary) {
            KeyBadge("A", Green, dark = true); Spacer(Modifier.width(8.dp)); Text(nextLabel, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun OptionRow(label: String, note: String, on: Boolean, color: Color, onClick: () -> Unit) {
    val shift by animateDpAsState(if (on) 8.dp else 0.dp, label = "mover")
    Row(Modifier.fillMaxWidth().offset(x = shift).optionSemantics(on, label, onClick)
        .background(Ink.copy(alpha = if (on) .9f else .72f), RoundedCornerShape(5.dp))
        .border(3.dp, if (on) color else Color.White.copy(alpha = .12f), RoundedCornerShape(5.dp))
        .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(20.dp).border(3.dp, if (on) color else Color(0xFF5A6670)).padding(3.dp)
            .background(if (on) color else Color.Transparent))
        Column {
            Text(label, color = Paper, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
            Text(note, color = Muted, fontSize = 14.sp)
        }
    }
}

private fun Modifier.optionSemantics(selected: Boolean, label: String, onClick: () -> Unit) =
    clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected; contentDescription = label }

@Composable
private fun PixelHead(head: Head, size: androidx.compose.ui.unit.Dp) {
    val cells = remember(head) { head.cells() }
    Box(Modifier.size(size).drawBehind {
        val cell = this.size.width / 8
        cells.forEachIndexed { index, color ->
            drawRect(color, Offset((index % 8) * cell, (index / 8) * cell), Size(cell + .5f, cell + .5f))
        }
    })
}

@Composable
private fun Title(text: String, center: Boolean = false) {
    Text(text, color = Paper, fontFamily = PixelFont, fontSize = 34.sp, lineHeight = 38.sp,
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null,
        modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Body(text: String, center: Boolean = false) {
    Text(text, color = Muted, fontSize = 18.sp, textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null,
        modifier = Modifier.widthIn(max = 720.dp))
}

@Composable
private fun Chip(text: String) {
    Text(text, Modifier.background(Ink.copy(alpha = .85f), RoundedCornerShape(4.dp)).border(2.dp, Color.White.copy(alpha = .14f),
        RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 6.dp), color = Paper, fontWeight = FontWeight.ExtraBold)
}

private fun keyColor(key: String) = when (key) {
    "A" -> Green
    "B" -> Color(0xFFE5484D)
    "X" -> Color(0xFF4FB3D9)
    "Y" -> Gold
    else -> Color(0xFF8A96A0)
}

@Composable
private fun KeyBadge(key: String, color: Color, dark: Boolean = false) {
    Box(Modifier.defaultMinSize(26.dp, 26.dp).background(if (dark) Ink else color, RoundedCornerShape(13.dp)).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center) {
        Text(key, color = if (dark) color else Ink, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

/** Draws the chosen head as the profile photo, so the hub shows it next to the name. */
private fun headPhoto(context: Context, head: Head): Uri {
    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint()
    head.cells().forEachIndexed { index, color ->
        paint.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(),
            (color.blue * 255).toInt())
        val x = (index % 8) * 32f
        val y = (index / 8) * 32f
        canvas.drawRect(x, y, x + 32f, y + 32f, paint)
    }
    val file = File(context.cacheDir, "intro-head.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return Uri.fromFile(file)
}
