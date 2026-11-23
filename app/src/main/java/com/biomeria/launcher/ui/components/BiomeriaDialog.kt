package com.biomeria.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.biomeria.launcher.ui.theme.Biomeria
import com.biomeria.launcher.ui.theme.PixelFont

/**
 * The frame every Biomeria window shares: a dark card with a thick outline, a colored strip on
 * top, the title in the pixel font with a small tag, and a footer with the buttons. It takes the
 * same slots as a Material AlertDialog so existing windows can switch to it directly.
 */
@Composable
internal fun BiomeriaDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    accent: Color = Biomeria.colors.brandAccent,
    chip: String? = null,
    width: Dp = 560.dp
) {
    val palette = Biomeria.colors
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier.widthIn(max = width).fillMaxWidth(0.94f).heightIn(max = maxHeight)
                .clip(PanelShape).background(palette.surfaceCard).border(3.dp, palette.ink, PanelShape)
        ) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(accent))
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    ProvideTextStyle(TextStyle(fontFamily = PixelFont, fontSize = 24.sp, color = palette.textPrimary)) { title() }
                }
                if (chip != null) Text(chip, Modifier.background(accent, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp), color = palette.ink,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
            }
            Box(Modifier.weight(1f, fill = false).padding(horizontal = 22.dp, vertical = 4.dp)) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(color = palette.textPrimary)) { text() }
            }
            Row(Modifier.fillMaxWidth().background(palette.surfaceVoid).padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A elegir · B cerrar", Modifier.weight(1f), color = palette.textMuted,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

/** A text field that looks like the rest of Biomeria: square corners and the dark field color. */
@Composable
internal fun BiomeriaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val palette = Biomeria.colors
    OutlinedTextField(
        value, onValueChange, modifier, enabled = enabled,
        label = label?.let { { Text(it) } }, placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine, maxLines = maxLines, shape = ControlShape,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = palette.ink, unfocusedContainerColor = palette.ink,
            focusedBorderColor = palette.gold, unfocusedBorderColor = palette.borderSubtle,
            focusedLabelColor = palette.gold, unfocusedLabelColor = palette.textMuted,
            cursorColor = palette.gold
        )
    )
}
