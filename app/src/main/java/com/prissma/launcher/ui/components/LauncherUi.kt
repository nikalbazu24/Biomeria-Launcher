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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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

/** Raised edge like the game's buttons: a light line on top, a dark ledge below. */
internal fun Modifier.blockBevel(light: Color, dark: Color, ledge: Float = 4f) = drawWithContent {
    drawContent()
    val px = density
    drawRect(light.copy(alpha = .55f), Offset(0f, 0f), Size(size.width, 2f * px))
    drawRect(dark.copy(alpha = .65f), Offset(0f, size.height - ledge * px), Size(size.width, ledge * px))
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
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val border = androidx.compose.foundation.BorderStroke(
        if (focused) 3.dp else 2.dp,
        if (focused) Prissma.colors.gold else Prissma.colors.ink
    )
    val content: @Composable RowScope.() -> Unit = {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
    }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.gamepadClick(enabled, onClick).heightIn(min = 44.dp).clip(ControlShape)
                .then(if (enabled) Modifier.blockBevel(Prissma.colors.brandAccentLight, Prissma.colors.brandAccentDark) else Modifier),
            enabled = enabled,
            shape = ControlShape,
            border = if (focused) androidx.compose.foundation.BorderStroke(3.dp, Prissma.colors.gold)
                else androidx.compose.foundation.BorderStroke(2.dp, Prissma.colors.ink),
            colors = ButtonDefaults.buttonColors(
                containerColor = Prissma.colors.brandAccent,
                contentColor = Prissma.colors.onBrandAccent
            ),
            interactionSource = source,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
            content = content
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.gamepadClick(enabled, onClick).heightIn(min = 44.dp).clip(ControlShape)
                .then(if (enabled) Modifier.blockBevel(Color(0xFF3E4A54), Color(0xFF0B0E11)) else Modifier),
            enabled = enabled,
            shape = ControlShape,
            border = border,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (focused) Color(0xFF34404A) else Color(0xFF2A333B),
                contentColor = Prissma.colors.textPrimary
            ),
            interactionSource = source,
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
            content = content
        )
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
