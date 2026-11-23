package com.biomeria.dualscreen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Biomeria's own menus: while they are on, Minecraft's pause screen stays open (so the world is
 * paused) but is not drawn, and Android shows its menu on top. Also reloads options.txt after
 * Android edits it, so video and sound changes apply without leaving the world.
 */
public final class MenuBridge {
    private static boolean failed;
    private static boolean optionsFailed;
    private static Field currentScreen, options, worldRenderer;
    private static Class<?> pauseScreen;
    private static Method load, reloadWorld, resolutionChanged;
    private static volatile Object hiddenScreen;
    private static boolean pauseWritten;

    private MenuBridge() {}

    /** Called from the screen render mixin: true hides this screen. */
    public static boolean hides(Object screen) {
        return screen != null && screen == hiddenScreen;
    }

    static void tick(Object client, Path root) {
        if (failed) return;
        try {
            if (currentScreen == null) init(client.getClass().getClassLoader());
            Object screen = currentScreen.get(client);
            boolean enabled = Files.isRegularFile(root.resolve("biomeria-menus"));
            Path vanilla = root.resolve("vanilla-pause");
            if (screen == null) Files.deleteIfExists(vanilla);
            boolean pause = enabled && screen != null && pauseScreen.isInstance(screen) && !Files.isRegularFile(vanilla);
            hiddenScreen = pause ? screen : null;
            if (pause != pauseWritten) {
                Path marker = root.resolve("pause-open");
                if (pause) Files.writeString(marker, "1", StandardCharsets.US_ASCII); else Files.deleteIfExists(marker);
                pauseWritten = pause;
            }
            Path reload = root.resolve("options-reload");
            if (Files.isRegularFile(reload)) {
                Files.deleteIfExists(reload);
                Files.writeString(root.resolve("options-result"), applyOptions(client) ? "1" : "0", StandardCharsets.US_ASCII);
            }
        } catch (Exception | LinkageError error) {
            failed = true;
            hiddenScreen = null;
            System.err.println("[BIOMERIA] Menús de Biomeria no disponibles: " + error);
        }
    }

    private static boolean applyOptions(Object client) {
        if (optionsFailed) return false;
        try {
            Object settings = options.get(client);
            load.invoke(settings);
            Object renderer = worldRenderer.get(client);
            if (renderer != null && reloadWorld != null) reloadWorld.invoke(renderer);
            if (resolutionChanged != null) resolutionChanged.invoke(client);
            return true;
        } catch (Exception | LinkageError error) {
            optionsFailed = true;
            System.err.println("[BIOMERIA] No se pudieron recargar las opciones: " + error);
            return false;
        }
    }

    private static void init(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> client = Class.forName("net.minecraft.class_310", false, loader);
        Class<?> gameOptions = Class.forName("net.minecraft.class_315", false, loader);
        pauseScreen = Class.forName("net.minecraft.class_433", false, loader);
        currentScreen = field(client, "field_1755");
        try {
            options = field(client, "field_1690");
            worldRenderer = field(client, "field_1769");
            load = gameOptions.getMethod("method_1636");
            reloadWorld = optional(Class.forName("net.minecraft.class_761", false, loader), "method_3279");
            resolutionChanged = optional(client, "method_15993");
        } catch (ReflectiveOperationException | LinkageError error) {
            optionsFailed = true;
            System.err.println("[BIOMERIA] Opciones en vivo no disponibles: " + error);
        }
    }

    private static Method optional(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
