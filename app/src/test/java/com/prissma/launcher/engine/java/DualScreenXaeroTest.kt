package com.prissma.launcher.engine.java

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class DualScreenXaeroTest {
    @Test
    fun acceptsOnlyTheCurrentMapsFrameWithItsExactPixelLength() {
        val frame = requireNotNull(frame())
        assertEquals(2, frame.width)
        assertEquals(1, frame.height)
        assertArrayEquals(byteArrayOf(1, 2, 3, -1, 4, 5, 6, -1), frame.rgba)
        assertNull(frame(generation = 8))
        assertNull(frame(width = 641))
        assertNull(frame(height = 481))
        assertNull(frame(width = 3))
        assertNull(frame(extraByte = true))
    }

    @Test
    fun encodesTouchCoordinatesIndependentlyOfThePanelResolution() {
        assertEquals(xaeroTouchPosition(160f, 90f, 320, 180), xaeroTouchPosition(320f, 180f, 640, 360))
        assertEquals(0, xaeroTouchPosition(-1f, -1f, 320, 180))
        assertEquals(0xffffff, xaeroTouchPosition(640f, 400f, 640, 360))
    }

    @Test
    fun mapPanPreservesBothSignedAxesAndRejectsMalformedOrUnroutedCommands() {
        val pan = xaeroPanVector(1.5f, -.5f)
        assertEquals(127, (pan shr 8).toByte().toInt())
        assertEquals(-63, pan.toByte().toInt())
        assertEquals(0x817f, xaeroPanVector(-1f, 1f))
        assertTrue(PanelLayout.XAERO.accepts(PanelCommand.MAP_PAN, pan))
        assertFalse(PanelLayout.CRAFTING_TABLE.accepts(PanelCommand.MAP_PAN, pan))
        listOf(0, -1, 0x8001, 0x0180, 0x10000).forEach {
            assertFalse(PanelLayout.XAERO.accepts(PanelCommand.MAP_PAN, it))
        }
    }

    private fun frame(generation: Long = 7, width: Int = 2, height: Int = 1, extraByte: Boolean = false): XaeroFrame? {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(XAERO_FRAME_MAGIC)
            out.writeLong(generation)
            out.writeInt(width)
            out.writeInt(height)
            out.write(byteArrayOf(1, 2, 3, -1, 4, 5, 6, -1))
            if (extraByte) out.writeByte(0)
        }
        val data = bytes.toByteArray()
        return DataInputStream(data.inputStream()).use { decodeXaeroFrame(it, 7, 20, data.size.toLong()) }
    }
}
