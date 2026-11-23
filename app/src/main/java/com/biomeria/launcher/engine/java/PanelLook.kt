package com.biomeria.launcher.engine.java

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.biomeria.launcher.ui.theme.PixelFont

/**
 * Colours of the lower-screen panels. CLASSIC is Minecraft's own grey inventory with its light and
 * dark bevels; DARK is Biomeria's night look. High contrast darkens (or lightens) the text further.
 */
internal data class PanelLook(
    val classic: Boolean,
    val background: Color,
    val frame: Color,
    val frameLight: Color,
    val frameDark: Color,
    val slot: Color,
    val slotShadow: Color,
    val slotLight: Color,
    val card: Color,
    val cardShadow: Color,
    val cardLight: Color,
    val button: Color,
    val ink: Color,
    val muted: Color,
    val good: Color,
    val bad: Color
)

internal fun panelLook(style: PanelStyle, highContrast: Boolean): PanelLook = if (style == PanelStyle.CLASSIC) PanelLook(
    classic = true,
    background = Color(0xFF2B2B2B),
    frame = Color(0xFFC6C6C6), frameLight = Color.White, frameDark = Color(0xFF555555),
    slot = Color(0xFF8B8B8B), slotShadow = Color(0xFF373737), slotLight = Color.White,
    card = Color(0xFFB4B4B4), cardShadow = Color(0xFF8B8B8B), cardLight = Color(0xFFE0E0E0),
    button = Color(0xFFDADADA),
    ink = if (highContrast) Color(0xFF1E1E1E) else Color(0xFF3F3F3F),
    muted = if (highContrast) Color(0xFF333333) else Color(0xFF555555),
    good = Color(0xFF225A0E), bad = Color(0xFF9A1F16)
) else PanelLook(
    classic = false,
    background = Color(0xFF101418),
    frame = Color(0xFF161B21), frameLight = Color(0xFF2E3740), frameDark = Color(0xFF0B0E11),
    slot = Color(0xFF1A2026), slotShadow = Color(0xFF0B0E11), slotLight = Color(0xFF3A454F),
    card = Color(0xFF1E252C), cardShadow = Color(0xFF14191E), cardLight = Color(0xFF2A333B),
    button = Color(0xFF2A333B),
    ink = Color(0xFFF2F5EF),
    muted = if (highContrast) Color(0xFFD3DCE0) else Color(0xFFA9B6BD),
    good = Color(0xFFA6E07E), bad = Color(0xFFFF8A6B)
)

internal val LocalPanelLook = staticCompositionLocalOf { panelLook(PanelStyle.CLASSIC, true) }

/** Minecraft's relief: one colour on the top and left edges, another on the bottom and right. */
internal fun Modifier.bevel(width: Dp, topLeft: Color, bottomRight: Color) = drawBehind {
    val w = width.toPx()
    drawRect(topLeft, Offset.Zero, Size(size.width, w))
    drawRect(topLeft, Offset.Zero, Size(w, size.height))
    drawRect(bottomRight, Offset(0f, size.height - w), Size(size.width, w))
    drawRect(bottomRight, Offset(size.width - w, 0f), Size(w, size.height))
}

/** The big framed window: black outline, panel face and a raised bevel. */
@Composable
internal fun Modifier.panelFrame(): Modifier {
    val look = LocalPanelLook.current
    return this.border(3.dp, Color.Black, RoundedCornerShape(6.dp))
        .padding(3.dp)
        .background(look.frame, RoundedCornerShape(3.dp))
        .bevel(4.dp, look.frameLight, look.frameDark)
}

/** A raised card inside a frame, sunk one step like Minecraft's sub-panels. */
@Composable
internal fun Modifier.panelCard(): Modifier {
    val look = LocalPanelLook.current
    return this.background(look.card, RoundedCornerShape(3.dp)).bevel(3.dp, look.cardShadow, look.cardLight)
}

/** A plain button in the panel's look: raised face, text in the panel's ink. */
@Composable
internal fun PanelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    face: Color? = null,
    content: @Composable RowScope.() -> Unit
) {
    val look = LocalPanelLook.current
    val color = face ?: look.button
    Row(
        modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else .45f)
            .background(color, RoundedCornerShape(3.dp))
            .bevel(3.dp, if (look.classic && face == null) Color.White else Color(0x40FFFFFF), Color(0x66000000))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

/** A controller button drawn as a small coloured key. */
@Composable
internal fun PadKey(key: String, modifier: Modifier = Modifier) {
    val (back, front) = when (key) {
        "A" -> Color(0xFF7BC74D) to Color(0xFF0E1A08)
        "B" -> Color(0xFFF07D4A) to Color(0xFF2B0E05)
        "X" -> Color(0xFF4FB3D9) to Color(0xFF08202B)
        "Y" -> Color(0xFFF5E663) to Color(0xFF2A2508)
        else -> Color(0xFF34404A) to Color(0xFFF2F5EF)
    }
    Box(modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).background(back, RoundedCornerShape(11.dp))
        .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(key, color = front, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * One inventory slot drawn like Minecraft's: sunk grey square, the game's own icon and the count
 * in the corner. A finger drag lifts the stack and drops it on another slot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun McSlot(
    number: Int,
    live: PanelInventory?,
    size: Dp,
    enabled: Boolean,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    chosen: Boolean = false,
    inHand: Boolean = false,
    focused: Boolean = false,
    dim: Boolean = false,
    ghostId: String? = null,
    placeholder: String? = null
) {
    val look = LocalPanelLook.current
    val item = live?.slots?.getOrNull(number)
    val shown = item?.takeIf { it.count > 0 }
    val icon = shown?.let { rememberItemIcon(it.id) }
    val ghost = if (shown == null && !ghostId.isNullOrBlank()) rememberItemIcon(ghostId) else null
    val drag = LocalSlotDrag.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) bringIntoView.bringIntoView() }
    val description = when {
        shown == null -> placeholder ?: "Casilla vacía"
        shown.count > 1 -> "${shown.name}, ${shown.count}"
        else -> shown.name
    }
    val ring = when {
        focused -> Color(0xFFF5E663)
        chosen -> Color(0xFFF5E663)
        inHand -> Color.White
        else -> null
    }
    Box(
        modifier.size(size)
            .bringIntoViewRequester(bringIntoView)
            .onGloballyPositioned { drag?.bounds?.set(number, it.boundsInRoot()); origin = it.positionInRoot() }
            .alpha(if (dim) .28f else 1f)
            .then(if (ring != null) Modifier.border(if (focused) 4.dp else 3.dp, ring, RoundedCornerShape(2.dp)) else Modifier)
            .padding(if (ring != null) 3.dp else 0.dp)
            .background(look.slot)
            .bevel(if (size > 56.dp) 4.dp else 3.dp, look.slotShadow, look.slotLight)
            .clickable(enabled = enabled && !dim, role = Role.Button) { onTap(number) }
            .pointerInput(enabled, dim, shown?.count, live?.generation, live?.held?.count) {
                val canLift = drag != null && enabled && !dim && shown != null && (live?.held?.count ?: 0) == 0
                if (!canLift || drag == null) return@pointerInput
                detectDragGestures(
                    onDragStart = { start ->
                        drag.from = number
                        drag.icon = icon
                        drag.finger = origin + start
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.finger = (drag.finger ?: origin) + amount
                    },
                    onDragEnd = {
                        val target = drag.finger?.let(drag::slotAt)
                        if (target != null && target != number) drag.onMove(number, target)
                        drag.from = null; drag.finger = null; drag.icon = null
                    },
                    onDragCancel = { drag.from = null; drag.finger = null; drag.icon = null }
                )
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        when {
            icon != null -> Image(icon, null, Modifier.fillMaxSize(.72f), filterQuality = FilterQuality.None)
            shown != null -> Text(shown.name, Modifier.padding(3.dp), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            ghost != null -> Image(ghost, null, Modifier.fillMaxSize(.66f).alpha(.4f), filterQuality = FilterQuality.None)
        }
        if (shown != null && shown.count > 1) Text(shown.count.toString(),
            Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 1.dp),
            color = Color.White, fontFamily = PixelFont, fontSize = (size.value * .2f).coerceIn(12f, 24f).sp,
            style = TextStyle(shadow = Shadow(Color(0xFF3F3F3F), Offset(3f, 3f))))
    }
}

/** An item icon in a small sunk square, for recipes and the action card. */
@Composable
internal fun McIcon(id: String, size: Dp, modifier: Modifier = Modifier, count: Int = 0, faded: Boolean = false) {
    val look = LocalPanelLook.current
    val icon = if (id.isBlank()) null else rememberItemIcon(id)
    Box(modifier.size(size).background(look.slot).bevel(3.dp, look.slotShadow, look.slotLight), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize(.72f).alpha(if (faded) .45f else 1f), filterQuality = FilterQuality.None)
        if (count > 1) Text(count.toString(), Modifier.align(Alignment.BottomEnd).padding(end = 4.dp),
            color = Color.White, fontFamily = PixelFont, fontSize = (size.value * .22f).coerceIn(11f, 22f).sp,
            style = TextStyle(shadow = Shadow(Color(0xFF3F3F3F), Offset(2f, 2f))))
    }
}
