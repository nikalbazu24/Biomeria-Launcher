package com.biomeria.launcher.engine.java

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.theme.PixelFont
import com.biomeria.launcher.ui.theme.Biomeria
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The saved look, loaded once per process. */
@Composable
internal fun rememberCharacterLook(): CharacterLook {
    val context = LocalContext.current
    remember(context) { CharacterLooks.load(context.applicationContext) }
    val look by CharacterLooks.look.collectAsState()
    return look
}

/** The character drawn from its real skin, pixel by pixel, from the front or the back. */
@Composable
internal fun CharacterFigure(look: CharacterLook, bridge: DualScreenBridge?, back: Boolean, height: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val revision by CharacterLooks.customRevision.collectAsState()
    val figure by produceState<Bitmap?>(null, look, back, revision, bridge) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val account = if (look.source == SkinSource.ACCOUNT) CharacterLooks.accountSkin(context, bridge) else null
                val skin = CharacterLooks.skinFor(context, look) ?: account?.bitmap
                    ?: ItemIcons.current?.resource("minecraft", "textures/entity/player/wide/steve.png")
                        ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }?.let(::normalizeSkin)
                    ?: presetSkin(SkinSource.EXPLORER)
                val slim = if (look.source == SkinSource.ACCOUNT) account?.slim ?: false else look.slim
                renderFigure(skin, slim, back, look, capeFor(look.cape))
            }.getOrNull()
        }
    }
    Box(
        modifier.size(height / 2 + 24.dp, height + 10.dp).background(
            Brush.radialGradient(listOf(Color(0x73000000), Color.Transparent), radius = 140f)
        ),
        contentAlignment = Alignment.Center
    ) {
        figure?.let {
            Image(it.asImageBitmap(), contentDescription = "Tu personaje, " + if (back) "de espalda" else "de frente",
                modifier = Modifier.size(height / 2, height), contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None)
        }
    }
}

/** "Tu personaje" in the inventory: armour on the left, the figure in the middle, the off hand on the right. */
@Composable
internal fun CharacterCard(
    bridge: DualScreenBridge?,
    armor: @Composable ColumnScope.() -> Unit,
    offHand: @Composable () -> Unit,
    onCustomize: () -> Unit,
    modifier: Modifier = Modifier
) {
    val look = rememberCharacterLook()
    // Small here; tapping the figure opens it in its own window to look at and customise.
    Row(
        modifier.background(Biomeria.colors.ink, RoundedCornerShapeSmall)
            .border(2.dp, Color(0xFF232B33), RoundedCornerShapeSmall).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), content = armor)
        Column(Modifier.clip(RoundedCornerShapeSmall).clickable(onClickLabel = "Personalizar personaje", onClick = onCustomize)
            .background(Color(0xFF161B21)).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            CharacterFigure(look, bridge, false, 92.dp)
            Text("Tocar para ver", color = Biomeria.colors.textMuted, fontSize = TypeScale.detail, fontWeight = FontWeight.Bold)
        }
        offHand()
    }
}

private val RoundedCornerShapeSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)

/** Skin, arms, cape and visible layers. Changes reach the game a moment later. */
@Composable
internal fun CharacterEditor(
    bridge: DualScreenBridge?,
    onUpload: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = true,
    controllerActive: Boolean = false,
    onPadInput: ((((PanelPadAction) -> Unit)?) -> Unit)? = null
) {
    val context = LocalContext.current
    val look = rememberCharacterLook()
    val message by CharacterLooks.importMessage.collectAsState()
    var back by remember { mutableStateOf(false) }
    fun change(next: CharacterLook) = CharacterLooks.update(context.applicationContext, next)
    val accountSkin = look.source == SkinSource.ACCOUNT
    val presets = listOf(SkinSource.EXPLORER, SkinSource.MINER, SkinSource.FOREST, SkinSource.NETHER)

    // Every control in reading order, so the lower screen's gamepad can walk through them.
    val rows: List<List<Pair<String, () -> Unit>>> = listOf(
        presets.map { preset -> preset.name to { change(look.copy(source = preset)) } },
        listOf("upload" to onUpload, "account" to { change(look.copy(source = SkinSource.ACCOUNT)) }),
        listOf("classic" to { if (!accountSkin) change(look.copy(slim = false)) },
            "slim" to { if (!accountSkin) change(look.copy(slim = true)) }),
        CapeChoice.entries.map { cape -> "cape-" + cape.name to { change(look.copy(cape = cape)) } },
        listOf("hat" to { change(look.copy(hat = !look.hat)) }),
        listOf("jacket" to { change(look.copy(jacket = !look.jacket)) }),
        listOf("sleeves" to { change(look.copy(sleeves = !look.sleeves)) }),
        listOf("pants" to { change(look.copy(pants = !look.pants)) }),
        listOfNotNull(("turn" to { back = !back }).takeIf { wide }, "done" to onDone)
    )
    var row by remember { mutableStateOf(0) }
    var column by remember { mutableStateOf(0) }
    val focused = if (controllerActive) rows.getOrNull(row)?.getOrNull(column)?.first else null
    val input by rememberUpdatedState<(PanelPadAction) -> Unit>({ action ->
        when (action) {
            PanelPadAction.UP -> { row = (row - 1).coerceAtLeast(0); column = column.coerceAtMost(rows[row].lastIndex) }
            PanelPadAction.DOWN -> { row = (row + 1).coerceAtMost(rows.lastIndex); column = column.coerceAtMost(rows[row].lastIndex) }
            PanelPadAction.LEFT -> column = (column - 1).coerceAtLeast(0)
            PanelPadAction.RIGHT -> column = (column + 1).coerceAtMost(rows[row].lastIndex)
            PanelPadAction.PICK -> rows.getOrNull(row)?.getOrNull(column)?.second?.invoke()
            PanelPadAction.BACK, PanelPadAction.MARK -> onDone()
            else -> Unit
        }
    })
    val register by rememberUpdatedState(onPadInput)
    DisposableEffect(Unit) {
        register?.invoke { input(it) }
        onDispose { register?.invoke(null) }
    }
    fun Modifier.pad(id: String) = if (focused == id) border(3.dp, Biomeria.colors.gold, RoundedCornerShapeSmall) else this

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (wide) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CharacterFigure(look, bridge, back, 200.dp)
                LauncherButton(if (back) "Ver de frente" else "Ver de espalda", { back = !back }, Modifier.pad("turn"))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Personalizar", fontFamily = PixelFont, fontSize = TypeScale.title, modifier = Modifier.weight(1f))
                LauncherButton("Listo", onDone, Modifier.pad("done"), primary = true)
            }
            EditorSection("Skin") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        PresetTile(preset, look.source == preset, Modifier.weight(1f).pad(preset.name)) {
                            change(look.copy(source = preset))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionBlock(if (CharacterLooks.customFile(context).isFile) "Mi imagen · cambiar" else "Subir imagen (PNG 64×64)",
                        look.source == SkinSource.CUSTOM, Modifier.weight(1f).pad("upload"), onClick = onUpload)
                    OptionBlock("Usar la de mi cuenta", accountSkin, Modifier.weight(1f).pad("account")) {
                        change(look.copy(source = SkinSource.ACCOUNT))
                    }
                }
                message?.let { Text(it, color = Color(0xFFFFB4A6), style = PanelText.detail) }
            }
            EditorSection("Brazos") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionBlock("Clásicos", !look.slim, Modifier.pad("classic"), enabled = !accountSkin) { change(look.copy(slim = false)) }
                    OptionBlock("Delgados", look.slim, Modifier.pad("slim"), enabled = !accountSkin) { change(look.copy(slim = true)) }
                }
                if (accountSkin) Note("Con la skin de tu cuenta se usan los brazos que ella trae.")
            }
            EditorSection("Capa") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapeChoice.entries.forEach { cape ->
                        OptionBlock(cape.label, look.cape == cape, Modifier.pad("cape-" + cape.name)) { change(look.copy(cape = cape)) }
                    }
                }
            }
            EditorSection("Partes visibles") {
                LayerSwitch("Sombrero", look.hat, Modifier.pad("hat")) { change(look.copy(hat = it)) }
                LayerSwitch("Chaqueta", look.jacket, Modifier.pad("jacket")) { change(look.copy(jacket = it)) }
                LayerSwitch("Mangas", look.sleeves, Modifier.pad("sleeves")) { change(look.copy(sleeves = it)) }
                LayerSwitch("Pantalón", look.pants, Modifier.pad("pants")) { change(look.copy(pants = it)) }
            }
            Note("La skin y la capa elegidas aquí solo se ven en este equipo. Las partes visibles sí las ven los demás. " +
                "Para que todos vean tu skin, cámbiala en tu cuenta de Microsoft o Ely.by.")
        }
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = PanelText.section, fontWeight = FontWeight.ExtraBold)
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Biomeria.colors.textMuted, style = PanelText.detail)
}

@Composable
private fun OptionBlock(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    BlockButton(onClick, modifier, if (selected) BlockButtons.primary else BlockButtons.neutral, enabled) {
        Text((if (selected) "✓ " else "") + label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.ExtraBold, style = PanelText.body)
    }
}

@Composable
private fun PresetTile(source: SkinSource, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (hair, skin, shirt, _) = presetColors(source).map { Color(it) }
    BlockButton(onClick, modifier, if (selected) BlockButtons.gold else BlockButtons.neutral,
        contentPadding = PaddingValues(6.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.size(40.dp).border(2.dp, Biomeria.colors.ink)) {
                val eye = Color(0xFF2B2B33)
                listOf(listOf(hair, hair, hair, hair), listOf(skin, eye, skin, eye), listOf(skin, skin, skin, skin),
                    listOf(shirt, shirt, shirt, shirt)).forEach { row ->
                    Row(Modifier.weight(1f)) { row.forEach { Box(Modifier.weight(1f).fillMaxHeight().background(it)) } }
                }
            }
            Text(source.label, fontWeight = FontWeight.ExtraBold, fontSize = TypeScale.detail, maxLines = 1)
        }
    }
}

@Composable
private fun LayerSwitch(label: String, on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = PanelText.body, fontWeight = FontWeight.ExtraBold)
        Switch(on, onChange)
    }
}
