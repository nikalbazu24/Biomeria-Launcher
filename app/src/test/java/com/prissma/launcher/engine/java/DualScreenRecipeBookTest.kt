package com.prissma.launcher.engine.java

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class DualScreenRecipeBookTest {
    private val chest = PanelRecipe(71, "Cofre de roble", 1, PanelRecipeState.MISSING)
    private val plank = PanelRecipe(93, "Tablón de abedul", 4, PanelRecipeState.READY)
    private val manual = PanelRecipe(105, "Patrón especial", 1, PanelRecipeState.MANUAL)

    @Test
    fun readsCatalogStatusAndGroupedMissingIngredients() {
        val missing = listOf(PanelStack("Tablones de roble / Tablones de abeto", 4))
        val result = requireNotNull(fixture(listOf(chest, plank), chest.id, missing))
        assertEquals(27L, result.generation)
        assertEquals(listOf(chest, plank), result.recipes)
        assertEquals(chest.id, result.selectedId)
        assertEquals(missing, result.missing)
    }

    @Test
    fun rejectsCatalogsFromPreviousTablesAndInvalidDetails() {
        assertNull(fixture(listOf(chest), generation = 26))
        assertNull(fixture(listOf(chest, chest)))
        assertNull(fixture(listOf(chest), selectedId = plank.id))
        assertNull(fixture(listOf(chest), missing = listOf(PanelStack("Roble", 4))))
        assertNull(fixture(listOf(chest), selectedId = chest.id, missing = listOf(PanelStack("Roble", 10))))
    }

    @Test
    fun searchesMultipleWordsIgnoringAccentsAndCanLimitToReadyRecipes() {
        val recipes = listOf(chest, plank, manual)
        assertEquals(listOf(plank), filterPanelRecipes(recipes, " abedul  TABLON ", onlyCraftable = false))
        assertEquals(listOf(chest), filterPanelRecipes(recipes, "roble cofre", onlyCraftable = false))
        assertTrue(filterPanelRecipes(recipes, "cofre", onlyCraftable = true).isEmpty())
        assertEquals(listOf(plank), filterPanelRecipes(recipes, "", onlyCraftable = true))
    }

    private fun fixture(
        recipes: List<PanelRecipe>,
        selectedId: Int = -1,
        missing: List<PanelStack> = emptyList(),
        generation: Long = 27L
    ): PanelRecipeBook? {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(RECIPE_BOOK_MAGIC)
            out.writeLong(generation)
            out.writeShort(recipes.size)
            for (recipe in recipes) {
                out.writeInt(recipe.id)
                out.writeUTF(recipe.name)
                out.writeInt(recipe.resultCount)
                out.writeByte(recipe.state.ordinal)
            }
            out.writeInt(selectedId)
            out.writeByte(missing.size)
            for (ingredient in missing) {
                out.writeUTF(ingredient.name)
                out.writeByte(ingredient.count)
            }
        }
        return DataInputStream(bytes.toByteArray().inputStream()).use { decodeRecipeBook(it, 27L, 10L) }
    }
}
