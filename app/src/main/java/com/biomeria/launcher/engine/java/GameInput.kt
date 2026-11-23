package com.biomeria.launcher.engine.java

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

internal enum class GameInputEvent(val nativeId: Int) {
    POINTER(0), RELATIVE_POINTER(1), MOUSE_BUTTON(2), KEY(3), SCROLL(4), RESET(5), CHARACTER(6)
}

internal enum class GamepadAction(val event: GameInputEvent, val code: Int, val label: String = "") {
    ATTACK(GameInputEvent.MOUSE_BUTTON, 0, "Atacar"),
    USE(GameInputEvent.MOUSE_BUTTON, 1, "Usar objeto"),
    JUMP(GameInputEvent.KEY, 32, "Saltar"),
    SNEAK(GameInputEvent.KEY, 340, "Agacharse"),
    SPRINT(GameInputEvent.KEY, 341, "Correr"),
    ESCAPE(GameInputEvent.KEY, 256, "Abrir pausa"),
    INVENTORY(GameInputEvent.KEY, 69, "Abrir inventario"),
    QUICK_MENU(GameInputEvent.KEY, 0, "Menú rápido de Biomeria"),
    SWAP_HANDS(GameInputEvent.KEY, 70, "Intercambiar manos"),
    DROP(GameInputEvent.KEY, 81, "Soltar objeto"),
    PREVIOUS_SLOT(GameInputEvent.SCROLL, 1, "Casilla anterior"),
    NEXT_SLOT(GameInputEvent.SCROLL, -1, "Casilla siguiente"),
    SLOT_1(GameInputEvent.KEY, 49, "Casilla 1"),
    SLOT_2(GameInputEvent.KEY, 50, "Casilla 2"),
    SLOT_3(GameInputEvent.KEY, 51, "Casilla 3"),
    SLOT_4(GameInputEvent.KEY, 52, "Casilla 4"),
    SLOT_5(GameInputEvent.KEY, 53, "Casilla 5"),
    SLOT_6(GameInputEvent.KEY, 54, "Casilla 6"),
    SLOT_7(GameInputEvent.KEY, 55, "Casilla 7"),
    SLOT_8(GameInputEvent.KEY, 56, "Casilla 8"),
    SLOT_9(GameInputEvent.KEY, 57, "Casilla 9"),
    WALK_LEFT(GameInputEvent.KEY, 65),
    WALK_RIGHT(GameInputEvent.KEY, 68),
    WALK_FORWARD(GameInputEvent.KEY, 87),
    WALK_BACK(GameInputEvent.KEY, 83),
    ARROW_LEFT(GameInputEvent.KEY, 263),
    ARROW_RIGHT(GameInputEvent.KEY, 262),
    ARROW_UP(GameInputEvent.KEY, 265),
    ARROW_DOWN(GameInputEvent.KEY, 264);

    val bindable: Boolean get() = label.isNotEmpty()
}

internal class GameInput(
    private val bindings: Map<Int, GamepadAction> = emptyMap(),
    private val scrollScale: Double = 1.0,
    private val emit: (GameInputEvent, Int, Int, Double, Double) -> Unit = { event, code, action, x, y ->
        NativeJavaBridge.nativeInput(event.nativeId, code, action, x, y)
    }
) {
    /** Camera turn per frame at full right-stick tilt; set from the HUD's camera speed. */
    @Volatile var lookSpeed = CameraSpeed.MEDIUM.factor

    fun opensQuickMenu(code: Int): Boolean = bindings[code] == GamepadAction.QUICK_MENU

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
    /** Hotbar slot (0-8) under the finger that went down on the hotbar, or -1. */
    private var touchHotbar = -1
    /** GUI scale from the mod; 0 means "work it out like Minecraft's automatic scale". */
    @Volatile var guiScale = 0
    /** True while Minecraft shows a screen with item slots; touches there become tap, hold and drag. */
    var slotScreen: () -> Boolean = { false }
    private var slotTouch = false
    private var slotDragging = false
    private var slotDownTime = 0L
    private var slotStartX = 0f
    private var slotStartY = 0f

    private fun viewPointer(x: Float, y: Float) = movePointer(x * width.toDouble() / viewWidth, y * height.toDouble() / viewHeight)

    private fun clickAt(x: Float, y: Float, button: Int) {
        viewPointer(x, y)
        mouseButton(button, true)
        mouseButton(button, false)
    }

    /** Returns true when something else (the lower screen) handled the inventory button. */
    var inventoryInterceptor: ((fromPad: Boolean) -> Boolean)? = null

    /** The inventory key from touch or a menu: goes to the lower screen when there is one. */
    fun inventoryKey(down: Boolean) {
        if (down && captured && inventoryInterceptor?.invoke(false) == true) { touchInventoryIntercepted = true; return }
        if (!down && touchInventoryIntercepted) { touchInventoryIntercepted = false; return }
        key(69, down)
    }
    private var touchInventoryIntercepted = false

    /** Called when an item is dragged from one hotbar slot to another. */
    var onHotbarSwap: ((Int, Int) -> Unit)? = null
    /** Slot being dragged and the slot under the finger, for the on-screen highlight. */
    var hotbarDrag by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    private var lookX = 0f
    private var lookY = 0f
    private var moveX = 0f
    private var moveY = 0f
    private var hatX = 0f
    private var hatY = 0f
    private val gamepadButtons = mutableMapOf<Int, GamepadAction>()
    private val touchActions = mutableSetOf<GamepadAction>()
    private val analogActions = mutableSetOf<GamepadAction>()
    private val activeGamepadActions = mutableSetOf<GamepadAction>()
    private var captured = false
    var gamepadUsed by mutableStateOf(false)
        private set
    var menuCursor by mutableStateOf(Offset(.5f, .5f))
        private set

    fun resize(viewWidth: Int, viewHeight: Int, width: Int, height: Int) {
        this.viewWidth = viewWidth.coerceAtLeast(1)
        this.viewHeight = viewHeight.coerceAtLeast(1)
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        if (!captured) movePointer(this.width / 2.0, this.height / 2.0)
    }

    fun setCaptured(value: Boolean) {
        if (captured == value) return
        captured = value
        reset()
        if (!value) movePointer(width / 2.0, height / 2.0)
    }

    fun reset() {
        touchId = -1
        touchHotbar = -1
        hotbarDrag = null
        slotTouch = false
        slotDragging = false
        touchClick = false
        mousePositionKnown = false
        lookX = 0f
        lookY = 0f
        moveX = 0f
        moveY = 0f
        hatX = 0f
        hatY = 0f
        gamepadButtons.clear()
        analogActions.clear()
        touchActions.clear()
        activeGamepadActions.clear()
        send(GameInputEvent.RESET)
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
                touchHotbar = if (captured) hotbarSlotAt(touchX, touchY) else -1
                if (touchHotbar >= 0) {
                    selectHotbarSlot(touchHotbar + 1)
                    hotbarDrag = touchHotbar to touchHotbar
                } else if (!captured && slotScreen()) {
                    // Tap = click, hold = right click (half a stack / one item), drag = carry the stack.
                    slotTouch = true
                    slotDragging = false
                    slotDownTime = event.eventTime
                    slotStartX = touchX
                    slotStartY = touchY
                    viewPointer(touchX, touchY)
                } else if (!captured) {
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
                    if (slotTouch) {
                        if (!slotDragging && abs(x - slotStartX) + abs(y - slotStartY) > viewWidth * .012f) {
                            slotDragging = true
                            clickAt(slotStartX, slotStartY, 0) // picks the stack up
                        }
                        viewPointer(x, y) // the stack follows the finger
                    } else if (touchHotbar >= 0) {
                        val over = hotbarSlotAt(x, y)
                        hotbarDrag = touchHotbar to (if (over >= 0) over else touchHotbar)
                    } else if (captured) relative((x - touchX) * width.toDouble() / viewWidth, (y - touchY) * height.toDouble() / viewHeight)
                    else movePointer(x * width.toDouble() / viewWidth, y * height.toDouble() / viewHeight)
                    touchX = x
                    touchY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || touchId == event.getPointerId(index)) {
                    if (touchClick) mouseButton(0, false)
                    if (slotTouch && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        val x = event.getX(index)
                        val y = event.getY(index)
                        when {
                            slotDragging -> clickAt(x, y, 0) // drops it where the finger lifted
                            event.eventTime - slotDownTime >= 450L -> clickAt(x, y, 1)
                            else -> clickAt(x, y, 0)
                        }
                    }
                    slotTouch = false
                    slotDragging = false
                    if (touchHotbar >= 0 && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        val target = hotbarSlotAt(event.getX(index), event.getY(index))
                        if (target >= 0 && target != touchHotbar) {
                            onHotbarSwap?.invoke(touchHotbar, target)
                            selectHotbarSlot(target + 1)
                        }
                    }
                    touchHotbar = -1
                    hotbarDrag = null
                    touchClick = false
                    touchId = -1
                }
            }
        }
        return true
    }

    private fun mouse(event: MotionEvent): Boolean {
        gamepadUsed = false
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
            MotionEvent.ACTION_SCROLL -> send(
                GameInputEvent.SCROLL,
                x = event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(),
                y = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble()
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
        return false
    }

    fun gamepadMotion(axes: GamepadAxes) = gamepadMotion(
        axes.moveX, axes.moveY, axes.lookX, axes.lookY, axes.hatX, axes.hatY, axes.attack, axes.use
    )

    internal fun gamepadMotion(
        moveX: Float, moveY: Float, lookX: Float, lookY: Float,
        hatX: Float, hatY: Float, attack: Boolean, use: Boolean
    ) {
        this.moveX = moveX
        this.moveY = moveY
        this.lookX = lookX
        this.lookY = lookY
        this.hatX = hatX
        this.hatY = hatY
        if (moveX != 0f || moveY != 0f || lookX != 0f || lookY != 0f ||
            hatX != 0f || hatY != 0f || attack || use
        ) gamepadUsed = true
        if (captured) {
            setAnalogAction(GamepadAction.WALK_LEFT, moveX < -.35f || hatX < -.5f)
            setAnalogAction(GamepadAction.WALK_RIGHT, moveX > .35f || hatX > .5f)
            setAnalogAction(GamepadAction.WALK_FORWARD, moveY < -.35f || hatY < -.5f)
            setAnalogAction(GamepadAction.WALK_BACK, moveY > .35f || hatY > .5f)
        }
        val attackAction = gamepadAction(KeyEvent.KEYCODE_BUTTON_R2) ?: GamepadAction.ATTACK
        val useAction = gamepadAction(KeyEvent.KEYCODE_BUTTON_L2) ?: GamepadAction.USE
        setAnalogAction(attackAction, attack || (attackAction == useAction && use))
        if (useAction != attackAction) setAnalogAction(useAction, use)
    }

    fun tick() {
        if (captured) {
            if (lookX != 0f || lookY != 0f) relative(lookX * lookSpeed, lookY * lookSpeed)
        } else {
            val dx = (moveX + lookX + hatX).coerceIn(-1f, 1f)
            val dy = (moveY + lookY + hatY).coerceIn(-1f, 1f)
            if (dx != 0f || dy != 0f) {
                val speed = width / 110.0
                movePointer(cursorX + dx * speed, cursorY + dy * speed)
            }
        }
    }

    fun keyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP && event.action != KeyEvent.ACTION_DOWN) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val code = event.keyCode
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) || KeyEvent.isGamepadButton(code)
        ) {
            return gamepadButton(code, down, event.repeatCount > 0)
        }
        val translated = keyboardMapping(code)
        if (translated == 0) return false
        val character = event.unicodeChar.takeIf { it in 32..0x10ffff } ?: 0
        send(GameInputEvent.KEY, translated, if (!down) 0 else if (event.repeatCount > 0) 2 else 1,
            character.toDouble())
        return true
    }

    internal fun gamepadButton(code: Int, down: Boolean, repeated: Boolean = false): Boolean {
        val mapping = gamepadAction(code) ?: return false
        if (down && repeated) return true
        if (down) {
            if (mapping == GamepadAction.INVENTORY && captured && inventoryInterceptor?.invoke(true) == true) return true
            if (gamepadButtons.putIfAbsent(code, mapping) != null) return true
            gamepadUsed = true
            updateGamepadAction(mapping)
        } else {
            val held = gamepadButtons.remove(code)
            if (held != null) updateGamepadAction(held)
        }
        return true
    }

    private fun gamepadAction(code: Int): GamepadAction? {
        if (captured && code in defaultGamepadActions) return bindings[code] ?: defaultGamepadActions.getValue(code)
        return when (code) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER ->
                if (captured) GamepadAction.JUMP else GamepadAction.ATTACK
            KeyEvent.KEYCODE_BUTTON_B -> if (captured) GamepadAction.SNEAK else GamepadAction.ESCAPE
            KeyEvent.KEYCODE_BUTTON_START -> GamepadAction.ESCAPE
            KeyEvent.KEYCODE_BUTTON_X -> if (captured) GamepadAction.INVENTORY else GamepadAction.ATTACK
            KeyEvent.KEYCODE_BUTTON_Y -> GamepadAction.SWAP_HANDS
            KeyEvent.KEYCODE_BUTTON_L1 -> GamepadAction.PREVIOUS_SLOT.takeIf { captured }
            KeyEvent.KEYCODE_BUTTON_R1 -> GamepadAction.NEXT_SLOT.takeIf { captured }
            KeyEvent.KEYCODE_BUTTON_L2 -> GamepadAction.USE
            KeyEvent.KEYCODE_BUTTON_R2 -> GamepadAction.ATTACK
            KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadAction.SPRINT
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadAction.SNEAK
            KeyEvent.KEYCODE_DPAD_UP -> if (captured) GamepadAction.WALK_FORWARD else GamepadAction.ARROW_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> if (captured) GamepadAction.WALK_BACK else GamepadAction.ARROW_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> if (captured) GamepadAction.WALK_LEFT else GamepadAction.ARROW_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (captured) GamepadAction.WALK_RIGHT else GamepadAction.ARROW_RIGHT
            else -> null
        }
    }

    private fun setAnalogAction(action: GamepadAction, pressed: Boolean) {
        val changed = if (pressed) analogActions.add(action) else analogActions.remove(action)
        if (changed) updateGamepadAction(action)
    }

    private fun updateGamepadAction(action: GamepadAction) {
        // Some controllers report one press both as an axis and as an Android key.
        val pressed = action in touchActions || action in analogActions || action in gamepadButtons.values
        val changed = if (pressed) activeGamepadActions.add(action) else activeGamepadActions.remove(action)
        if (!changed) return
        if (action.event == GameInputEvent.SCROLL) {
            if (pressed) send(GameInputEvent.SCROLL, y = action.code * scrollScale)
        } else {
            send(action.event, action.code, if (pressed) 1 else 0)
        }
    }

    fun touchMovement(x: Float, y: Float) {
        setTouchAction(GamepadAction.WALK_LEFT, captured && x < -.28f)
        setTouchAction(GamepadAction.WALK_RIGHT, captured && x > .28f)
        setTouchAction(GamepadAction.WALK_FORWARD, captured && y < -.28f)
        setTouchAction(GamepadAction.WALK_BACK, captured && y > .28f)
    }

    private fun setTouchAction(action: GamepadAction, pressed: Boolean) {
        val changed = if (pressed) touchActions.add(action) else touchActions.remove(action)
        if (changed) updateGamepadAction(action)
    }

    fun insertText(text: String) {
        var position = 0
        while (position < text.length) {
            val point = text.codePointAt(position)
            if (!Character.isISOControl(point) && point !in 0xD800..0xDFFF) {
                send(GameInputEvent.CHARACTER, code = point)
            }
            position += Character.charCount(point)
        }
    }

    /** Hotbar slot (0-8) drawn under a point of the view, or -1. Mirrors Minecraft's hotbar layout. */
    fun hotbarSlotAt(viewX: Float, viewY: Float): Int {
        val scale = (if (guiScale > 0) guiScale else autoGuiScale(width, height)).coerceAtLeast(1)
        val guiWidth = Math.ceil(width.toDouble() / scale)
        val guiHeight = Math.ceil(height.toDouble() / scale)
        val x = viewX * width.toDouble() / viewWidth / scale
        val y = viewY * height.toDouble() / viewHeight / scale
        val left = guiWidth / 2 - 91
        // A little taller than the 22 px bar so it is easy to hit with a finger.
        if (y < guiHeight - 26 || x < left || x >= left + 182) return -1
        return ((x - left - 1) / 20).toInt().coerceIn(0, 8)
    }

    /** Where slot (0-8) is on the view, as left, top, size in view pixels. */
    fun hotbarSlotRect(slot: Int): Triple<Float, Float, Float> {
        val scale = (if (guiScale > 0) guiScale else autoGuiScale(width, height)).coerceAtLeast(1)
        val guiWidth = Math.ceil(width.toDouble() / scale)
        val guiHeight = Math.ceil(height.toDouble() / scale)
        val toView = scale * viewWidth.toDouble() / width
        val left = (guiWidth / 2 - 91 + 1 + slot * 20) * toView
        val top = (guiHeight - 21) * scale * viewHeight.toDouble() / height
        return Triple(left.toFloat(), top.toFloat(), (20 * toView).toFloat())
    }

    fun tapKey(key: Int) {
        key(key, true)
        key(key, false)
    }

    fun selectHotbarSlot(slot: Int) {
        if (!captured || slot !in 1..9) return
        tapKey(48 + slot)
    }

    fun key(key: Int, down: Boolean) {
        send(GameInputEvent.KEY, key, if (down) 1 else 0)
    }

    fun mouseButton(button: Int, down: Boolean) {
        send(GameInputEvent.MOUSE_BUTTON, button, if (down) 1 else 0)
    }

    private fun movePointer(x: Double, y: Double) {
        cursorX = x.coerceIn(0.0, (width - 1).toDouble())
        cursorY = y.coerceIn(0.0, (height - 1).toDouble())
        menuCursor = Offset((cursorX / width).toFloat(), (cursorY / height).toFloat())
        send(GameInputEvent.POINTER, x = cursorX, y = cursorY)
    }

    private fun relative(dx: Double, dy: Double) = send(GameInputEvent.RELATIVE_POINTER, x = dx, y = dy)

    private fun send(
        event: GameInputEvent, code: Int = 0, action: Int = 0, x: Double = 0.0, y: Double = 0.0
    ) = emit(event, code, action, x, y)

}

/** Minecraft's automatic GUI scale (option "Auto"). */
internal fun autoGuiScale(width: Int, height: Int): Int {
    var scale = 1
    while (scale < width && scale < height && width / (scale + 1) >= 320 && height / (scale + 1) >= 240) scale++
    return scale
}

internal fun readGamepadAxes(event: MotionEvent): GamepadAxes {
    val device = event.device
    fun axis(code: Int): Float {
        val value = event.getAxisValue(code)
        val flat = device?.getMotionRange(code, event.source)?.flat ?: 0f
        return if (abs(value) > maxOf(flat, .18f)) value else 0f
    }
    val useZAxes = device?.getMotionRange(MotionEvent.AXIS_Z, event.source) != null &&
        device.getMotionRange(MotionEvent.AXIS_RZ, event.source) != null
    return GamepadAxes(
        moveX = axis(MotionEvent.AXIS_X),
        moveY = axis(MotionEvent.AXIS_Y),
        lookX = axis(if (useZAxes) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX),
        lookY = axis(if (useZAxes) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY),
        hatX = axis(MotionEvent.AXIS_HAT_X),
        hatY = axis(MotionEvent.AXIS_HAT_Y),
        attack = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)) > .45f,
        use = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)) > .45f
    )
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
