package com.prissma.dualscreen;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The look Biomeria's character editor chose: skin, arms, cape and visible layers.
 * Only this device draws the chosen skin and cape (no server stores them), while the
 * visible layers are the game's own options and other players see them too.
 * Intermediary names for Fabric 1.21.4, verified against yarn.
 */
public final class PlayerSkin {
    private static final int CHECK_TICKS = 20;
    private static int ticks;
    private static boolean failed;
    private static String generation = "";
    private static Access access;

    private static volatile Object localPlayer;
    private static volatile Object skinId, capeId;
    private static volatile String model;
    private static volatile String capeMode = "account";
    private static String exported = "";

    private PlayerSkin() {}

    static void tick(Object client, Path root) {
        if (failed || ++ticks % CHECK_TICKS != 0) return;
        try {
            if (access == null) access = new Access(client.getClass().getClassLoader());
            localPlayer = access.player.get(client);
            Path marker = root.resolve("skin-generation");
            String next = Files.isRegularFile(marker) && Files.size(marker) <= 32
                    ? Files.readString(marker, StandardCharsets.US_ASCII).trim() : "";
            if (next.equals(generation)) return;
            generation = next;
            apply(client, root.resolve("skin"), next);
        } catch (Exception | LinkageError error) {
            failed = true;
            skinId = null;
            capeId = null;
            model = null;
            System.err.println("[PRISSMA] El editor de personaje no está disponible: " + error);
        }
    }

    private static void apply(Object client, Path folder, String next) throws Exception {
        Map<String, String> config = new HashMap<>();
        Path file = folder.resolve("config");
        if (!next.isEmpty() && Files.isRegularFile(file) && Files.size(file) <= 1024) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int split = line.indexOf('=');
                if (split > 0) config.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
            }
        }
        Object textures = access.textureManager.invoke(client);
        Object oldSkin = skinId, oldCape = capeId;
        skinId = next.isEmpty() ? null : register(textures, folder.resolve("skin.png"), "skin/" + next);
        capeMode = config.getOrDefault("cape", "account");
        capeId = "custom".equals(capeMode) ? register(textures, folder.resolve("cape.png"), "cape/" + next) : null;
        model = skinId == null ? null : ("slim".equals(config.get("arms")) ? "slim" : "wide");
        if (oldSkin != null) access.destroyTexture.invoke(textures, oldSkin);
        if (oldCape != null) access.destroyTexture.invoke(textures, oldCape);

        if (next.isEmpty()) return;
        Object options = access.options.get(client);
        for (Object part : access.parts) {
            String name = (String) access.partName.invoke(part);
            String key = switch (name) {
                case "left_sleeve", "right_sleeve" -> "sleeves";
                case "left_pants_leg", "right_pants_leg" -> "pants";
                default -> name;
            };
            boolean shown = "cape".equals(key) ? !"none".equals(capeMode) : !"0".equals(config.get(key));
            access.setPart.invoke(options, part, shown);
        }
        access.sendSettings.invoke(options);
    }

    private static Object register(Object textures, Path png, String path) throws Exception {
        if (!Files.isRegularFile(png) || Files.size(png) > 256 * 1024) return null;
        Object image;
        try (InputStream input = Files.newInputStream(png)) {
            image = access.readImage.invoke(null, input);
        }
        Object texture = access.backedTexture.newInstance(image);
        Object id = access.identifier.invoke(null, "biomeria", path);
        access.registerTexture.invoke(textures, id, texture);
        return id;
    }

    /** Called by the skin mixin with the textures Minecraft chose for a player. */
    public static Object textures(Object player, Object original) {
        if (failed || access == null || original == null || player != localPlayer) return original;
        try {
            export(original);
            Object skin = skinId, cape = capeId;
            String chosenModel = model, mode = capeMode;
            if (skin == null && cape == null && !"none".equals(mode)) return original;
            Object[] values = new Object[access.components.length];
            for (int i = 0; i < values.length; i++) values[i] = access.components[i].getAccessor().invoke(original);
            if (skin != null) {
                values[0] = skin;
                values[1] = null;
                values[4] = access.modelFromName.invoke(null, chosenModel);
            }
            if (cape != null) {
                values[2] = cape;
                values[3] = cape;
            } else if ("none".equals(mode)) {
                values[2] = null;
                values[3] = null;
            }
            return access.skinTextures.newInstance(values);
        } catch (Exception | LinkageError error) {
            failed = true;
            System.err.println("[PRISSMA] Se restauró la skin original: " + error);
            return original;
        }
    }

    /** Tells Biomeria which skin the account really has, so the editor can draw it. */
    private static void export(Object original) throws Exception {
        if (GamePanelBridge.SESSION.isEmpty()) return;
        Object url = access.components[1].getAccessor().invoke(original);
        Object texture = access.components[0].getAccessor().invoke(original);
        Object chosen = access.components[4].getAccessor().invoke(original);
        String value = (url == null ? "" : url) + "\n" + texture + "\n" + access.modelName.invoke(chosen);
        if (value.equals(exported)) return;
        exported = value;
        GamePanelBridge.writeAtomically(Path.of(GamePanelBridge.SESSION).resolve("skin-account"),
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Access {
        final Field player, options;
        final Method textureManager, registerTexture, destroyTexture, readImage, identifier;
        final Method partName, setPart, sendSettings, modelFromName, modelName;
        final Constructor<?> backedTexture, skinTextures;
        final RecordComponent[] components;
        final Object[] parts;

        Access(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> client = type(loader, "310");
            player = client.getField("field_1724");
            options = client.getField("field_1690");
            textureManager = client.getMethod("method_1531");
            Class<?> manager = type(loader, "1060");
            Class<?> id = type(loader, "2960");
            registerTexture = manager.getMethod("method_4616", id, type(loader, "1044"));
            destroyTexture = manager.getMethod("method_4615", id);
            Class<?> image = type(loader, "1011");
            readImage = image.getMethod("method_4309", InputStream.class);
            backedTexture = type(loader, "1043").getConstructor(image);
            identifier = id.getMethod("method_60655", String.class, String.class);
            Class<?> part = type(loader, "1664");
            parts = part.getEnumConstants();
            partName = part.getMethod("method_7429");
            Class<?> gameOptions = type(loader, "315");
            setPart = gameOptions.getMethod("method_1635", part, boolean.class);
            sendSettings = gameOptions.getMethod("method_1643");
            Class<?> textures = type(loader, "8685");
            Class<?> skinModel = loader.loadClass("net.minecraft.class_8685$class_7920");
            modelFromName = skinModel.getMethod("method_52857", String.class);
            modelName = skinModel.getMethod("method_52856");
            components = textures.getRecordComponents();
            if (components == null || components.length != 6 || components[0].getType() != id
                    || components[1].getType() != String.class || components[2].getType() != id
                    || components[4].getType() != skinModel || components[5].getType() != boolean.class) {
                throw new NoSuchMethodException("SkinTextures tiene otra forma");
            }
            Class<?>[] shape = new Class<?>[components.length];
            for (int i = 0; i < shape.length; i++) shape[i] = components[i].getType();
            skinTextures = textures.getDeclaredConstructor(shape);
            skinTextures.setAccessible(true);
        }

        private static Class<?> type(ClassLoader loader, String number) throws ClassNotFoundException {
            return loader.loadClass("net.minecraft.class_" + number);
        }
    }
}
