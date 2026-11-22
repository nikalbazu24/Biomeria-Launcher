package com.prissma.dualscreen;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads the unlocked client recipes and uses the same finder and placement packet as Minecraft. */
final class RecipeBookPanel {
    private static final int MAGIC = 0x50524231;
    private static final int MAX_RECIPES = 4096;
    private static final int GRID_SLOTS = 9;
    private static final int MANUAL = 0;
    private static final int MISSING = 1;
    private static final int READY = 2;

    private final Access game;
    private final Map<Integer, Recipe> recipes = new LinkedHashMap<>();
    private long generation = -1;
    private Object collections;
    private Object context;
    private int inventoryChanges;
    private List<Object> grid = List.of();
    private int selection = -1;
    private boolean needsWrite;

    private record Recipe(Object networkId, List<?> requirements, String name, int count) {}

    RecipeBookPanel(ClassLoader loader) throws ReflectiveOperationException {
        game = new Access(loader);
    }

    void inspect(int id) {
        if (id == selection || !recipes.containsKey(id)) return;
        selection = id;
        needsWrite = true;
    }

    void place(long generation, int id, Object player, Object world, Object handler, Object interactions, int syncId)
            throws ReflectiveOperationException {
        refreshCatalog(generation, player, world);
        Recipe recipe = recipes.get(id);
        if (recipe == null || recipe.requirements().isEmpty()) return;
        Object finder = newFinder(game.getInventory.invoke(player), handler);
        if (canMake(finder, recipe.requirements())) game.placeRecipe.invoke(interactions, syncId, recipe.networkId(), false);
    }

    void publish(Path destination, long generation, Object player, Object world, Object handler)
            throws ReflectiveOperationException, IOException {
        refreshCatalog(generation, player, world);
        Object inventory = game.getInventory.invoke(player);
        int change = (int) game.inventoryChanges.invoke(inventory);
        List<Object> currentGrid = readGrid(handler);
        // The inventory change counter doesn't include the independent 3×3 crafting slots.
        if (!needsWrite && change == inventoryChanges && sameGrid(currentGrid) && Files.isRegularFile(destination)) return;

        Object finder = newFinder(inventory, handler);
        var bytes = new ByteArrayOutputStream(8192);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeLong(generation);
            out.writeShort(recipes.size());
            for (var entry : recipes.entrySet()) {
                var recipe = entry.getValue();
                out.writeInt(entry.getKey());
                out.writeUTF(recipe.name());
                out.writeInt(recipe.count());
                int state = recipe.requirements().isEmpty() ? MANUAL : canMake(finder, recipe.requirements()) ? READY : MISSING;
                out.writeByte(state);
            }
            out.writeInt(selection);
            Recipe selected = recipes.get(selection);
            Map<String, Integer> missing = selected == null ? Map.of() : missing(finder, selected.requirements());
            out.writeByte(missing.size());
            for (var ingredient : missing.entrySet()) {
                out.writeUTF(ingredient.getKey());
                out.writeByte(ingredient.getValue());
            }
        }
        List<Object> savedGrid = new ArrayList<>(GRID_SLOTS);
        for (Object stack : currentGrid) savedGrid.add(game.copyStack.invoke(stack));
        GamePanelBridge.writeAtomically(destination, bytes.toByteArray());
        grid = savedGrid;
        inventoryChanges = change;
        needsWrite = false;
    }

    private void refreshCatalog(long currentGeneration, Object player, Object world) throws ReflectiveOperationException {
        Object book = game.getBook.invoke(player);
        List<?> ordered = (List<?>) game.orderedRecipes.invoke(book);
        if (generation == currentGeneration && collections == ordered) return;
        if (generation != currentGeneration) selection = -1;
        generation = currentGeneration;
        context = game.displayContext.invoke(null, world);
        recipes.clear();
        for (Object collection : ordered) {
            for (Object entry : (List<?>) game.collectionRecipes.invoke(collection)) {
                if (recipes.size() == MAX_RECIPES) break;
                Object display = game.recipeDisplay.invoke(entry);
                if (!fitsTable(display)) continue;

                Object id = game.recipeId.invoke(entry);
                int index = (int) game.networkIndex.invoke(id);
                if (index < 0 || recipes.containsKey(index)) continue;
                List<?> results = (List<?>) game.resultStacks.invoke(entry, context);
                if (results.isEmpty()) continue;
                Object result = results.get(0);
                if ((boolean) game.emptyStack.invoke(result)) continue;
                int count = (int) game.stackCount.invoke(result);
                String name = itemName(result);
                if (name.isBlank()) continue;
                List<?> requirements = (List<?>) ((Optional<?>) game.recipeRequirements.invoke(entry)).orElse(null);
                if (requirements == null || requirements.size() > GRID_SLOTS) requirements = List.of();
                recipes.put(index, new Recipe(id, requirements, name, count));
            }
            if (recipes.size() == MAX_RECIPES) break;
        }
        if (!recipes.containsKey(selection)) selection = -1;
        collections = ordered;
        needsWrite = true;
    }

    private boolean fitsTable(Object display) throws ReflectiveOperationException {
        if (game.shaped.isInstance(display)) {
            return (int) game.width.invoke(display) <= 3 && (int) game.height.invoke(display) <= 3;
        }
        return game.shapeless.isInstance(display) && ((List<?>) game.ingredients.invoke(display)).size() <= GRID_SLOTS;
    }

    private List<Object> readGrid(Object handler) throws ReflectiveOperationException {
        List<Object> stacks = new ArrayList<>(GRID_SLOTS);
        for (Object slot : (List<?>) game.inputSlots.invoke(handler)) stacks.add(game.slotStack.invoke(slot));
        return stacks;
    }

    private boolean sameGrid(List<Object> current) throws ReflectiveOperationException {
        if (current.size() != grid.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            if (!(boolean) game.equalStacks.invoke(null, current.get(i), grid.get(i))) return false;
        }
        return true;
    }

    private Object newFinder(Object inventory, Object handler) throws ReflectiveOperationException {
        Object finder = game.finder.newInstance();
        game.addInventory.invoke(inventory, finder);
        game.addGrid.invoke(handler, finder);
        return finder;
    }

    private boolean canMake(Object finder, List<?> requirements) throws ReflectiveOperationException {
        return (boolean) game.canCraft.invoke(finder, requirements, null);
    }

    private Map<String, Integer> missing(Object finder, List<?> requirements) throws ReflectiveOperationException {
        Map<String, Integer> result = new LinkedHashMap<>();
        List<Object> matched = new ArrayList<>();
        for (Object ingredient : requirements) {
            matched.add(ingredient);
            // Let Minecraft reassign interchangeable items instead of counting each ingredient separately.
            if (!canMake(finder, matched)) {
                matched.remove(matched.size() - 1);
                result.merge(ingredientName(ingredient), 1, Integer::sum);
            }
        }
        return result;
    }

    private String ingredientName(Object ingredient) throws ReflectiveOperationException {
        Object display = game.ingredientDisplay.invoke(ingredient);
        List<?> options = (List<?>) game.displayStacks.invoke(display, context);
        var names = new LinkedHashSet<String>();
        boolean more = false;
        for (Object item : options) {
            if ((boolean) game.emptyStack.invoke(item)) continue;
            String name = itemName(item);
            if (names.size() == 3 && !names.contains(name)) {
                more = true;
                break;
            }
            names.add(name);
        }
        if (names.isEmpty()) return "";
        String label = String.join(" / ", names) + (more ? " / …" : "");
        return label.substring(0, Math.min(label.length(), 160));
    }

    private String itemName(Object stack) throws ReflectiveOperationException {
        String name = (String) game.textString.invoke(game.stackName.invoke(stack));
        return name.substring(0, Math.min(name.length(), 96));
    }

    /** Intermediary names for Fabric 1.21.4. No dependency on Mojang classes at APK build time. */
    private static final class Access {
        final Class<?> shaped, shapeless;
        final Constructor<?> finder;
        final Method getBook, orderedRecipes, collectionRecipes, recipeDisplay, recipeId, networkIndex, resultStacks;
        final Method recipeRequirements, displayContext, getInventory, inventoryChanges, inputSlots, slotStack;
        final Method emptyStack, copyStack, equalStacks, stackName, stackCount, textString;
        final Method addInventory, addGrid, canCraft, placeRecipe, ingredientDisplay, displayStacks, width, height, ingredients;

        Access(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> stack = type(loader, 1799);
            Class<?> inventory = type(loader, 1661);
            Class<?> entry = type(loader, 10297);
            Class<?> networkId = type(loader, 10298);
            Class<?> context = type(loader, 10352);
            Class<?> finderType = type(loader, 9875);
            shaped = type(loader, 10300);
            shapeless = type(loader, 10301);
            width = shaped.getMethod("comp_3268");
            height = shaped.getMethod("comp_3269");
            ingredients = shapeless.getMethod("comp_3271");
            finder = finderType.getConstructor();
            getBook = type(loader, 746).getMethod("method_3130");
            orderedRecipes = type(loader, 299).getMethod("method_1393");
            collectionRecipes = type(loader, 516).getMethod("method_2650");
            recipeDisplay = entry.getMethod("comp_3263");
            recipeId = entry.getMethod("comp_3262");
            networkIndex = networkId.getMethod("comp_3267");
            resultStacks = entry.getMethod("method_64730", context);
            recipeRequirements = entry.getMethod("comp_3266");
            displayContext = type(loader, 10363).getMethod("method_65008", type(loader, 1937));
            getInventory = type(loader, 1657).getMethod("method_31548");
            inventoryChanges = inventory.getMethod("method_7364");
            inputSlots = type(loader, 9884).getMethod("method_61628");
            slotStack = type(loader, 1735).getMethod("method_7677");
            emptyStack = stack.getMethod("method_7960");
            copyStack = stack.getMethod("method_7972");
            equalStacks = stack.getMethod("method_7973", stack, stack);
            stackName = stack.getMethod("method_7964");
            stackCount = stack.getMethod("method_7947");
            textString = type(loader, 2561).getMethod("getString");
            addInventory = inventory.getMethod("method_7387", finderType);
            addGrid = type(loader, 1729).getMethod("method_7654", finderType);
            canCraft = finderType.getMethod("method_64645", List.class, loader.loadClass("net.minecraft.class_1662$class_9874"));
            placeRecipe = type(loader, 636).getMethod("method_2912", int.class, networkId, boolean.class);
            ingredientDisplay = type(loader, 1856).getMethod("method_64673");
            displayStacks = type(loader, 10302).getMethod("method_64738", context);
        }

        private static Class<?> type(ClassLoader loader, int id) throws ClassNotFoundException {
            return loader.loadClass("net.minecraft.class_" + id);
        }
    }
}
