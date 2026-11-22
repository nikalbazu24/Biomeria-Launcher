package com.prissma.launcher.engine.java

import org.junit.Assert.*
import org.junit.Test

class PanelPadRouterTest {
    private val focus = mutableListOf<Boolean>()
    private val actions = mutableListOf<PanelPadAction>()
    private val router = PanelPadRouter({ focus += it }, { actions += it })
    private val neutral = GamepadAxes(0f, 0f, 0f, 0f, 0f, 0f, attack = false, use = false)

    @Test
    fun onlyALongSelectTakesControlAndATapReturnsIt() {
        assertFalse(router.select(4, 600))
        router.setAvailable(true)
        assertFalse(router.select(4, 100))
        assertTrue(router.select(4, 500))
        assertTrue(router.select(4, 100))
        assertEquals(listOf(true, false), focus)
    }

    @Test
    fun heldButtonsCannotExecuteOnTheOtherScreenBeforeRelease() {
        router.setAvailable(true)
        assertFalse(router.button(4, 96, PanelPadAction.PICK, true, false, 20))
        assertTrue(router.select(4, 550))
        assertTrue(router.button(4, 96, PanelPadAction.PICK, true, true, 500))
        assertTrue(router.button(4, 96, PanelPadAction.PICK, false, false, 530))
        assertTrue(actions.isEmpty())

        assertTrue(router.button(4, 96, PanelPadAction.PICK, true, false, 550))
        router.release()
        assertTrue(router.button(4, 96, PanelPadAction.PICK, true, true, 590))
        assertTrue(router.button(4, 96, PanelPadAction.PICK, false, false, 600))
        assertFalse(router.button(4, 96, PanelPadAction.PICK, true, false, 700))
        assertEquals(listOf(PanelPadAction.PICK), actions)
        assertEquals(listOf(true, false), focus)
    }

    @Test
    fun eachHandoffRequiresNeutralSticksAndTriggersBeforeSendingNewMotion() {
        val holding = neutral.copy(moveX = .75f, attack = true)
        router.setAvailable(true)
        assertFalse(router.motion(4, holding, 0))
        assertTrue(router.select(4, 550))
        assertTrue(router.motion(4, holding, 650))
        assertTrue(actions.isEmpty())
        assertTrue(router.motion(4, neutral.copy(attack = true), 660))
        assertTrue(router.motion(4, neutral, 670))
        assertTrue(router.motion(4, holding, 700))
        assertEquals(listOf(PanelPadAction.RIGHT), actions)
        router.release()
        assertTrue(router.motion(4, holding, 750))
        assertTrue(router.motion(4, neutral, 800))
        assertFalse(router.motion(4, holding, 850))
    }

    @Test
    fun dpadHatAndDigitalEventsAreCoalescedAndStickRepeatsStopOnRelease() {
        router.setAvailable(true)
        router.select(4, 500)
        assertTrue(router.motion(4, neutral.copy(hatY = -1f), 800))
        assertTrue(router.button(4, 19, PanelPadAction.UP, true, false, 811))
        router.tick(1200)
        assertEquals(listOf(PanelPadAction.UP), actions)
        router.button(4, 19, PanelPadAction.UP, false, false, 1210)
        router.motion(4, neutral, 1212)
        router.motion(4, neutral.copy(moveY = .8f), 1260)
        router.tick(1400)
        router.tick(1590)
        router.motion(4, neutral, 1600)
        router.tick(1900)
        assertEquals(listOf(PanelPadAction.UP, PanelPadAction.DOWN, PanelPadAction.DOWN), actions)
    }

    @Test
    fun missingDisplayAndUnpluggingReturnControlWithoutAStuckButton() {
        router.setAvailable(true)
        router.select(4, 550)
        router.setAvailable(false)
        assertFalse(router.select(4, 600))
        router.setAvailable(true)
        router.select(4, 550)
        router.button(4, 96, PanelPadAction.PICK, true, false, 700)
        router.removeDevice(4)
        assertFalse(router.button(4, 96, PanelPadAction.PICK, true, false, 900))
        assertEquals(listOf(true, false, true, false), focus)
        assertEquals(listOf(PanelPadAction.PICK), actions)
    }
}
