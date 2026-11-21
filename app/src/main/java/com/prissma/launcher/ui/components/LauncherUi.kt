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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prissma.launcher.ui.theme.Prissma

internal val PanelShape = RoundedCornerShape(18.dp)
internal val ControlShape = RoundedCornerShape(11.dp)

@Composable
internal fun PrissmaMark(modifier: Modifier = Modifier) {
    val mint = Prissma.colors.brandAccent
    val turquoise = Prissma.colors.netP2P
    Canvas(modifier.background(Prissma.colors.surfaceElevated, ControlShape)) {
        val edge = size.minDimension / 9
        val left = (size.width - 5 * edge) / 2
        val top = (size.height - 6 * edge) / 2
        val pattern = listOf("11110", "11001", "11001", "11110", "11000", "11000")
        val gradient = Brush.linearGradient(
            listOf(mint, turquoise), Offset(left, top), Offset(left + 5 * edge, top + 6 * edge)
        )
        pattern.forEachIndexed { row, pixels ->
            pixels.forEachIndexed { column, pixel ->
                if (pixel == '1') drawRect(
                    gradient, Offset(left + column * edge, top + row * edge), Size(edge, edge)
                )
            }
        }
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
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val border = androidx.compose.foundation.BorderStroke(
        if (focused) 2.dp else 1.dp,
        if (focused) Prissma.colors.brandAccent else Prissma.colors.borderSubtle
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
            modifier = modifier.gamepadClick(enabled, onClick).heightIn(min = 44.dp),
            enabled = enabled,
            shape = ControlShape,
            border = if (focused) androidx.compose.foundation.BorderStroke(2.dp, Color.White) else null,
            colors = ButtonDefaults.buttonColors(
                containerColor = Prissma.colors.brandAccent,
                contentColor = Prissma.colors.surfaceVoid
            ),
            interactionSource = source,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
            content = content
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.gamepadClick(enabled, onClick).heightIn(min = 44.dp),
            enabled = enabled,
            shape = ControlShape,
            border = border,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (focused) Prissma.colors.surfaceElevated else Prissma.colors.surfaceCard,
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
