package com.biomeria.launcher.engine.java

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
    val panelMap: PanelMapSource = PanelMapSource.BIOMERIA,
    val compactPanel: Boolean = false,
    val routeChests: Boolean = true,
    val routeFurnaces: Boolean = true,
    val showMinimap: Boolean = true,
    val showCoordinates: Boolean = false,
    val showBattery: Boolean = false,
    val widgetsBelow: Boolean = false,
    val widgetScale: Float = 1f,
    val widgetOpacity: Float = .65f,
    val fpsLimit: Int = 0,
    val batterySaver: Boolean = false,
    /** Creatures on the Biomeria map. Only singleplayer and this device's LAN room send them. */
    val showEntities: Boolean = true,
    /** How unlocked advancements are announced on the top screen. */
    val achievements: AchievementStyle = AchievementStyle.FULL,
    /** Look of the lower-screen inventory: Minecraft's grey panels or Biomeria's dark ones. */
    val panelStyle: PanelStyle = PanelStyle.CLASSIC,
    val textSize: PanelTextSize = PanelTextSize.NORMAL,
    val highContrast: Boolean = true,
    val reduceMotion: Boolean = false,
    /** Biomeria's own first-time hints with the controller's buttons. */
    val biomeriaTips: Boolean = true,
    /** Minecraft's keyboard tutorial toasts ("use W A S D…"). Off: options.txt gets tutorialStep:none. */
    val minecraftTutorial: Boolean = false,
    val cameraSpeed: CameraSpeed = CameraSpeed.MEDIUM,
    /** Scales everything on the lower screen (slots, buttons, text), not just the text. */
    val interfaceSize: InterfaceSize = InterfaceSize.SMALL,
    /** The tip strip along the bottom of the lower screen. */
    val panelTips: Boolean = true,
    /** The "controller drives game / panel" chip in the lower screen's top bar. */
    val showPadChip: Boolean = true,
    /** Which corner of the top screen the Biomeria tips appear in. */
    val tipCorner: TipCorner = TipCorner.TOP_END,
    /** Floating map for single-screen play: corner, size, see-through, how much land it shows, marks and coordinates. */
    val mapCorner: TipCorner = TipCorner.TOP_END,
    val mapSize: FloatingMapSize = FloatingMapSize.MEDIUM,
    val mapSoft: Boolean = false,
    val mapZoom: FloatingMapZoom = FloatingMapZoom.NORMAL,
    val mapMarks: Boolean = true,
    val mapCoords: Boolean = true
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
        putBoolean("entities", showEntities)
        putString("achievements", achievements.name)
        putString("panelStyle", panelStyle.name)
        putString("textSize", textSize.name)
        putBoolean("highContrast", highContrast)
        putBoolean("reduceMotion", reduceMotion)
        putBoolean("biomeriaTips", biomeriaTips)
        putBoolean("minecraftTutorial", minecraftTutorial)
        putString("cameraSpeed", cameraSpeed.name)
        putString("interfaceSize", interfaceSize.name)
        putBoolean("panelTips", panelTips)
        putBoolean("showPadChip", showPadChip)
        putString("tipCorner", tipCorner.name)
        putString("mapCorner", mapCorner.name)
        putString("mapSize", mapSize.name)
        putBoolean("mapSoft", mapSoft)
        putString("mapZoom", mapZoom.name)
        putBoolean("mapMarks", mapMarks)
        putBoolean("mapCoords", mapCoords)
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
            bundle?.getBoolean("minimap", true) ?: true,
            bundle?.getBoolean("coordinates", false) ?: false,
            bundle?.getBoolean("battery", false) ?: false,
            bundle?.getBoolean("widgetsBelow", false) ?: false,
            bundle?.getFloat("widgetScale", 1f)?.coerceIn(.75f, 1.5f) ?: 1f,
            bundle?.getFloat("widgetOpacity", .65f)?.coerceIn(.25f, 1f) ?: .65f,
            normalizeFpsLimit(bundle?.getInt("fpsLimit") ?: 0),
            bundle?.getBoolean("batterySaver", false) ?: false,
            bundle?.getBoolean("entities", true) ?: true,
            AchievementStyle.fromPreference(bundle?.getString("achievements")),
            PanelStyle.fromPreference(bundle?.getString("panelStyle")),
            PanelTextSize.fromPreference(bundle?.getString("textSize")),
            bundle?.getBoolean("highContrast", true) ?: true,
            bundle?.getBoolean("reduceMotion", false) ?: false,
            bundle?.getBoolean("biomeriaTips", true) ?: true,
            bundle?.getBoolean("minecraftTutorial", false) ?: false,
            CameraSpeed.fromPreference(bundle?.getString("cameraSpeed")),
            InterfaceSize.fromPreference(bundle?.getString("interfaceSize")),
            bundle?.getBoolean("panelTips", true) ?: true,
            bundle?.getBoolean("showPadChip", true) ?: true,
            TipCorner.fromPreference(bundle?.getString("tipCorner")),
            TipCorner.fromPreference(bundle?.getString("mapCorner")),
            FloatingMapSize.fromPreference(bundle?.getString("mapSize")),
            bundle?.getBoolean("mapSoft", false) ?: false,
            FloatingMapZoom.fromPreference(bundle?.getString("mapZoom")),
            bundle?.getBoolean("mapMarks", true) ?: true,
            bundle?.getBoolean("mapCoords", true) ?: true
        )
    }
}

internal enum class AchievementStyle(val label: String) {
    FULL("Animación de Biomeria"), SMALL("Solo aviso chico"), VANILLA("El aviso de Minecraft");

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: FULL
    }
}

internal enum class PanelStyle(val label: String) {
    CLASSIC("Clásico"), DARK("Oscuro");

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: CLASSIC
    }
}

internal enum class PanelTextSize(val label: String, val scale: Float) {
    SMALL("Chico", .9f), NORMAL("Normal", 1f), LARGE("Grande", 1.15f);

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

/** How fast the right stick turns the camera; [factor] multiplies the stick per frame. */
internal enum class CameraSpeed(val label: String, val factor: Double) {
    LOW("Baja", 18.0), MEDIUM("Media", 26.0), HIGH("Alta", 36.0);

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: MEDIUM
    }
}

/** How big the whole lower screen is drawn; [scale] multiplies every dp and sp in it. */
internal enum class InterfaceSize(val label: String, val scale: Float) {
    TINY("Muy chica", .7f), SMALL("Chica", .85f), NORMAL("Normal", 1f);

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: SMALL
    }
}

/** Corner of the top screen where Biomeria's tips show. */
internal enum class TipCorner(val label: String) {
    TOP_START("Arriba izquierda"), TOP_END("Arriba derecha"), BOTTOM_START("Abajo izquierda"), BOTTOM_END("Abajo derecha");

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: TOP_END
    }
}

/** Side of the floating map over the game. */
internal enum class FloatingMapSize(val label: String, val side: Int) {
    SMALL("Chico", 112), MEDIUM("Mediano", 144), LARGE("Grande", 184);

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: MEDIUM
    }
}

/** How many blocks across the floating map shows. */
internal enum class FloatingMapZoom(val label: String, val span: Float) {
    FAR("Lejos", 128f), NORMAL("Normal", 80f), NEAR("Cerca", 48f);

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: NORMAL
    }
}
