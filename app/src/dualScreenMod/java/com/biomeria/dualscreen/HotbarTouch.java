package com.biomeria.dualscreen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Touch hotbar for one screen: tells Android the GUI scale (so it knows where the hotbar is drawn)
 * and swaps two hotbar slots when the player drags an item from one to the other.
 */
final class HotbarTouch {
    private static boolean failed;
    private static Method getWindow, scaleFactor, clickSlot;
    private static Field player, interactions, currentScreen, playerHandler, currentHandler, syncId;
    private static Object swap;
    private static Class<?> handledScreen;
    private static boolean slotScreenWritten;
    private static int writtenScale = -1;
    private static int ticks;

    private HotbarTouch() {}

    static void tick(Object client, Path root) {
        if (failed || ++ticks % 2 != 0) return;
        try {
            if (getWindow == null) init(client.getClass().getClassLoader());
            int scale = (int) Math.round((double) scaleFactor.invoke(getWindow.invoke(client)));
            if (scale != writtenScale) {
                Path staged = root.resolve("gui-scale.part");
                Files.writeString(staged, Integer.toString(scale), StandardCharsets.US_ASCII);
                Files.move(staged, root.resolve("gui-scale"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                writtenScale = scale;
            }
            // Tells Android when an inventory-like screen (with slots) is open, for drag and drop.
            Object screen = currentScreen.get(client);
            boolean slots = screen != null && handledScreen.isInstance(screen);
            if (slots != slotScreenWritten) {
                Path marker = root.resolve("slot-screen");
                if (slots) Files.writeString(marker, "1", StandardCharsets.US_ASCII); else Files.deleteIfExists(marker);
                slotScreenWritten = slots;
            }
            Path request = root.resolve("hotbar-swap");
            if (!Files.isRegularFile(request)) return;
            String text = Files.size(request) <= 16 ? Files.readString(request, StandardCharsets.US_ASCII).trim() : "";
            Files.deleteIfExists(request);
            String[] parts = text.split(" ");
            if (parts.length != 2) return;
            int from = Integer.parseInt(parts[0]);
            int to = Integer.parseInt(parts[1]);
            if (from < 0 || from > 8 || to < 0 || to > 8 || from == to) return;
            Object entity = player.get(client);
            Object manager = interactions.get(client);
            if (entity == null || manager == null || currentScreen.get(client) != null) return;
            Object handler = playerHandler.get(entity);
            if (handler != currentHandler.get(entity)) return;
            // In the player's own menu the hotbar is slots 36-44; SWAP moves the slot into hotbar index "to".
            clickSlot.invoke(manager, syncId.getInt(handler), 36 + from, to, swap, entity);
        } catch (NumberFormatException ignored) {
            // A malformed request changes nothing.
        } catch (Exception | LinkageError error) {
            failed = true;
            System.err.println("[BIOMERIA] Barra táctil no disponible: " + error);
        }
    }

    private static void init(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> client = type(loader, 310);
        Class<?> window = type(loader, 1041);
        Class<?> playerEntity = type(loader, 1657);
        Class<?> handler = type(loader, 1703);
        Class<?> action = type(loader, 1713);
        handledScreen = type(loader, 465);
        getWindow = client.getMethod("method_22683");
        scaleFactor = window.getMethod("method_4495");
        player = field(client, "field_1724");
        interactions = field(client, "field_1761");
        currentScreen = field(client, "field_1755");
        playerHandler = field(playerEntity, "field_7498");
        currentHandler = field(playerEntity, "field_7512");
        syncId = field(handler, "field_7763");
        clickSlot = type(loader, 636).getMethod("method_2906", int.class, int.class, int.class, action, playerEntity);
        Object found;
        try {
            found = field(action, "field_7791").get(null);
        } catch (NoSuchFieldException missing) {
            found = action.getEnumConstants()[2]; // PICKUP, QUICK_MOVE, SWAP…
        }
        swap = found;
    }

    private static Class<?> type(ClassLoader loader, int id) throws ClassNotFoundException {
        return Class.forName("net.minecraft.class_" + id, false, loader);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
