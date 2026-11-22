package com.prissma.dualscreen;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Runs at the tail of the Fabric client tick so inventory changes follow Minecraft's own protocol. */
public final class GamePanelBridge {
    private static final int MAGIC = 0x50525333;
    private static final int SLOTS = 46;
    private static final int MAP_PIXELS = 128 * 128;
    private static final String SESSION = System.getProperty("prissma.dualscreen.session", "");
    private static final int SNAPSHOT_TICKS = 4;
    private static Access game;
    private static boolean disabled;
    private static int ticks;
    private static long generation;
    private static Object previousWorld;
    private static Object previousPlayer;
    private static Object previousHandler;
    private static Object clientInstance;
    private static Object routedCraftingScreen;
    private static XaeroPanel xaero;
    private static byte[] previousMap;
    private static int mapRevision;
    private static int writtenMapRevision = -1;
    private static byte[] lastSnapshot;
    private static long lastWrite;

    private GamePanelBridge() {}

    public static void tick(Object client) {
        if (SESSION.isEmpty() || disabled || ++ticks % 2 != 0) return;
        Path root = Path.of(SESSION);
        if (!Files.isRegularFile(root.resolve("visible"))) {
            routedCraftingScreen = null;
            return;
        }
        try {
            if (game == null) {
                ClassLoader loader = client.getClass().getClassLoader();
                game = new Access(loader);
                xaero = new XaeroPanel(loader);
                clientInstance = client;
            }
            Object player = game.player.get(client);
            Object world = game.world.get(client);
            Object screen = game.currentScreen.get(client);
            Object personal = player == null ? null : game.playerHandler.get(player);
            Object active = player == null ? null : game.currentHandler.get(player);
            boolean table = screen != null && screen.getClass() == game.craftingScreen
                    && active != null && active.getClass() == game.craftingHandler
                    && game.screenHandler.invoke(screen) == active;
            Object handler = table ? active : personal;
            if (world != previousWorld || player != previousPlayer) {
                previousWorld = world;
                previousPlayer = player;
                previousMap = null;
                writtenMapRevision = -1;
                generation++;
            }
            if (handler != previousHandler) {
                previousHandler = handler;
                generation++;
            }
            Object interactions = game.interactions.get(client);
            boolean canUse = world != null && handler != null && interactions != null
                    && (table || (screen == null && active == personal))
                    && !(boolean) game.spectator.invoke(player);
            routedCraftingScreen = canUse && table && requestedCrafting(root) ? screen : null;
            if (consumeRequests(root, player, handler, interactions, canUse, table, screen)) {
                routedCraftingScreen = null;
                return;
            }
            if (ticks % SNAPSHOT_TICKS == 0) publishSnapshot(root, player, world, handler, canUse, table);
        } catch (ReflectiveOperationException | LinkageError error) {
            disabled = true;
            routedCraftingScreen = null;
            System.err.println("[PRISSMA] El panel Fabric no pudo leer la sesión del juego: " + error.getClass().getSimpleName());
        } catch (IOException ignored) {
            // Android can close the panel or dispose the session between ticks.
        }
    }

    private static boolean requestedCrafting(Path root) {
        Path marker = root.resolve("crafting");
        try {
            return Files.isRegularFile(marker) && Files.size(marker) <= 20
                    && Files.readString(marker, StandardCharsets.US_ASCII).equals(Long.toString(generation));
        } catch (IOException ignored) {
            return false;
        }
    }

    public static boolean hidesCrafting(Object screen) {
        return routedCraftingScreen != null && routedCraftingScreen == screen;
    }

    public static boolean blocksCraftingMouse() {
        if (routedCraftingScreen == null) return false;
        try {
            return hidesCrafting(game.currentScreen.get(clientInstance));
        } catch (IllegalAccessException ignored) {
            return false;
        }
    }

    private static boolean consumeRequests(Path root, Object player, Object handler, Object interactions,
                                           boolean canUse, boolean table, Object screen)
            throws IOException, ReflectiveOperationException {
        List<Path> requests;
        try (Stream<Path> files = Files.list(root)) {
            requests = files.filter(path -> path.getFileName().toString().matches("command-[0-9]{12}"))
                    .sorted().limit(8).toList();
        }
        for (Path request : requests) {
            String command = Files.size(request) <= 64 ? Files.readString(request, StandardCharsets.US_ASCII) : "";
            Files.deleteIfExists(request);
            if (!canUse || (table && !hidesCrafting(screen))) continue;
            String[] parts = command.split(" ");
            if (parts.length != 3) continue;
            try {
                if (Long.parseLong(parts[0]) != generation) continue;
                int slot = Integer.parseInt(parts[2]);
                switch (parts[1]) {
                    case "pick" -> click(player, handler, interactions, slot, 0, game.pickup);
                    case "split" -> click(player, handler, interactions, slot, 1, game.pickup);
                    case "quick" -> click(player, handler, interactions, slot, 0, game.quickMove);
                    case "hotbar" -> {
                        if (!table && slot >= 0 && slot <= 8) game.selectSlot.invoke(game.inventory.invoke(player), slot);
                    }
                    case "close" -> {
                        if (table && slot == 0) {
                            game.closeScreen.invoke(screen);
                            return true;
                        }
                    }
                    case "xaero" -> {
                        if (!table && slot == 0 && xaero.openWorldMap()) return true;
                    }
                    default -> { }
                }
            } catch (NumberFormatException ignored) {
                // A malformed request cannot change game state.
            }
        }
        return false;
    }

    private static void click(Object player, Object handler, Object interactions, int slot, int button, Object action)
            throws ReflectiveOperationException {
        List<?> slots = (List<?>) game.slots.get(handler);
        if (slot >= 0 && slot < SLOTS && slot < slots.size()) {
            game.clickSlot.invoke(interactions, game.syncId.getInt(handler), slot, button, action, player);
        }
    }

    private static void publishSnapshot(Path root, Object player, Object world, Object handler, boolean canUse,
                                        boolean table)
            throws IOException, ReflectiveOperationException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(MAGIC);
            data.writeLong(generation);
            boolean present = player != null && world != null && handler != null;
            data.writeBoolean(present);
            if (present) {
                data.writeBoolean(canUse);
                data.writeByte(table ? 1 : 0);
                data.writeInt(game.selectedSlot.getInt(game.inventory.invoke(player)));
                data.writeDouble((double) game.x.invoke(player));
                data.writeDouble((double) game.y.invoke(player));
                data.writeDouble((double) game.z.invoke(player));
                writeStack(data, game.cursorStack.invoke(handler));
                List<?> slots = (List<?>) game.slots.get(handler);
                for (int i = 0; i < SLOTS; i++) writeStack(data, i < slots.size() ? game.slotStack.invoke(slots.get(i)) : null);
                data.writeInt(publishHeldMap(root, player, world));
                xaero.write(data);
            }
        }
        byte[] snapshot = bytes.toByteArray();
        long now = System.nanoTime();
        if (!Arrays.equals(lastSnapshot, snapshot) || now - lastWrite >= 1_000_000_000L) {
            writeAtomically(root.resolve("snapshot"), snapshot);
            lastSnapshot = snapshot;
            lastWrite = now;
        }
    }

    private static void writeStack(DataOutputStream data, Object stack) throws IOException, ReflectiveOperationException {
        if (stack == null || (boolean) game.empty.invoke(stack)) {
            data.writeUTF("");
            data.writeInt(0);
            return;
        }
        String name = (String) game.text.invoke(game.name.invoke(stack));
        data.writeUTF(name.substring(0, Math.min(name.length(), 96)));
        data.writeInt((int) game.count.invoke(stack));
    }

    private static int publishHeldMap(Path root, Object player, Object world) throws IOException, ReflectiveOperationException {
        Object state = mapForStack(game.mainHand.invoke(player), world);
        if (state == null) state = mapForStack(game.offHand.invoke(player), world);
        if (state == null) return -1;
        byte[] colors = (byte[]) game.mapColors.get(state);
        if (colors.length != MAP_PIXELS) return -1;
        if (Arrays.equals(colors, previousMap) && writtenMapRevision >= 0) return writtenMapRevision;
        int revision = ++mapRevision;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4 + MAP_PIXELS * 4);
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(revision);
            for (byte color : colors) {
                int abgr = (int) game.mapColor.invoke(null, color & 0xff);
                data.writeInt((abgr & 0xff00ff00) | ((abgr >>> 16) & 0xff) | ((abgr & 0xff) << 16));
            }
        }
        writeAtomically(root.resolve("map"), bytes.toByteArray());
        writtenMapRevision = revision;
        previousMap = colors.clone();
        return revision;
    }

    private static Object mapForStack(Object stack, Object world) throws ReflectiveOperationException {
        Object mapId = game.component.invoke(stack, game.mapId);
        return mapId == null ? null : game.mapState.invoke(null, mapId, world);
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.write(part, data);
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Intermediary names for Fabric 1.21.4. The APK build does not bundle Mojang classes. */
    private static final class Access {
        final Field player, world, currentScreen, interactions, playerHandler, currentHandler, slots, syncId, selectedSlot, mapColors;
        final Method slotStack, cursorStack, empty, name, text, count, inventory, selectSlot, clickSlot, spectator;
        final Method x, y, z, mainHand, offHand, component, mapState, mapColor;
        final Method screenHandler, closeScreen;
        final Class<?> craftingScreen, craftingHandler;
        final Object pickup, quickMove, mapId;

        Access(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> client = type(loader, 310);
            Class<?> playerEntity = type(loader, 1657);
            Class<?> stack = type(loader, 1799);
            Class<?> playerInventory = type(loader, 1661);
            Class<?> handler = type(loader, 1703);
            Class<?> action = type(loader, 1713);
            Class<?> entity = type(loader, 1297);
            Class<?> living = type(loader, 1309);
            player = field(client, "field_1724");
            world = field(client, "field_1687");
            currentScreen = field(client, "field_1755");
            craftingScreen = type(loader, 479);
            craftingHandler = type(loader, 1714);
            screenHandler = craftingScreen.getMethod("method_17577");
            closeScreen = type(loader, 437).getMethod("method_25419");
            interactions = field(client, "field_1761");
            playerHandler = field(playerEntity, "field_7498");
            currentHandler = field(playerEntity, "field_7512");
            slots = field(handler, "field_7761");
            syncId = field(handler, "field_7763");
            selectedSlot = field(playerInventory, "field_7545");
            mapColors = field(type(loader, 22), "field_122");
            slotStack = type(loader, 1735).getMethod("method_7677");
            cursorStack = handler.getMethod("method_34255");
            empty = stack.getMethod("method_7960");
            name = stack.getMethod("method_7964");
            text = type(loader, 2561).getMethod("getString");
            count = stack.getMethod("method_7947");
            inventory = playerEntity.getMethod("method_31548");
            selectSlot = playerInventory.getMethod("method_61496", int.class);
            clickSlot = type(loader, 636).getMethod("method_2906", int.class, int.class, int.class, action, playerEntity);
            spectator = playerEntity.getMethod("method_7325");
            x = entity.getMethod("method_23317");
            y = entity.getMethod("method_23318");
            z = entity.getMethod("method_23321");
            mainHand = living.getMethod("method_6047");
            offHand = living.getMethod("method_6079");
            component = type(loader, 9322).getMethod("method_57824", type(loader, 9331));
            mapId = field(type(loader, 9334), "field_49646").get(null);
            mapState = type(loader, 1806).getMethod("method_7997", type(loader, 9209), type(loader, 1937));
            mapColor = type(loader, 3620).getMethod("method_38480", int.class);
            pickup = field(action, "field_7790").get(null);
            quickMove = field(action, "field_7794").get(null);
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
}
