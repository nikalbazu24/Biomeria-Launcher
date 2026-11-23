package com.prissma.launcher.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.prissma.launcher.R
import com.prissma.launcher.data.EngineType

/** Each engine lives in a biome: Java is the forest, Bedrock the ocean, Starling the Nether. */
internal val EngineType.biomeName: String
    get() = when (this) {
        EngineType.JAVA -> "Bosque"
        EngineType.BEDROCK -> "Océano"
        EngineType.RUST -> "Nether"
    }

/** The engine as players know it: Starling rather than the language it is written in. */
internal val EngineType.engineLabel: String
    get() = when (this) {
        EngineType.JAVA -> "Java"
        EngineType.BEDROCK -> "Bedrock"
        EngineType.RUST -> "Starling"
    }

internal val EngineType.biomeLabel: String get() = "$biomeName · $engineLabel"

@get:DrawableRes
internal val EngineType.biomeImage: Int
    get() = when (this) {
        EngineType.JAVA -> R.drawable.prissma_valley
        EngineType.BEDROCK -> R.drawable.biome_ocean
        EngineType.RUST -> R.drawable.biome_nether
    }

internal val EngineType.biomeColor: Color get() = Prissma.colors.engineColor(name)
