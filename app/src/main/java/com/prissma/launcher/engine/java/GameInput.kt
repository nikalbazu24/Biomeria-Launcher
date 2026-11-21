package com.prissma.launcher.engine.java

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

internal class GameInput {
    private var viewWidth = 1
    private var viewHeight = 1
    private var width = 1
    private var height = 1
    private var cursorX = 0.0
    private var cursorY = 0.0
    private var touchId = -1
    private var touchX = 0f
    private var touchY = 0f
    private var touchClick = false
    private var lookX = 0f
    private var lookY = 0f
    private var moveX = 0f
    private var moveY = 0f
    private var hatX = 0f
    private var hatY = 0f
    private var leftTrigger = false
    private var rightTrigger = false
    private val moveKeys = mutableSetOf<Int>()
    private var captured = false
    var gamepadUsed by mutableStateOf(false)
        private set

    fun resize(viewWidth: Int, viewHeight: Int, width: Int, height: Int) {
        this.viewWidth = viewWidth.coerceAtLeast(1)
        this.viewHeight = viewHeight.coerceAtLeast(1)
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        movePointer(width / 2.0, height / 2.0)
    }

    fun setCaptured(value: Boolean) {
        if (captured == value) return
        captured = value
        reset()
        if (!value) movePointer(width / 2.0, height / 2.0)
    }

    fun reset() {
        touchId = -1
        touchClick = false
        mousePositionKnown = false
        lookX = 0f
        lookY = 0f
        moveX = 0f
        moveY = 0f
        hatX = 0f
        hatY = 0f
        leftTrigger = false
        rightTrigger = false
        moveKeys.clear()
        padMappings.clear()
        NativeJavaBridge.nativeInput(5, 0, 0, 0.0, 0.0)
    }

    fun touch(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return mouse(event)
        gamepadUsed = false
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (touchId == -1) {
                touchId = event.getPointerId(index)
                touchX = event.getX(index)
                touchY = event.getY(index)
                if (!captured) {
                    movePointer(touchX * width.toDouble() / viewWidth, touchY * height.toDouble() / viewHeight)
                    mouseButton(0, true)
                    touchClick = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val active = event.findPointerIndex(touchId)
                if (active >= 0) {
                    val x = event.getX(active)
                    val y = event.getY(active)
                    if (captured) relative((x - touchX).toDouble(), (y - touchY).toDouble())
                    else movePointer(x * width.toDouble() / viewWidth, y * height.toDouble() / viewHeight)
                    touchX = x
                    touchY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || touchId == event.getPointerId(index)) {
                    if (touchClick) mouseButton(0, false)
                    touchClick = false
                    touchId = -1
                }
            }
        }
        return true
    }

    private fun mouse(event: MotionEvent): Boolean {
        val x = event.x * width.toDouble() / viewWidth
        val y = event.y * height.toDouble() / viewHeight
        if (captured) {
            val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X).toDouble()
            val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y).toDouble()
            if (dx != 0.0 || dy != 0.0) relative(dx, dy)
            else if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                if (mousePositionKnown) relative(x - cursorX, y - cursorY)
                cursorX = x
                cursorY = y
                mousePositionKnown = true
            }
        } else {
            movePointer(x, y)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> NativeJavaBridge.nativeInput(
                4, 0, 0, event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(),
                event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble()
            )
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val button = when (event.actionButton) {
                    MotionEvent.BUTTON_PRIMARY -> 0
                    MotionEvent.BUTTON_SECONDARY -> 1
                    MotionEvent.BUTTON_TERTIARY -> 2
                    else -> return true
                }
                mouseButton(button, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
            }
        }
        return true
    }

    private var mousePositionKnown = false

    fun motion(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return mouse(event)
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        gamepadUsed = true
        moveX = axis(event, MotionEvent.AXIS_X)
        moveY = axis(event, MotionEvent.AXIS_Y)
        lookX = axis(event, MotionEvent.AXIS_Z)
        lookY = axis(event, MotionEvent.AXIS_RZ)
        if (event.device?.getMotionRange(MotionEvent.AXIS_Z, event.source) == null) {
            lookX = axis(event, MotionEvent.AXIS_RX)
            lookY = axis(event, MotionEvent.AXIS_RY)
        }
        hatX = axis(event, MotionEvent.AXIS_HAT_X)
        hatY = axis(event, MotionEvent.AXIS_HAT_Y)
        val attack = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > .45f
        val use = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > .45f
        if (attack != rightTrigger) mouseButton(0, attack)
        if (use != leftTrigger) mouseButton(1, use)
        rightTrigger = attack
        leftTrigger = use
        return true
    }

    fun tick() {
        if (captured) {
            updateMoveKey(65, moveX < -.35f || hatX < -.5f)
            updateMoveKey(68, moveX > .35f || hatX > .5f)
            updateMoveKey(87, moveY < -.35f || hatY < -.5f)
            updateMoveKey(83, moveY > .35f || hatY > .5f)
            if (lookX != 0f || lookY != 0f) relative(lookX * 26.0, lookY * 26.0)
        } else {
            val dx = (moveX + lookX + hatX).coerceIn(-1f, 1f)
            val dy = (moveY + lookY + hatY).coerceIn(-1f, 1f)
            if (dx != 0f || dy != 0f) {
                val speed = width / 110.0
                movePointer(cursorX + dx * speed, cursorY + dy * speed)
            }
        }
    }

    private val padMappings = mutableMapOf<Int, Int>()

    fun keyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP && event.action != KeyEvent.ACTION_DOWN) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val code = event.keyCode
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) || KeyEvent.isGamepadButton(code)
        ) {
            gamepadUsed = true
            val mapping = if (down) padMappings.getOrPut(code) { padMapping(code) }
                          else padMappings.remove(code) ?: return false
            if (mapping == 0) return false
            if (event.repeatCount == 0) {
                if (mapping < 0) mouseButton(-mapping - 1, down) else key(mapping, down)
            }
            return true
        }
        val translated = keyboardMapping(code)
        if (translated == 0) return false
        val character = event.unicodeChar.takeIf { it in 32..0x10ffff } ?: 0
        NativeJavaBridge.nativeInput(3, translated, if (!down) 0 else if (event.repeatCount > 0) 2 else 1,
            character.toDouble(), 0.0)
        return true
    }

    private fun padMapping(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> if (captured) 32 else -1
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START -> 256
        KeyEvent.KEYCODE_BUTTON_X -> if (captured) 69 else -1
        KeyEvent.KEYCODE_BUTTON_Y -> 70
        KeyEvent.KEYCODE_BUTTON_L1 -> 81
        KeyEvent.KEYCODE_BUTTON_R1 -> 70
        KeyEvent.KEYCODE_BUTTON_L2 -> -2
        KeyEvent.KEYCODE_BUTTON_R2 -> -1
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 341
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 340
        KeyEvent.KEYCODE_DPAD_CENTER -> if (captured) 32 else -1
        KeyEvent.KEYCODE_DPAD_UP -> if (captured) 87 else 265
        KeyEvent.KEYCODE_DPAD_DOWN -> if (captured) 83 else 264
        KeyEvent.KEYCODE_DPAD_LEFT -> if (captured) 65 else 263
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (captured) 68 else 262
        else -> 0
    }

    fun key(key: Int, down: Boolean) {
        NativeJavaBridge.nativeInput(3, key, if (down) 1 else 0, 0.0, 0.0)
    }

    fun mouseButton(button: Int, down: Boolean) {
        NativeJavaBridge.nativeInput(2, button, if (down) 1 else 0, 0.0, 0.0)
    }

    private fun updateMoveKey(key: Int, down: Boolean) {
        if (if (down) moveKeys.add(key) else moveKeys.remove(key)) key(key, down)
    }

    private fun movePointer(x: Double, y: Double) {
        cursorX = x.coerceIn(0.0, (width - 1).toDouble())
        cursorY = y.coerceIn(0.0, (height - 1).toDouble())
        NativeJavaBridge.nativeInput(0, 0, 0, cursorX, cursorY)
    }

    private fun relative(dx: Double, dy: Double) = NativeJavaBridge.nativeInput(1, 0, 0, dx, dy)

    private fun axis(event: MotionEvent, axis: Int): Float {
        val value = event.getAxisValue(axis)
        val flat = event.device?.getMotionRange(axis, event.source)?.flat ?: 0f
        return if (abs(value) > maxOf(flat, .18f)) value else 0f
    }
}

private fun keyboardMapping(code: Int): Int = when (code) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 65 + code - KeyEvent.KEYCODE_A
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 48 + code - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 290 + code - KeyEvent.KEYCODE_F1
    KeyEvent.KEYCODE_SPACE -> 32
    KeyEvent.KEYCODE_APOSTROPHE -> 39
    KeyEvent.KEYCODE_COMMA -> 44
    KeyEvent.KEYCODE_MINUS -> 45
    KeyEvent.KEYCODE_PERIOD -> 46
    KeyEvent.KEYCODE_SLASH -> 47
    KeyEvent.KEYCODE_SEMICOLON -> 59
    KeyEvent.KEYCODE_EQUALS -> 61
    KeyEvent.KEYCODE_LEFT_BRACKET -> 91
    KeyEvent.KEYCODE_BACKSLASH -> 92
    KeyEvent.KEYCODE_RIGHT_BRACKET -> 93
    KeyEvent.KEYCODE_GRAVE -> 96
    KeyEvent.KEYCODE_ESCAPE -> 256
    KeyEvent.KEYCODE_ENTER -> 257
    KeyEvent.KEYCODE_TAB -> 258
    KeyEvent.KEYCODE_DEL -> 259
    KeyEvent.KEYCODE_INSERT -> 260
    KeyEvent.KEYCODE_FORWARD_DEL -> 261
    KeyEvent.KEYCODE_DPAD_RIGHT -> 262
    KeyEvent.KEYCODE_DPAD_LEFT -> 263
    KeyEvent.KEYCODE_DPAD_DOWN -> 264
    KeyEvent.KEYCODE_DPAD_UP -> 265
    KeyEvent.KEYCODE_PAGE_UP -> 266
    KeyEvent.KEYCODE_PAGE_DOWN -> 267
    KeyEvent.KEYCODE_MOVE_HOME -> 268
    KeyEvent.KEYCODE_MOVE_END -> 269
    KeyEvent.KEYCODE_SHIFT_LEFT -> 340
    KeyEvent.KEYCODE_CTRL_LEFT -> 341
    KeyEvent.KEYCODE_ALT_LEFT -> 342
    KeyEvent.KEYCODE_SHIFT_RIGHT -> 344
    KeyEvent.KEYCODE_CTRL_RIGHT -> 345
    KeyEvent.KEYCODE_ALT_RIGHT -> 346
    else -> 0
}
