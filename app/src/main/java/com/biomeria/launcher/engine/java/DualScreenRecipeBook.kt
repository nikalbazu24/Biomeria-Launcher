package com.biomeria.launcher.engine.java

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

internal const val RECIPE_BOOK_MAGIC = 0x50524232

/** NEEDS_TABLE: unlocked, but it uses the 3×3 table and the player is at the 2×2 inventory grid. */
internal enum class PanelRecipeState { MANUAL, MISSING, READY, NEEDS_TABLE, COOKING }
internal data class PanelRecipe(
    val id: Int,
    val name: String,
    val resultCount: Int,
    val state: PanelRecipeState,
    /** The result's item id, for its icon. */
    val resultId: String = "",
    /** Newly unlocked and not looked at yet, as Minecraft's own book marks it. */
    val isNew: Boolean = false
)
internal data class PanelRecipeBook(
    val generation: Long,
    val modifiedAt: Long,
    val recipes: List<PanelRecipe>,
    val selectedId: Int?,
    val missing: List<PanelStack>,
    /** 3 at the crafting table, 2 at the inventory grid. */
    val gridSize: Int = 3,
    /** The selected recipe on a 3×3 grid, one item id per cell ("" when empty). */
    val pattern: List<String> = emptyList()
)

internal data class RecipeBookNavigation(
    val focusedId: Int? = null,
    val detailId: Int? = null,
    val searching: Boolean = false,
    val onlyCraftable: Boolean = false
) {
    fun focusedRecipe(results: List<PanelRecipe>): PanelRecipe? =
        results.firstOrNull { it.id == focusedId } ?: results.firstOrNull()

    fun move(results: List<PanelRecipe>, offset: Int): RecipeBookNavigation {
        val current = focusedRecipe(results) ?: return this
        val next = (results.indexOf(current) + offset).coerceIn(0, results.lastIndex)
        return copy(focusedId = results[next].id)
    }

    fun open(recipe: PanelRecipe) = copy(focusedId = recipe.id, detailId = recipe.id)

    fun back(): RecipeBookNavigation? = when {
        searching -> copy(searching = false)
        detailId != null -> copy(detailId = null)
        else -> null
    }
}

internal fun DualScreenBridge.readRecipeBook(generation: Long, previous: PanelRecipeBook?): PanelRecipeBook? {
    val file = File(directory, "recipes")
    val modifiedAt = file.lastModified()
    if (modifiedAt == 0L || file.length() !in 19L..1_400_000L) return null
    if (previous?.generation == generation && previous.modifiedAt == modifiedAt) return previous
    return try {
        DataInputStream(file.inputStream().buffered()).use { decodeRecipeBook(it, generation, modifiedAt) }
    } catch (_: IOException) {
        null
    }
}

internal fun decodeRecipeBook(input: DataInputStream, generation: Long, modifiedAt: Long): PanelRecipeBook? {
    if (input.readInt() != RECIPE_BOOK_MAGIC || input.readLong() != generation) return null
    val gridSize = input.readUnsignedByte()
    if (gridSize > 3) return null
    val count = input.readUnsignedShort()
    if (count > 4096) return null
    val ids = HashSet<Int>(count)
    val recipes = ArrayList<PanelRecipe>(count)
    repeat(count) {
        val id = input.readInt()
        val name = input.readUTF()
        val resultCount = input.readInt()
        val state = PanelRecipeState.entries.getOrNull(input.readUnsignedByte()) ?: return null
        val resultId = input.readUTF()
        val isNew = input.readBoolean()
        if (id < 0 || !ids.add(id) || name.isBlank() || name.length > 96 || resultCount <= 0 || resultId.length > 96) return null
        recipes += PanelRecipe(id, name, resultCount, state, resultId, isNew)
    }
    val selected = input.readInt()
    if (selected != -1 && selected !in ids) return null
    val missingCount = input.readUnsignedByte()
    if (missingCount > 9 || selected == -1 && missingCount > 0) return null
    val missing = ArrayList<PanelStack>(missingCount)
    repeat(missingCount) {
        val name = input.readUTF()
        val units = input.readUnsignedByte()
        if (name.length > 160 || units !in 1..9) return null
        missing += PanelStack(name, units)
    }
    val pattern = if (selected == -1) emptyList() else List(9) { input.readUTF().also { if (it.length > 96) return null } }
    return PanelRecipeBook(generation, modifiedAt, recipes, selected.takeIf { it >= 0 }, missing, gridSize, pattern)
}

private val recipeAccents = Regex("\\p{M}+")
private val recipeSpaces = Regex("\\s+")

internal fun panelSearchText(value: String): String =
    recipeAccents.replace(Normalizer.normalize(value, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

internal fun filterPanelRecipes(
    recipes: List<PanelRecipe>,
    query: String,
    onlyCraftable: Boolean
): List<PanelRecipe> {
    val words = panelSearchText(query).trim().split(recipeSpaces).filter(String::isNotEmpty)
    return recipes.filter { recipe ->
        (!onlyCraftable || recipe.state == PanelRecipeState.READY) &&
            (words.isEmpty() || panelSearchText(recipe.name).let { name -> words.all(name::contains) })
    }
}
