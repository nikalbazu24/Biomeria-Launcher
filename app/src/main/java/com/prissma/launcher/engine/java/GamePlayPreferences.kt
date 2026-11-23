package com.prissma.launcher.engine.java

import android.os.Bundle
import android.view.KeyEvent

internal val remappableGamepadButtons = linkedMapOf(
    KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B",
    KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
    KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1",
    KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
    KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
    KeyEvent.KEYCODE_BUTTON_START to "Start"
)

internal val defaultGamepadActions = remappableGamepadButtons.keys.zip(listOf(
    GamepadAction.JUMP, GamepadAction.SNEAK, GamepadAction.INVENTORY, GamepadAction.SWAP_HANDS,
    GamepadAction.PREVIOUS_SLOT, GamepadAction.NEXT_SLOT, GamepadAction.USE, GamepadAction.ATTACK,
    GamepadAction.SPRINT, GamepadAction.SNEAK, GamepadAction.ESCAPE
)).toMap()

internal fun decodeGamepadActions(raw: String): Map<Int, GamepadAction> = raw.split(',').mapNotNull { entry ->
    val code = entry.substringBefore('=').toIntOrNull()
    val action = GamepadAction.entries.firstOrNull { it.name == entry.substringAfter('=') && it.bindable }
    if (code != null && code in remappableGamepadButtons && action != null) code to action else null
}.toMap()

internal fun encodeGamepadActions(bindings: Map<Int, GamepadAction>): String =
    bindings.entries.joinToString(",") { (code, action) -> "$code=" + action.name }

internal data class GameHudSettings(
    val showTouch: Boolean = true,
    val showFps: Boolean = false,
    val pauseOpacity: Float = .65f,
    val dualScreen: Boolean = false,
    val routeCrafting: Boolean = true,
    val panelHome: PanelPage = PanelPage.INVENTORY,
    val panelMap: PanelMapSource = PanelMapSource.PRISSMA,
    val compactPanel: Boolean = false,
    val routeChests: Boolean = true,
    val routeFurnaces: Boolean = true,
    val showMinimap: Boolean = false,
    val showCoordinates: Boolean = false,
    val showBattery: Boolean = false,
    val widgetsBelow: Boolean = false,
    val widgetScale: Float = 1f,
    val widgetOpacity: Float = .65f,
    val fpsLimit: Int = 0,
    val batterySaver: Boolean = false
) {
    fun routes(layout: PanelLayout) = when (layout) {
        PanelLayout.PERSONAL -> false
        PanelLayout.CRAFTING_TABLE -> routeCrafting
        PanelLayout.CHEST, PanelLayout.DOUBLE_CHEST, PanelLayout.HOPPER, PanelLayout.DISPENSER -> routeChests
        PanelLayout.FURNACE -> routeFurnaces
    }

    fun toBundle() = Bundle().apply {
        putBoolean("touch", showTouch)
        putBoolean("fps", showFps)
        putFloat("opacity", pauseOpacity)
        putBoolean("dualScreen", dualScreen)
        putBoolean("routeCrafting", routeCrafting)
        putString("panelHome", panelHome.name)
        putString("panelMap", panelMap.name)
        putBoolean("compactPanel", compactPanel)
        putBoolean("routeChests", routeChests)
        putBoolean("routeFurnaces", routeFurnaces)
        putBoolean("minimap", showMinimap)
        putBoolean("coordinates", showCoordinates)
        putBoolean("battery", showBattery)
        putBoolean("widgetsBelow", widgetsBelow)
        putFloat("widgetScale", widgetScale)
        putFloat("widgetOpacity", widgetOpacity)
        putInt("fpsLimit", fpsLimit)
        putBoolean("batterySaver", batterySaver)
    }

    companion object {
        fun fromBundle(bundle: Bundle?) = GameHudSettings(
            bundle?.getBoolean("touch", true) ?: true,
            bundle?.getBoolean("fps", false) ?: false,
            bundle?.getFloat("opacity", .65f)?.coerceIn(.15f, 1f) ?: .65f,
            bundle?.getBoolean("dualScreen", false) ?: false,
            bundle?.getBoolean("routeCrafting", true) ?: true,
            PanelPage.fromPreference(bundle?.getString("panelHome")),
            PanelMapSource.fromPreference(bundle?.getString("panelMap")),
            bundle?.getBoolean("compactPanel", false) ?: false,
            bundle?.getBoolean("routeChests", true) ?: true,
            bundle?.getBoolean("routeFurnaces", true) ?: true,
            bundle?.getBoolean("minimap", false) ?: false,
            bundle?.getBoolean("coordinates", false) ?: false,
            bundle?.getBoolean("battery", false) ?: false,
            bundle?.getBoolean("widgetsBelow", false) ?: false,
            bundle?.getFloat("widgetScale", 1f)?.coerceIn(.75f, 1.5f) ?: 1f,
            bundle?.getFloat("widgetOpacity", .65f)?.coerceIn(.25f, 1f) ?: .65f,
            normalizeFpsLimit(bundle?.getInt("fpsLimit") ?: 0),
            bundle?.getBoolean("batterySaver", false) ?: false
        )
    }
}
