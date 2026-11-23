package com.prissma.launcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prissma.launcher.ui.theme.Prissma

internal val PanelShape = RoundedCornerShape(6.dp)
internal val ControlShape = RoundedCornerShape(4.dp)

/** The Biomeria block: forest on top, ocean on the left face, Nether on the right. */
@Composable
internal fun PrissmaMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // Drawn in a 200×220 box, scaled to fit and centred.
        val scale = minOf(size.width / 200f, size.height / 220f)
        val dx = (size.width - 200f * scale) / 2
        val dy = (size.height - 220f * scale) / 2
        fun face(color: Color, vararg points: Float) {
            val path = Path()
            for (i in points.indices step 2) {
                val x = dx + points[i] * scale
                val y = dy + points[i + 1] * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color)
        }
        face(Color(0xFF7BC74D), 100f, 20f, 180f, 60f, 100f, 100f, 20f, 60f)
        face(Color(0xFFA6E07E), 60f, 40f, 80f, 50f, 60f, 60f, 40f, 50f)
        face(Color(0xFF2E8FBF), 20f, 60f, 100f, 100f, 100f, 200f, 20f, 160f)
        face(Color(0xFF4FB3D9), 40f, 120f, 60f, 130f, 60f, 150f, 40f, 140f)
        face(Color(0xFFD9572B), 100f, 100f, 180f, 60f, 180f, 160f, 100f, 200f)
        face(Color(0xFFF5B14A), 140f, 120f, 160f, 110f, 160f, 130f, 140f, 140f)
        face(Color(0xFF5FA83A), 20f, 60f, 100f, 100f, 100f, 122f, 20f, 82f)
        face(Color(0xFF4C9A3F), 100f, 100f, 180f, 60f, 180f, 82f, 100f, 122f)
    }
}

/** Colors of a block button: face, the light line on top, the dark ledge below and the label. */
internal data class BlockColors(val face: Color, val light: Color, val ledge: Color, val content: Color)

internal object BlockButtons {
    val primary = BlockColors(Color(0xFF7BC74D), Color(0xFFA6E07E), Color(0xFF4C8A2B), Color(0xFF0E1A08))
    val neutral = BlockColors(Color(0xFF2A333B), Color(0xFF3E4A54), Color(0xFF1A2026), Color(0xFFF2F5EF))
    val ocean = BlockColors(Color(0xFF4FB3D9), Color(0xFF8FD3EC), Color(0xFF2E86B5), Color(0xFF08202B))
    val gold = BlockColors(Color(0xFFF5E663), Color(0xFFFFF4A8), Color(0xFFC9B93A), Color(0xFF2A2508))
    val danger = BlockColors(Color(0xFF3A1F19), Color(0xFF55302A), Color(0xFF24120E), Color(0xFFFFD2C4))
}

/**
 * Flat button shaped like a game block: a solid face with a thin light line on top and a
 * darker ledge at the bottom, inside a dark outline. Focus turns the outline gold.
 */
@Composable
internal fun BlockButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: BlockColors = BlockButtons.neutral,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    content: @Composable RowScope.() -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val face = if (pressed) colors.ledge else colors.face
    Row(
        modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else .45f)
            .clip(ControlShape)
            .background(face)
            .drawBehind {
                if (!pressed) {
                    drawRect(colors.light, size = Size(size.width, 2.dp.toPx()))
                    val ledge = 4.dp.toPx()
                    drawRect(colors.ledge, Offset(0f, size.height - ledge), Size(size.width, ledge))
                }
            }
            .border(if (focused) 3.dp else 2.dp, if (focused) Prissma.colors.gold else Prissma.colors.ink, ControlShape)
            .gamepadClick(enabled, onClick)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding)
            .padding(bottom = if (pressed) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.content) { content() }
    }
}

@Composable
internal fun LauncherButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    BlockButton(onClick, modifier, if (primary) BlockButtons.primary else BlockButtons.neutral, enabled) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.labelLarge)
    }
}

/** Square block button for a single icon, such as the favourite star. */
@Composable
internal fun BlockIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Prissma.colors.textPrimary,
    enabled: Boolean = true
) {
    BlockButton(onClick, modifier.size(48.dp).semantics { contentDescription = label },
        BlockButtons.neutral, enabled, PaddingValues(0.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun PageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LauncherButton("Volver", onBack, icon = Icons.Default.ArrowBack)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall,
                color = Prissma.colors.textPrimary, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = Prissma.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(8.dp)) {
        drawCircle(color.copy(alpha = .34f), style = Stroke(width = 1.dp.toPx()))
        drawCircle(color, radius = size.minDimension * .25f)
    }
}

internal fun Modifier.gamepadClick(enabled: Boolean = true, onClick: () -> Unit) = onKeyEvent {
    if (enabled && it.key == Key.ButtonA && it.type == KeyEventType.KeyUp) {
        onClick()
        true
    } else false
}
