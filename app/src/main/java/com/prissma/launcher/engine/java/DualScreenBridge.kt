package com.prissma.launcher.engine.java

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal const val DUAL_SNAPSHOT_MAGIC = 0x50525332
internal const val DUAL_INVENTORY_SLOTS = 46
internal const val DUAL_MAP_SIZE = 128
internal const val DUAL_SCREEN_MOD = "DUAL_SCREEN_MOD"

internal fun supportsDualInventory(version: String, loader: String?) = version == "1.21.4" && loader == "fabric"

internal data class PanelStack(val name: String, val count: Int)
internal data class PanelInventory(
    val generation: Long,
    val interactive: Boolean,
    val selectedSlot: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val held: PanelStack,
    val slots: List<PanelStack>,
    val mapRevision: Int
)

internal enum class PanelCommand(val wireName: String, val slots: IntRange) {
    PICK("pick", 0..45), SPLIT("split", 0..45), QUICK_MOVE("quick", 0..45), SELECT_HOTBAR("hotbar", 0..8)
}

internal class DualScreenBridge(val directory: File) {
    private val sequence = AtomicLong()

    fun readInventory(nowMillis: Long = System.currentTimeMillis()): PanelInventory? {
        val file = File(directory, "snapshot")
        if (!file.isFile || nowMillis - file.lastModified() !in 0L..1800L || file.length() > 32768L) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use(::decodePanelInventory)
        } catch (error: IOException) {
            null
        }
    }

    fun readMap(revision: Int): IntArray? {
        val file = File(directory, "map")
        if (revision < 0 || file.length() != (4 + DUAL_MAP_SIZE * DUAL_MAP_SIZE * 4).toLong()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() == revision) IntArray(DUAL_MAP_SIZE * DUAL_MAP_SIZE) { input.readInt() } else null
            }
        } catch (error: IOException) {
            null
        }
    }

    fun send(inventory: PanelInventory, command: PanelCommand, slot: Int): Boolean {
        if (!inventory.interactive || slot !in command.slots || !File(directory, "visible").isFile) return false
        val now = readInventory() ?: return false
        if (!now.interactive || now.generation != inventory.generation) return false
        val target = File(directory, String.format(Locale.ROOT, "command-%012d", sequence.incrementAndGet()))
        val staged = File(directory, target.name + ".part")
        return try {
            staged.writeText("${inventory.generation} ${command.wireName} $slot", Charsets.US_ASCII)
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (error: IOException) {
            false
        } finally {
            staged.delete()
        }
    }

    fun visibility(visible: Boolean) {
        val marker = File(directory, "visible")
        if (visible) {
            try {
                marker.createNewFile()
            } catch (_: IOException) {
                // The session may have ended while Android was opening the panel.
            }
        } else {
            marker.delete()
            directory.listFiles()?.filter { it.name.startsWith("command-") }?.forEach { it.delete() }
        }
    }

    companion object {
        fun create(cache: File): DualScreenBridge = DualScreenBridge(Files.createTempDirectory(cache.toPath(), "dual-screen-").toFile())
    }
}

internal fun decodePanelInventory(input: DataInputStream): PanelInventory? {
    if (input.readInt() != DUAL_SNAPSHOT_MAGIC) return null
    val generation = input.readLong()
    if (!input.readBoolean() || generation <= 0L) return null
    val interactive = input.readBoolean()
    val selected = input.readInt()
    if (selected !in 0..8) return null
    val x = input.readDouble()
    val y = input.readDouble()
    val z = input.readDouble()
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
    fun stack(): PanelStack {
        val label = input.readUTF()
        val count = input.readInt()
        if (label.length > 96 || count < 0) throw IOException("Invalid panel stack")
        return PanelStack(label, count)
    }
    val held = stack()
    val slots = List(DUAL_INVENTORY_SLOTS) { stack() }
    return PanelInventory(generation, interactive, selected, kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt(),
        kotlin.math.floor(z).toInt(), held, slots, input.readInt())
}
