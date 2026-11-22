package com.prissma.launcher.engine.java

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

internal const val XAERO_FRAME_MAGIC = 0x50584d31

internal data class XaeroFrame(
    val generation: Long,
    val modifiedAt: Long,
    val width: Int,
    val height: Int,
    val rgba: ByteArray
)

internal fun DualScreenBridge.readXaeroFrame(generation: Long, previous: XaeroFrame?): XaeroFrame? {
    val file = File(directory, "xaero-frame")
    val stamp = file.lastModified()
    val length = file.length()
    if (stamp == 0L || length !in 24L..(20L + 640 * 480 * 4)) return null
    if (previous?.generation == generation && previous.modifiedAt == stamp) return previous
    return try {
        DataInputStream(file.inputStream().buffered()).use { decodeXaeroFrame(it, generation, stamp, length) }
    } catch (_: IOException) {
        null
    }
}

internal fun decodeXaeroFrame(input: DataInputStream, generation: Long, stamp: Long, length: Long): XaeroFrame? {
    if (input.readInt() != XAERO_FRAME_MAGIC || input.readLong() != generation) return null
    val width = input.readInt()
    val height = input.readInt()
    if (width !in 1..640 || height !in 1..480) return null
    val bytes = width * height * 4
    if (length != 20L + bytes) return null
    return XaeroFrame(generation, stamp, width, height, ByteArray(bytes).also(input::readFully))
}

internal fun xaeroTouchPosition(x: Float, y: Float, width: Int, height: Int): Int {
    val horizontal = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
    val vertical = (y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
    return ((horizontal * 4095).roundToInt() shl 12) or (vertical * 4095).roundToInt()
}

internal fun xaeroPanVector(x: Float, y: Float): Int {
    val horizontal = (x.coerceIn(-1f, 1f) * 127).roundToInt()
    val vertical = (y.coerceIn(-1f, 1f) * 127).roundToInt()
    return ((horizontal and 0xff) shl 8) or (vertical and 0xff)
}

internal fun validXaeroPanVector(value: Int): Boolean =
    value in 1..0xffff && value ushr 8 != 0x80 && value and 0xff != 0x80
