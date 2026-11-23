package com.biomeria.launcher.engine.java

internal const val DEFAULT_JAVA_HEAP_MB = 1536
internal val javaFpsLimits = listOf(0, 30, 45, 60, 90, 120)
internal fun normalizeFpsLimit(value: Int): Int = value.takeIf { it in javaFpsLimits } ?: 0
internal fun javaFrameLimit(limit: Int, saver: Boolean, foreground: Boolean): Int {
    val chosen = normalizeFpsLimit(limit)
    return if (saver && !foreground) minOf(if (chosen == 0) 15 else chosen, 15) else chosen
}
private val javaHeapSizes = listOf(768, 1024, 1536, 2048, 3072, 4096, 6144, 8192)

internal enum class RenderScale(val percent: Int) {
    FULL(100), REDUCED(75);

    fun pixels(displayPixels: Int): Int =
        if (displayPixels <= 0) 0 else ((displayPixels.toLong() * percent + 50) / 100).toInt().coerceAtLeast(1)

    companion object {
        fun fromPercent(percent: Int) = entries.firstOrNull { it.percent == percent } ?: FULL
    }
}

internal enum class JavaRenderer(val label: String) {
    MOBILE_GLUES("MobileGlues · OpenGL ES");

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: MOBILE_GLUES
    }
}

internal fun availableHeapSizes(totalRamBytes: Long): List<Int> {
    val deviceMiB = totalRamBytes / 1_048_576L
    if (deviceMiB <= 0) return javaHeapSizes.takeWhile { it <= DEFAULT_JAVA_HEAP_MB }
    // Reserve memory for Android, Java outside the heap and the graphics driver.
    val heapBudget = minOf(deviceMiB * 65 / 100, deviceMiB - 768).coerceAtLeast(768)
    return javaHeapSizes.takeWhile { it <= heapBudget }
}

internal fun selectedHeapSize(requestedMb: Int, totalRamBytes: Long): Int {
    val sizes = availableHeapSizes(totalRamBytes)
    return sizes.lastOrNull { it <= requestedMb } ?: sizes.first()
}
