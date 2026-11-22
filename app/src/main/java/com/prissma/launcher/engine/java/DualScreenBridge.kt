package com.prissma.launcher.engine.java

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal const val DUAL_SNAPSHOT_MAGIC = 0x50525333
internal const val DUAL_INVENTORY_SLOTS = 46
internal const val DUAL_MAP_SIZE = 128
internal const val DUAL_SCREEN_MOD = "DUAL_SCREEN_MOD"

internal fun supportsDualInventory(version: String, loader: String?) = version == "1.21.4" && loader == "fabric"

internal enum class PanelPage(val label: String) {
    INVENTORY("Inventario"), CRAFT("Crafteo"), MAP("Mapa"), OPTIONS("Opciones");

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it != OPTIONS && it.name == value } ?: INVENTORY
    }
}

internal enum class PanelMapSource(val label: String) {
    MINECRAFT("Minecraft"), XAERO("Xaero’s");

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: MINECRAFT
    }
}

internal enum class PanelLayout(val craftSize: Int, val inventoryStart: Int, val hotbarStart: Int) {
    PERSONAL(2, 9, 36), CRAFTING_TABLE(3, 10, 37);

    fun accepts(command: PanelCommand, slot: Int): Boolean = when (command) {
        PanelCommand.PICK, PanelCommand.SPLIT, PanelCommand.QUICK_MOVE -> slot in 0 until DUAL_INVENTORY_SLOTS
        PanelCommand.SELECT_HOTBAR -> this == PERSONAL && slot in 0..8
        PanelCommand.CLOSE_TABLE -> this == CRAFTING_TABLE && slot == 0
        PanelCommand.OPEN_XAERO -> this == PERSONAL && slot == 0
    }
}

internal data class PanelStack(val name: String, val count: Int)
internal data class PanelWaypoint(val name: String, val x: Int, val y: Int, val z: Int)
internal data class PanelInventory(
    val generation: Long,
    val interactive: Boolean,
    val layout: PanelLayout,
    val selectedSlot: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val held: PanelStack,
    val slots: List<PanelStack>,
    val mapRevision: Int,
    val xaeroWorldMap: Boolean,
    val xaeroMinimap: Boolean,
    val waypoints: List<PanelWaypoint>
)

internal enum class PanelCommand(val wireName: String) {
    PICK("pick"), SPLIT("split"), QUICK_MOVE("quick"), SELECT_HOTBAR("hotbar"),
    CLOSE_TABLE("close"), OPEN_XAERO("xaero")
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

    fun send(inventory: PanelInventory, command: PanelCommand, slot: Int = 0): Boolean {
        if (!inventory.interactive || !inventory.layout.accepts(command, slot) || !File(directory, "visible").isFile) return false
        val now = readInventory() ?: return false
        if (!now.interactive || now.generation != inventory.generation || now.layout != inventory.layout) return false
        if (command == PanelCommand.OPEN_XAERO && !now.xaeroWorldMap) return false
        if (inventory.layout == PanelLayout.CRAFTING_TABLE && !File(directory, "crafting").isFile) return false
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

    fun routeCrafting(generation: Long?) {
        val marker = File(directory, "crafting")
        if (generation == null) {
            marker.delete()
            return
        }
        val staged = File(directory, "crafting.part")
        try {
            staged.writeText(generation.toString(), Charsets.US_ASCII)
            Files.move(staged.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // The secondary screen can detach while the session is closing.
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
            routeCrafting(null)
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
    val layout = PanelLayout.entries.getOrNull(input.readUnsignedByte()) ?: return null
    val selected = input.readInt()
    if (selected !in 0..8) return null
    val x = input.readDouble()
    val y = input.readDouble()
    val z = input.readDouble()
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
    fun label(): String {
        val label = input.readUTF()
        if (label.length > 96) throw IOException("Invalid panel label")
        return label
    }
    fun stack(): PanelStack {
        val label = label()
        val count = input.readInt()
        if (count < 0) throw IOException("Invalid panel stack")
        return PanelStack(label, count)
    }
    val held = stack()
    val slots = List(DUAL_INVENTORY_SLOTS) { stack() }
    val mapRevision = input.readInt()
    val xaeroWorldMap = input.readBoolean()
    val xaeroMinimap = input.readBoolean()
    val waypointCount = input.readUnsignedByte()
    if (waypointCount > 24 || !xaeroMinimap && waypointCount > 0) return null
    val waypoints = List(waypointCount) { PanelWaypoint(label(), input.readInt(), input.readInt(), input.readInt()) }
    return PanelInventory(generation, interactive, layout, selected, kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt(),
        kotlin.math.floor(z).toInt(), held, slots, mapRevision, xaeroWorldMap, xaeroMinimap, waypoints)
}
