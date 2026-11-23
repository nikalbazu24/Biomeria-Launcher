package com.biomeria.launcher.engine.java

import org.junit.Assert.assertEquals
import org.junit.Test

class GamePerformanceTest {
    @Test
    fun backgroundLimitRestoresThePreferredForegroundCap() {
        assertEquals(15, javaFrameLimit(0, saver = true, foreground = false))
        assertEquals(15, javaFrameLimit(60, saver = true, foreground = false))
        assertEquals(60, javaFrameLimit(60, saver = true, foreground = true))
        assertEquals(90, javaFrameLimit(90, saver = false, foreground = false))
        assertEquals(0, javaFrameLimit(-5, saver = false, foreground = true))
    }

    @Test
    fun resolutionScaleUsesRealBufferDimensions() {
        assertEquals(1920, RenderScale.FULL.pixels(1920))
        assertEquals(1440, RenderScale.REDUCED.pixels(1920))
        assertEquals(810, RenderScale.REDUCED.pixels(1080))
        assertEquals(901, RenderScale.REDUCED.pixels(1201))
        assertEquals(1, RenderScale.REDUCED.pixels(1))
        assertEquals(0, RenderScale.REDUCED.pixels(0))
        assertEquals(RenderScale.FULL, RenderScale.fromPercent(85))
    }

    @Test
    fun heapsAreLimitedByDeviceRamAndStayOnAvailablePresets() {
        val gibibyte = 1L shl 30
        assertEquals(listOf(768, 1024), availableHeapSizes(2 * gibibyte))
        assertEquals(2048, selectedHeapSize(8192, 4 * gibibyte))
        assertEquals(4096, selectedHeapSize(8192, 8 * gibibyte))
        assertEquals(8192, selectedHeapSize(8192, 16 * gibibyte))
        assertEquals(768, selectedHeapSize(100, 4 * gibibyte))
        assertEquals(DEFAULT_JAVA_HEAP_MB, selectedHeapSize(8192, 0))
    }
}
