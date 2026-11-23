package com.biomeria.launcher.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biomeria.launcher.R

/**
 * Biomeria palette. Property names are kept from the BIOMERIA theme so every screen
 * that reads Biomeria.colors picks up the new look without further changes.
 */
@Immutable
class BiomeriaColors {
    val surfaceVoid = Color(0xFF101418)
    val surfaceCard = Color(0xFF161B21)
    val surfaceElevated = Color(0xFF1E252C)
    val surfaceGlass = Color(0xE60B0E11)
    val textPrimary = Color(0xFFF2F5EF)
    val textMuted = Color(0xFFA9B6BD)
    /** Forest green: the Java biome and every primary action. */
    val brandAccent = Color(0xFF7BC74D)
    val brandAccentDark = Color(0xFF4C8A2B)
    val brandAccentLight = Color(0xFFA6E07E)
    val onBrandAccent = Color(0xFF0E1A08)
    /** Gold: notices, favourites and focus. */
    val gold = Color(0xFFF5E663)
    val netP2P = Color(0xFF4FB3D9)
    val borderSubtle = Color(0xFF34404A)
    val ink = Color(0xFF0B0E11)
    val engineJava = Color(0xFF7BC74D)
    val engineRust = Color(0xFFF07D4A)
    val engineBedrock = Color(0xFF4FB3D9)
    val error = Color(0xFFFF8A6B)

    fun engineColor(engineName: String): Color = when (engineName) {
        "JAVA" -> engineJava
        "RUST" -> engineRust
        "BEDROCK" -> engineBedrock
        else -> brandAccent
    }
}

object Biomeria {
    val colors = BiomeriaColors()
}

/** Pixel display face for titles; Nunito for everything people read. */
val PixelFont = FontFamily(Font(R.font.pixelify_sans_bold, FontWeight.Bold))
val BodyFont = FontFamily(
    Font(R.font.nunito_medium, FontWeight.Normal),
    Font(R.font.nunito_medium, FontWeight.Medium),
    Font(R.font.nunito_bold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    Font(R.font.nunito_extrabold, FontWeight.Black)
)

private val scheme = Biomeria.colors.run {
    darkColorScheme(
        primary = brandAccent,
        onPrimary = onBrandAccent,
        primaryContainer = surfaceElevated,
        onPrimaryContainer = textPrimary,
        secondary = netP2P,
        onSecondary = ink,
        // Without these Material falls back to its purple baseline (tonal buttons, chips, cards).
        secondaryContainer = surfaceElevated,
        onSecondaryContainer = textPrimary,
        tertiary = gold,
        onTertiary = ink,
        tertiaryContainer = surfaceElevated,
        onTertiaryContainer = gold,
        surfaceVariant = surfaceElevated,
        surfaceTint = brandAccent,
        surfaceContainerLowest = ink,
        surfaceContainerLow = surfaceVoid,
        surfaceDim = surfaceVoid,
        surfaceBright = surfaceElevated,
        inverseSurface = textPrimary,
        inverseOnSurface = ink,
        inversePrimary = brandAccentDark,
        scrim = Color(0xCC000000),
        onError = ink,
        errorContainer = Color(0xFF3A1C17),
        onErrorContainer = error,
        background = surfaceVoid,
        surface = surfaceCard,
        surfaceContainer = surfaceCard,
        surfaceContainerHigh = surfaceElevated,
        surfaceContainerHighest = surfaceElevated,
        onBackground = textPrimary,
        onSurface = textPrimary,
        onSurfaceVariant = textMuted,
        outline = borderSubtle,
        outlineVariant = borderSubtle,
        error = error
    )
}

private val typography = Typography().run {
    fun TextStyle.body() = copy(fontFamily = BodyFont)
    fun TextStyle.pixel() = copy(fontFamily = PixelFont, fontWeight = FontWeight.Bold)
    copy(
        displayLarge = displayLarge.pixel(), displayMedium = displayMedium.pixel(), displaySmall = displaySmall.pixel(),
        headlineLarge = headlineLarge.pixel(), headlineMedium = headlineMedium.pixel(), headlineSmall = headlineSmall.pixel(),
        titleLarge = titleLarge.body(), titleMedium = titleMedium.body(), titleSmall = titleSmall.body(),
        bodyLarge = bodyLarge.body(), bodyMedium = bodyMedium.body(), bodySmall = bodySmall.body(),
        labelLarge = labelLarge.body(), labelMedium = labelMedium.body(), labelSmall = labelSmall.body()
    )
}

/** Small radii: blocks, not bubbles. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp)
)

@Composable
fun BiomeriaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
        // Text outside a Surface would otherwise be black on Biomeria's dark backgrounds.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Biomeria.colors.textPrimary, content = content
        )
    }
}
