package com.prissma.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val SurfaceVoid = Color(0xFF090B0E)
val SurfaceCard = Color(0xFF141820)
val SurfaceElevated = Color(0xFF1E2430)
val SurfaceGlass = Color(0xD9090B0E)

val TextPrimary = Color(0xFFF3F6FC)
val TextMuted = Color(0xFF8E9BB0)

val BrandAccent = Color(0xFF00D68F)
val NetP2PPurple = Color(0xFFA78BFA)
val BorderSubtle = Color(0xFF262E3D)

val EngineJavaAmber = Color(0xFFF59E0B)
val EngineRustTerracotta = Color(0xFFE85D3F)
val EngineBedrockSky = Color(0xFF38BDF8)

private val PrissmaColorScheme = darkColorScheme(
    primary = BrandAccent,
    onPrimary = SurfaceVoid,
    secondary = NetP2PPurple,
    background = SurfaceVoid,
    surface = SurfaceCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Immutable
data class PrissmaColors(
    val surfaceVoid: Color = SurfaceVoid,
    val surfaceCard: Color = SurfaceCard,
    val surfaceElevated: Color = SurfaceElevated,
    val surfaceGlass: Color = SurfaceGlass,
    val textPrimary: Color = TextPrimary,
    val textMuted: Color = TextMuted,
    val brandAccent: Color = BrandAccent,
    val netP2P: Color = NetP2PPurple,
    val borderSubtle: Color = BorderSubtle,
    val engineJava: Color = EngineJavaAmber,
    val engineRust: Color = EngineRustTerracotta,
    val engineBedrock: Color = EngineBedrockSky
) {
    fun engineColor(engineName: String): Color = when (engineName.uppercase()) {
        "JAVA" -> engineJava
        "RUST" -> engineRust
        "BEDROCK" -> engineBedrock
        else -> brandAccent
    }
}

val LocalPrissmaColors = staticCompositionLocalOf { PrissmaColors() }

@Composable
fun PrissmaTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPrissmaColors provides PrissmaColors()) {
        MaterialTheme(
            colorScheme = PrissmaColorScheme,
            content = content
        )
    }
}

object Prissma {
    val colors: PrissmaColors
        @Composable
        @ReadOnlyComposable
        get() = LocalPrissmaColors.current
}
