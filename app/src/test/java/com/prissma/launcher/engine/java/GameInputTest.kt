package com.prissma.launcher.engine.java

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameInputTest {
    private data class Event(
        val type: GameInputEvent,
        val code: Int = 0,
        val action: Int = 0,
        val x: Double = 0.0,
        val y: Double = 0.0
    )

    private val events = mutableListOf<Event>()
    private val input = GameInput { type, code, action, x, y -> events.add(Event(type, code, action, x, y)) }

    @Test
    fun menuStickMovesTheVisibleCursorAndAUsesItsClickPosition() {
        input.resize(viewWidth = 960, viewHeight = 540, width = 1920, height = 1080)
        events.clear()

        motion(moveX = 1f, moveY = -1f)
        input.tick()
        tap(KeyEvent.KEYCODE_BUTTON_A)

        assertTrue(input.gamepadUsed)
        assertEquals(3, events.size)
        val pointer = events.first()
        assertEquals(GameInputEvent.POINTER, pointer.type)
        assertTrue(pointer.x > 960 && pointer.y < 540)
        assertEquals(pointer.x / 1920, input.menuCursor.x.toDouble(), .000001)
        assertEquals(pointer.y / 1080, input.menuCursor.y.toDouble(), .000001)
        assertEquals(listOf(mouse(0, 1), mouse(0, 0)), events.drop(1))

        repeat(200) { input.tick() }
        assertEquals(Event(GameInputEvent.POINTER, x = 1919.0, y = 0.0), events.last())
    }

    @Test
    fun bClosesMenusAndSneaksWhilePlaying() {
        tap(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(listOf(key(256, 1), key(256, 0)), events)

        input.setCaptured(true)
        events.clear()
        tap(KeyEvent.KEYCODE_BUTTON_B)
        tap(KeyEvent.KEYCODE_BUTTON_START)
        tap(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf(key(340, 1), key(340, 0), key(256, 1), key(256, 0), key(32, 1), key(32, 0)), events)
    }

    @Test
    fun shoulderButtonsScrollOneSlotPerPressOnlyInGame() {
        assertFalse(input.gamepadButton(KeyEvent.KEYCODE_BUTTON_L1, down = true))
        assertFalse(input.gamepadButton(KeyEvent.KEYCODE_BUTTON_R1, down = true))
        assertTrue(events.isEmpty())

        input.setCaptured(true)
        events.clear()
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_L1, down = true)
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_L1, down = true, repeated = true)
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_L1, down = true)
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_L1, down = false)
        tap(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(listOf(Event(GameInputEvent.SCROLL, y = 1.0), Event(GameInputEvent.SCROLL, y = -1.0)), events)
    }

    @Test
    fun cameraAndMovementKeepTheirSeparateSticks() {
        input.setCaptured(true)
        events.clear()
        motion(moveY = -1f, lookX = .5f, lookY = -.5f)
        input.tick()
        motion()
        input.tick()
        assertEquals(
            listOf(key(87, 1), Event(GameInputEvent.RELATIVE_POINTER, x = 13.0, y = -13.0), key(87, 0)),
            events
        )
    }

    @Test
    fun digitalAndAnalogTriggerSignalsDoNotCauseDoubleClicksOrEarlyRelease() {
        input.setCaptured(true)
        events.clear()
        motion(attack = true, use = true)
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_R2, down = true)
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_L2, down = true)
        motion(use = true)
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_L2, down = false)
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_R2, down = false)
        motion()
        assertEquals(listOf(mouse(0, 1), mouse(1, 1), mouse(0, 0), mouse(1, 0)), events)
    }

    @Test
    fun aHeldAcrossACaptureChangeDoesNotJumpUntilPressedAgain() {
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_A, down = true)
        input.setCaptured(true)
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_A, down = true, repeated = true)
        input.gamepadButton(KeyEvent.KEYCODE_BUTTON_A, down = false)
        tap(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf(mouse(0, 1), Event(GameInputEvent.RESET), key(32, 1), key(32, 0)), events)
    }

    @Test
    fun aRemappedShoulderAndTouchCanSelectSpecificHotbarSlots() {
        val mapped = GameInput(mapOf(KeyEvent.KEYCODE_BUTTON_L1 to GamepadAction.SLOT_4)) { type, code, action, x, y ->
            events.add(Event(type, code, action, x, y))
        }
        mapped.selectHotbarSlot(9)
        assertTrue(events.isEmpty())
        mapped.setCaptured(true)
        events.clear()
        assertTrue(mapped.gamepadButton(KeyEvent.KEYCODE_BUTTON_L1, true))
        assertTrue(mapped.gamepadButton(KeyEvent.KEYCODE_BUTTON_L1, false))
        mapped.selectHotbarSlot(9)
        mapped.selectHotbarSlot(0)
        assertEquals(listOf(key(52, 1), key(52, 0), key(57, 1), key(57, 0)), events)
    }

    @Test
    fun controllerWheelUsesTheConfiguredCalibration() {
        val calibrated = GameInput(scrollScale = 4.0) { type, code, action, x, y ->
            events.add(Event(type, code, action, x, y))
        }
        calibrated.setCaptured(true)
        events.clear()
        calibrated.gamepadButton(KeyEvent.KEYCODE_BUTTON_R1, true)
        calibrated.gamepadButton(KeyEvent.KEYCODE_BUTTON_R1, false)
        assertEquals(listOf(Event(GameInputEvent.SCROLL, y = -4.0)), events)
    }

    @Test
    fun remappingRoundTripsAndIgnoresUnsupportedActions() {
        val bindings = mapOf(KeyEvent.KEYCODE_BUTTON_L1 to GamepadAction.SLOT_4)
        val encoded = encodeGamepadActions(bindings) + "," +
            KeyEvent.KEYCODE_BUTTON_R1 + "=WALK_LEFT,9999=USE,BROKEN"
        assertEquals(bindings, decodeGamepadActions(encoded))
    }

    private fun tap(code: Int) {
        assertTrue(input.gamepadButton(code, down = true))
        assertTrue(input.gamepadButton(code, down = false))
    }

    private fun key(code: Int, action: Int) = Event(GameInputEvent.KEY, code, action)

    private fun mouse(code: Int, action: Int) = Event(GameInputEvent.MOUSE_BUTTON, code, action)

    private fun motion(
        moveX: Float = 0f, moveY: Float = 0f, lookX: Float = 0f, lookY: Float = 0f,
        attack: Boolean = false, use: Boolean = false
    ) = input.gamepadMotion(moveX, moveY, lookX, lookY, 0f, 0f, attack, use)
}
