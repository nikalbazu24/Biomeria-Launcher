package com.biomeria.launcher.engine.java

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal const val DUAL_SNAPSHOT_MAGIC = 0x50525336
internal const val DUAL_MAP_SIZE = 128
internal const val DUAL_SCREEN_MOD = "DUAL_SCREEN_MOD"
private const val SNAPSHOT_MAX_AGE_MS = 1800L
private const val SNAPSHOT_CLOCK_TOLERANCE_MS = 150L
private const val SNAPSHOT_GAP_GRACE_MS = 400L

internal fun supportsDualInventory(version: String, loader: String?) = version == "1.21.4" && loader == "fabric"

internal enum class PanelPage(val label: String, val canStart: Boolean = true) {
    INVENTORY("Inventario"), CRAFT("Crafteo"), MAP("Mapa"), STORAGE("Contenedor", false), OPTIONS("Opciones", false);

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.canStart && it.name == value } ?: INVENTORY
    }
}

internal enum class PanelMapSource(val label: String) {
    BIOMERIA("Mapa de Biomeria"), MINECRAFT("Mapa equipado");

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: BIOMERIA
    }
}

internal enum class PanelLayout(
    val slotCount: Int,
    val inventoryStart: Int,
    val hotbarStart: Int,
    val menuPage: PanelPage?,
    val chestRows: Int = 0,
    /** Slots per container row: 9 for chests, 5 for the hopper, 3 for the dispenser. */
    val containerColumns: Int = 9
) {
    PERSONAL(46, 9, 36, null),
    CRAFTING_TABLE(46, 10, 37, PanelPage.CRAFT),
    CHEST(63, 27, 54, PanelPage.STORAGE, chestRows = 3),
    DOUBLE_CHEST(90, 54, 81, PanelPage.STORAGE, chestRows = 6),
    FURNACE(39, 3, 30, PanelPage.STORAGE),
    HOPPER(41, 5, 32, PanelPage.STORAGE, chestRows = 1, containerColumns = 5),
    DISPENSER(45, 9, 36, PanelPage.STORAGE, chestRows = 3, containerColumns = 3);

    val craftSize get() = if (this == CRAFTING_TABLE) 3 else 2

    fun accepts(command: PanelCommand, slot: Int): Boolean = when (command) {
        PanelCommand.PICK, PanelCommand.SPLIT, PanelCommand.QUICK_MOVE, PanelCommand.THROW -> slot in 0 until slotCount
        PanelCommand.SELECT_HOTBAR -> this == PERSONAL && slot in 0..8
        PanelCommand.CLOSE_MENU -> menuPage != null && slot == 0
        PanelCommand.INSPECT_RECIPE, PanelCommand.PLACE_RECIPE, PanelCommand.PLACE_ALL -> (this == CRAFTING_TABLE || this == PERSONAL) && slot >= 0
    }
}

/** One slot. [id] is the item id (minecraft:torch) when the Biomeria mod could read it. */
internal data class PanelStack(val name: String, val count: Int, val id: String = "")
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
    val recipeBookAvailable: Boolean,
    val menuTitle: String,
    val furnace: PanelFurnace?
)

internal data class PanelSnapshot(val inventory: PanelInventory?)

internal class PanelSnapshotHistory {
    private var lastInventory: PanelInventory? = null
    private var lastRead = 0L

    fun update(snapshot: PanelSnapshot?, elapsedMillis: Long): PanelInventory? {
        if (snapshot != null) {
            lastInventory = snapshot.inventory
            lastRead = elapsedMillis
        } else if (elapsedMillis - lastRead >= SNAPSHOT_GAP_GRACE_MS) {
            lastInventory = null
        }
        return lastInventory
    }
}

internal enum class PanelCommand(val wireName: String) {
    PICK("pick"), SPLIT("split"), QUICK_MOVE("quick"), SELECT_HOTBAR("hotbar"),
    CLOSE_MENU("close"), INSPECT_RECIPE("inspect"), PLACE_RECIPE("recipe"),
    /** Drops one item from the slot. */
    THROW("throw"),
    /** Fills the grid with as many sets of the recipe as the inventory allows. */
    PLACE_ALL("recipeall")
}

internal class DualScreenBridge(val directory: File, val terrainDirectory: File? = null) {
    private val sequence = AtomicLong()

    fun readInventory(nowMillis: Long? = null): PanelInventory? = readSnapshot(nowMillis)?.inventory

    /** Null is an unavailable read. A valid snapshot with empty inventory means the world closed. */
    fun readSnapshot(nowMillis: Long? = null): PanelSnapshot? {
        val file = File(directory, "snapshot")
        val stamp = file.lastModified()
        val age = (nowMillis ?: System.currentTimeMillis()) - stamp
        if (stamp == 0L || age !in -SNAPSHOT_CLOCK_TOLERANCE_MS..SNAPSHOT_MAX_AGE_MS || file.length() !in 13L..65_536L) {
            return null
        }
        return try {
            DataInputStream(file.inputStream().buffered()).use(::decodePanelSnapshot)
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
        if (command == PanelCommand.INSPECT_RECIPE && !now.recipeBookAvailable) return false
        if (command == PanelCommand.PLACE_RECIPE && (!now.recipeBookAvailable || now.held.count != 0)) return false
        if (inventory.layout.menuPage != null && !File(directory, "routed-menu").isFile) return false
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

    fun prepareMenus(mask: Int) = setGenerationMarker("menu-policy", mask.toLong().takeIf { it != 0L })

    fun viewRecipes(generation: Long?) = setGenerationMarker("recipes-open", generation)

    /** Asks the mod to hand advancements to Biomeria instead of showing Minecraft's toast. */
    fun celebrateAchievements(enabled: Boolean) = setGenerationMarker("achievements-enabled", if (enabled) 1L else null)

    fun collectEntities(enabled: Boolean) = setGenerationMarker("entities-enabled", if (enabled) 1L else null)

    fun collectTerrain(tiles: Boolean, position: Boolean) {
        setGenerationMarker("terrain-enabled", if (tiles && terrainDirectory != null) 1L else null)
        setGenerationMarker("terrain-position", if (position && terrainDirectory != null) 1L else null)
    }

    /** GUI scale Minecraft is drawing with, published by the mod; null before the first tick. */
    fun guiScale(): Int? = runCatching { File(directory, "gui-scale").readText(Charsets.US_ASCII).trim().toInt() }
        .getOrNull()?.takeIf { it in 1..16 }

    /** Turns Biomeria's pause menu on or off; the mod then hides Minecraft's. */
    fun useBiomeriaMenus(enabled: Boolean) = setGenerationMarker("biomeria-menus", if (enabled) 1L else null)

    /** Minecraft's pause screen is open (and hidden): Android should show its own. */
    fun pauseOpen(): Boolean = File(directory, "pause-open").isFile

    /** Shows Minecraft's own pause screen this time. */
    fun showVanillaPause(): Boolean = writeMarker("vanilla-pause", "1")

    /** Asks the mod to reload options.txt after Biomeria changed it; the answer comes in [optionsApplied]. */
    fun reloadOptions(): Boolean {
        File(directory, "options-result").delete()
        return writeMarker("options-reload", "1")
    }

    /** null while waiting, true when the game applied the options, false when they apply on restart. */
    fun optionsApplied(): Boolean? = runCatching { File(directory, "options-result").readText().trim() == "1" }.getOrNull()

    /** True while Minecraft shows a screen with item slots (inventory, chest, crafting table…). */
    fun slotScreen(): Boolean = File(directory, "slot-screen").isFile

    /** Swaps two hotbar slots (0-8) when no menu is open. */
    fun swapHotbar(from: Int, to: Int): Boolean = from in 0..8 && to in 0..8 && from != to &&
        writeMarker("hotbar-swap", "$from $to")

    /** Asks Minecraft to save the world and close, like closing its window. */
    fun requestQuit(): Boolean = writeMarker("quit", "1")

    fun requestInternetWorld(token: UUID): Boolean = writeMarker("internet-open", token.toString())

    fun stopInternetWorld() {
        File(directory, "internet-open").delete()
    }

    fun readInternetWorld(token: UUID): Int? {
        val file = File(directory, "internet-world")
        val stamp = file.lastModified()
        if (stamp == 0L || System.currentTimeMillis() - stamp !in -150L..3500L || file.length() !in 38L..42L) {
            return null
        }
        return try {
            val value = file.readText(Charsets.US_ASCII)
            if (value.substringBefore(' ') != token.toString()) null
            else value.substringAfter(' ', "").toIntOrNull()?.takeIf { it in -1..65535 }
        } catch (_: IOException) {
            null
        }
    }

    private fun setGenerationMarker(name: String, generation: Long?) {
        if (generation == null) {
            File(directory, name).delete()
            return
        }
        writeMarker(name, generation.toString())
    }

    private fun writeMarker(name: String, value: String): Boolean {
        val marker = File(directory, name)
        val staged = File(directory, "$name.part")
        return try {
            staged.writeText(value, Charsets.US_ASCII)
            Files.move(staged.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: IOException) {
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
            routeMenu(null)
            prepareMenus(0)
            viewRecipes(null)
            directory.listFiles()?.filter { it.name.startsWith("command-") }?.forEach { it.delete() }
        }
    }

    companion object {
        fun create(cache: File, terrainDirectory: File? = null): DualScreenBridge =
            DualScreenBridge(Files.createTempDirectory(cache.toPath(), "dual-screen-").toFile(), terrainDirectory)
    }
}

private fun decodePanelSnapshot(input: DataInputStream): PanelSnapshot? {
    if (input.readInt() != DUAL_SNAPSHOT_MAGIC) return null
    val generation = input.readLong()
    if (!input.readBoolean()) return if (generation >= 0L && input.read() == -1) PanelSnapshot(null) else null
    if (generation <= 0L) return null
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
    val recipeBookAvailable = input.readBoolean() && (layout == PanelLayout.CRAFTING_TABLE || layout == PanelLayout.PERSONAL)
    val menuTitle = label()
    val furnace = if (layout == PanelLayout.FURNACE) {
        val cook = input.readFloat()
        val fuel = input.readFloat()
        if (!cook.isFinite() || !fuel.isFinite()) return null
        PanelFurnace(cook.coerceIn(0f, 1f), fuel.coerceIn(0f, 1f), input.readBoolean())
    } else null
    // Optional trailer with item ids: held stack first, then every slot.
    val ids = if (input.available() > 0) {
        val count = input.readUnsignedShort()
        if (count != layout.slotCount + 1) return null
        List(count) { input.readUTF().takeIf { it.length <= 128 } ?: throw IOException("Invalid item id") }
    } else null
    fun withId(stack: PanelStack, index: Int) = ids?.getOrNull(index)?.let { stack.copy(id = it) } ?: stack
    return PanelSnapshot(PanelInventory(generation, interactive, layout, selected,
        kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt(), kotlin.math.floor(z).toInt(),
        withId(held, 0), slots.mapIndexed { index, stack -> withId(stack, index + 1) }, mapRevision, recipeBookAvailable,
        menuTitle, furnace))
}
