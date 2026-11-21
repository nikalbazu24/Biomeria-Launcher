package com.prissma.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
class PrissmaColors {
    val surfaceVoid = Color(0xFF101618)
    val surfaceCard = Color(0xFF192124)
    val surfaceElevated = Color(0xFF242E31)
    val surfaceGlass = Color(0xE6101618)
    val textPrimary = Color(0xFFF3F7F2)
    val textMuted = Color(0xFFA4B3B3)
    val brandAccent = Color(0xFFDDF6AA)
    val netP2P = Color(0xFF8CD8CF)
    val borderSubtle = Color(0xFF354143)
    val engineJava = Color(0xFFEFC681)
    val engineRust = Color(0xFFF2A28E)
    val engineBedrock = Color(0xFF93C7F3)
    val error = Color(0xFFFFA8A1)

    fun engineColor(engineName: String): Color = when (engineName) {
        "JAVA" -> engineJava
        "RUST" -> engineRust
        "BEDROCK" -> engineBedrock
        else -> brandAccent
    }
}

object Prissma {
    val colors = PrissmaColors()
}

private val scheme = Prissma.colors.run {
    darkColorScheme(
        primary = brandAccent,
        onPrimary = surfaceVoid,
        primaryContainer = surfaceElevated,
        onPrimaryContainer = textPrimary,
        secondary = netP2P,
        background = surfaceVoid,
        surface = surfaceCard,
        surfaceContainer = surfaceCard,
        surfaceContainerHigh = surfaceElevated,
        onBackground = textPrimary,
        onSurface = textPrimary,
        onSurfaceVariant = textMuted,
        outline = borderSubtle,
        error = error
    )
}

@Composable
fun PrissmaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
