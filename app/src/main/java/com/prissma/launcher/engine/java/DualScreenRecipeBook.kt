package com.prissma.launcher.engine.java

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale

internal const val RECIPE_BOOK_MAGIC = 0x50524231

internal enum class PanelRecipeState { MANUAL, MISSING, READY }
internal data class PanelRecipe(val id: Int, val name: String, val resultCount: Int, val state: PanelRecipeState)
internal data class PanelRecipeBook(
    val generation: Long,
    val modifiedAt: Long,
    val recipes: List<PanelRecipe>,
    val selectedId: Int?,
    val missing: List<PanelStack>
)

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
    val count = input.readUnsignedShort()
    if (count > 4096) return null
    val ids = HashSet<Int>(count)
    val recipes = ArrayList<PanelRecipe>(count)
    repeat(count) {
        val id = input.readInt()
        val name = input.readUTF()
        val resultCount = input.readInt()
        val state = PanelRecipeState.entries.getOrNull(input.readUnsignedByte()) ?: return null
        if (id < 0 || !ids.add(id) || name.isBlank() || name.length > 96 || resultCount <= 0) return null
        recipes += PanelRecipe(id, name, resultCount, state)
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
    return PanelRecipeBook(generation, modifiedAt, recipes, selected.takeIf { it >= 0 }, missing)
}

private val recipeAccents = Regex("\\p{M}+")
private val recipeSpaces = Regex("\\s+")

private fun recipeSearchText(value: String): String =
    recipeAccents.replace(Normalizer.normalize(value, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

internal fun filterPanelRecipes(
    recipes: List<PanelRecipe>,
    query: String,
    onlyCraftable: Boolean
): List<PanelRecipe> {
    val words = recipeSearchText(query).trim().split(recipeSpaces).filter(String::isNotEmpty)
    return recipes.filter { recipe ->
        (!onlyCraftable || recipe.state == PanelRecipeState.READY) &&
            (words.isEmpty() || recipeSearchText(recipe.name).let { name -> words.all(name::contains) })
    }
}
