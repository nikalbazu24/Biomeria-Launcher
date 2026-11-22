package com.prissma.launcher.data

import java.util.UUID

enum class EngineType(
    val displayName: String,
    val defaultLoader: String
) {
    JAVA("Java", "Vanilla"),
    RUST("Rust", "Starling Native"),
    BEDROCK("Bedrock", "Minecraft Android")
}

data class GameInstance(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val engine: EngineType,
    val version: String,
    val loader: String,
    val playtimeHours: Float = 0f,
    val friendsOnline: Int = 0
) {
    val configuredModLoader: String?
        get() = loader.substringBefore(' ').lowercase().takeIf {
            it in setOf("fabric", "forge", "neoforge", "quilt")
        }
}
