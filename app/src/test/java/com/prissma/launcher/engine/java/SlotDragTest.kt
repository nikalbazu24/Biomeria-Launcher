package com.prissma.launcher.engine.java

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotDragTest {
    @Test
    fun findsTheSlotUnderTheFinger() {
        val drag = SlotDrag { _, _ -> }
        drag.bounds[3] = Rect(0f, 0f, 50f, 50f)
        drag.bounds[4] = Rect(56f, 0f, 106f, 50f)
        assertEquals(4, drag.slotAt(Offset(70f, 20f)))
        assertNull(drag.slotAt(Offset(53f, 20f)))
    }
}
