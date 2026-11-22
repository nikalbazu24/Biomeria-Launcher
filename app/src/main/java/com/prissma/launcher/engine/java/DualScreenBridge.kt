package com.prissma.launcher.engine.java

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal const val DUAL_SNAPSHOT_MAGIC = 0x50525335
internal const val DUAL_MAP_SIZE = 128
internal const val DUAL_SCREEN_MOD = "DUAL_SCREEN_MOD"

internal fun supportsDualInventory(version: String, loader: String?) = version == "1.21.4" && loader == "fabric"

internal enum class PanelPage(val label: String, val canStart: Boolean = true) {
    INVENTORY("Inventario"), CRAFT("Crafteo"), MAP("Mapa"), STORAGE("Contenedor", false), OPTIONS("Opciones", false);

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.canStart && it.name == value } ?: INVENTORY
    }
}

internal enum class PanelMapSource(val label: String) {
    MINECRAFT("Minecraft"), XAERO("Xaero’s");

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: MINECRAFT
    }
}

internal enum class PanelLayout(
    val slotCount: Int,
    val inventoryStart: Int,
    val hotbarStart: Int,
    val menuPage: PanelPage?,
    val chestRows: Int = 0
) {
    PERSONAL(46, 9, 36, null),
    CRAFTING_TABLE(46, 10, 37, PanelPage.CRAFT),
    CHEST(63, 27, 54, PanelPage.STORAGE, chestRows = 3),
    DOUBLE_CHEST(90, 54, 81, PanelPage.STORAGE, chestRows = 6),
    FURNACE(39, 3, 30, PanelPage.STORAGE),
    XAERO(0, 0, 0, PanelPage.MAP);

    val craftSize get() = if (this == CRAFTING_TABLE) 3 else 2

    fun accepts(command: PanelCommand, slot: Int): Boolean = when (command) {
        PanelCommand.PICK, PanelCommand.SPLIT, PanelCommand.QUICK_MOVE -> slot in 0 until slotCount
        PanelCommand.SELECT_HOTBAR -> this == PERSONAL && slot in 0..8
        PanelCommand.CLOSE_MENU -> menuPage != null && slot == 0
        PanelCommand.OPEN_XAERO -> this == PERSONAL && slot == 0
        PanelCommand.INSPECT_RECIPE, PanelCommand.PLACE_RECIPE -> this == CRAFTING_TABLE && slot >= 0
        PanelCommand.MAP_DOWN, PanelCommand.MAP_MOVE, PanelCommand.MAP_UP -> this == XAERO && slot in 0..0xffffff
        PanelCommand.MAP_ZOOM -> this == XAERO && (slot == -1 || slot == 1)
    }
}

internal data class PanelStack(val name: String, val count: Int)
internal data class PanelWaypoint(val name: String, val x: Int, val y: Int, val z: Int)
internal data class PanelFurnace(val cook: Float, val fuel: Float, val burning: Boolean)
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
    val waypoints: List<PanelWaypoint>,
    val recipeBookAvailable: Boolean,
    val menuTitle: String,
    val furnace: PanelFurnace?,
    val xaeroLowerAvailable: Boolean
)

internal enum class PanelCommand(val wireName: String) {
    PICK("pick"), SPLIT("split"), QUICK_MOVE("quick"), SELECT_HOTBAR("hotbar"),
    CLOSE_MENU("close"), OPEN_XAERO("xaero"), INSPECT_RECIPE("inspect"), PLACE_RECIPE("recipe"),
    MAP_DOWN("map-down"), MAP_MOVE("map-move"), MAP_UP("map-up"), MAP_ZOOM("map-zoom")
}

internal class DualScreenBridge(val directory: File) {
    private val sequence = AtomicLong()

    fun readInventory(nowMillis: Long = System.currentTimeMillis()): PanelInventory? {
        val file = File(directory, "snapshot")
        if (!file.isFile || nowMillis - file.lastModified() !in 0L..1800L || file.length() > 65_536L) return null
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
        if (command == PanelCommand.INSPECT_RECIPE && !now.recipeBookAvailable) return false
        if (command == PanelCommand.PLACE_RECIPE && (!now.recipeBookAvailable || now.held.count != 0)) return false
        if (inventory.layout.menuPage != null && !File(directory, "routed-menu").isFile) return false
        if (inventory.layout == PanelLayout.XAERO && !now.xaeroLowerAvailable) return false
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

    fun routeMenu(generation: Long?) = setGenerationMarker("routed-menu", generation)

    fun viewRecipes(generation: Long?) = setGenerationMarker("recipes-open", generation)

    private fun setGenerationMarker(name: String, generation: Long?) {
        val marker = File(directory, name)
        if (generation == null) {
            marker.delete()
            return
        }
        val staged = File(directory, "$name.part")
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
            routeMenu(null)
            viewRecipes(null)
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
    val slots = List(layout.slotCount) { stack() }
    val mapRevision = input.readInt()
    val xaeroWorldMap = input.readBoolean()
    val xaeroMinimap = input.readBoolean()
    val waypointCount = input.readUnsignedByte()
    if (waypointCount > 24 || !xaeroMinimap && waypointCount > 0) return null
    val waypoints = List(waypointCount) { PanelWaypoint(label(), input.readInt(), input.readInt(), input.readInt()) }
    val recipeBookAvailable = input.readBoolean() && layout == PanelLayout.CRAFTING_TABLE
    val menuTitle = label()
    val furnace = if (layout == PanelLayout.FURNACE) {
        val cook = input.readFloat()
        val fuel = input.readFloat()
        if (!cook.isFinite() || !fuel.isFinite()) return null
        PanelFurnace(cook.coerceIn(0f, 1f), fuel.coerceIn(0f, 1f), input.readBoolean())
    } else null
    val xaeroLowerAvailable = input.readBoolean()
    return PanelInventory(generation, interactive, layout, selected, kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt(),
        kotlin.math.floor(z).toInt(), held, slots, mapRevision, xaeroWorldMap, xaeroMinimap, waypoints, recipeBookAvailable,
        menuTitle, furnace, xaeroLowerAvailable)
}
