package com.prissma.launcher.engine.java

import kotlin.math.abs

internal enum class PanelPadAction {
    UP, DOWN, LEFT, RIGHT, PICK, SPLIT, TRANSFER, BACK, PREVIOUS_TAB, NEXT_TAB;

    val directional get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}

internal data class GamepadAxes(
    val moveX: Float, val moveY: Float, val lookX: Float, val lookY: Float,
    val hatX: Float, val hatY: Float, val attack: Boolean, val use: Boolean
) {
    val neutral get() = moveX == 0f && moveY == 0f && lookX == 0f && lookY == 0f &&
        hatX == 0f && hatY == 0f && !attack && !use

    fun direction(): PanelPadAction? {
        val hasHat = abs(hatX) >= .5f || abs(hatY) >= .5f
        val x = if (hasHat) hatX else moveX
        val y = if (hasHat) hatY else moveY
        if (maxOf(abs(x), abs(y)) < .5f) return null
        return if (abs(x) >= abs(y)) {
            if (x < 0) PanelPadAction.LEFT else PanelPadAction.RIGHT
        } else if (y < 0) PanelPadAction.UP else PanelPadAction.DOWN
    }
}

/** Routes physical events once and quarantines held controls until their next release after a handoff. */
internal class PanelPadRouter(
    private val onFocus: (Boolean) -> Unit,
    private val onAction: (PanelPadAction) -> Unit
) {
    private data class Button(val device: Int, val code: Int)

    private val held = mutableMapOf<Button, PanelPadAction?>()
    private val suppressed = mutableSetOf<Button>()
    private val movingDevices = mutableSetOf<Int>()
    private val suppressedAxes = mutableSetOf<Int>()
    private var owner: Int? = null
    private var available = false
    private var stickDevice: Int? = null
    private var stickDirection: PanelPadAction? = null
    private var nextRepeat = 0L
    private var lastDirection: PanelPadAction? = null
    private var lastDirectionAt = 0L

    fun setAvailable(value: Boolean) {
        available = value
        if (!value) release()
    }

    fun select(device: Int, duration: Long): Boolean {
        if (owner != null) {
            release()
            return true
        }
        if (!available || duration < 500L) return false
        changeOwner(device)
        return true
    }

    fun release() {
        if (owner != null) changeOwner(null)
    }

    fun removeDevice(device: Int) {
        held.keys.removeAll { it.device == device }
        suppressed.removeAll { it.device == device }
        movingDevices.remove(device)
        suppressedAxes.remove(device)
        if (stickDevice == device) {
            stickDevice = null
            stickDirection = null
        }
        if (owner == device) release()
    }

    private fun changeOwner(device: Int?) {
        suppressed.addAll(held.keys)
        suppressedAxes.addAll(movingDevices)
        owner = device
        stickDevice = null
        stickDirection = null
        lastDirection = null
        onFocus(device != null)
    }

    fun button(device: Int, code: Int, action: PanelPadAction?, down: Boolean, repeat: Boolean, time: Long): Boolean {
        val button = Button(device, code)
        if (down) held[button] = action else held.remove(button)
        if (button in suppressed) {
            if (!down) suppressed.remove(button)
            return true
        }
        if (owner == null) return false
        if (down && action != null && (!repeat || action.directional)) dispatch(action, time)
        return true
    }

    fun motion(device: Int, axes: GamepadAxes, time: Long): Boolean {
        if (axes.neutral) movingDevices.remove(device) else movingDevices.add(device)
        if (device in suppressedAxes) {
            if (axes.neutral) suppressedAxes.remove(device)
            return true
        }
        if (owner == null) return false

        val direction = axes.direction()
        if (direction != null && (device != stickDevice || direction != stickDirection)) {
            stickDevice = device
            stickDirection = direction
            nextRepeat = time + 320L
            if (!digitalDirectionHeld()) dispatch(direction, time)
        } else if (direction == null && device == stickDevice) {
            stickDirection = null
            stickDevice = null
        }
        return true
    }

    fun tick(time: Long) {
        val direction = stickDirection ?: return
        if (owner != null && time >= nextRepeat && !digitalDirectionHeld()) {
            nextRepeat = time + 120L
            dispatch(direction, time)
        }
    }

    private fun digitalDirectionHeld() = held.any { (button, action) ->
        button !in suppressed && action?.directional == true
    }

    private fun dispatch(action: PanelPadAction, time: Long) {
        // A physical d-pad can report the same direction as an Android key and a hat axis.
        if (action.directional) {
            if (lastDirection == action && time - lastDirectionAt in 0L..45L) return
            lastDirection = action
            lastDirectionAt = time
        }
        onAction(action)
    }
}
