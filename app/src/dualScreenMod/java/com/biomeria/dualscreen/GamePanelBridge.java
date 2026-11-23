package com.biomeria.dualscreen;

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
    private static final int MAGIC = 0x50525336;
    private static final int MAP_PIXELS = 128 * 128;
    static final String SESSION = System.getProperty("biomeria.dualscreen.session", "");
    private static final String TERRAIN = System.getProperty("biomeria.map.directory", "");
    private static final int SNAPSHOT_TICKS = 4;
    private static final long MENU_OPEN_GRACE_NS = 550_000_000L;
    private static Access game;
    private static boolean disabled;
    private static int ticks;
    private static long generation;
    private static Object previousWorld;
    private static Object previousPlayer;
    private static Object previousHandler;
    private static Object previousScreen;
    private static Object clientInstance;
    private static Object routedScreen;
    private static Layout routedLayout = Layout.PERSONAL;
    private static Object openingScreen;
    private static long openingDeadline;
    private static int preparedMenus;
    private static final InternetWorldLease internetWorld = new InternetWorldLease();
    private static TerrainMap terrain;
    private static boolean terrainFailed;
    private static EntityRadar radar;
    private static boolean radarFailed;
    private static RecipeBookPanel recipeBook;
    private static byte[] previousMap;
    private static int mapRevision;
    private static int writtenMapRevision = -1;
    private static byte[] lastSnapshot;
    private static long lastWrite;

    // Ordinals and slot counts match Android's PanelLayout.
    private enum Layout {
        PERSONAL(46), CRAFTING_TABLE(46), CHEST(63), DOUBLE_CHEST(90), FURNACE(39), HOPPER(41), DISPENSER(45);
        final int slots;
        Layout(int slots) { this.slots = slots; }
    }

    private GamePanelBridge() {}

    public static void tick(Object client) {
        if (SESSION.isEmpty()) return;
        checkQuit(client);
        PlayerSkin.tick(client, Path.of(SESSION));
        HotbarTouch.tick(client, Path.of(SESSION));
        MenuBridge.tick(client, Path.of(SESSION));
        if (disabled || ++ticks % 2 != 0) return;
        Path root = Path.of(SESSION);
        Path lanRequest = root.resolve("lan-open");
        boolean shareRequested = Files.isRegularFile(lanRequest);
        boolean internetRequested = Files.isRegularFile(root.resolve("internet-open"));
        if (!internetRequested) internetWorld.clear();
        boolean panelVisible = Files.isRegularFile(root.resolve("visible"));
        boolean mapVisible = !TERRAIN.isEmpty() && !terrainFailed && Files.isRegularFile(root.resolve("terrain-enabled"));
        boolean trackPosition = !TERRAIN.isEmpty() && !terrainFailed &&
                (mapVisible || Files.isRegularFile(root.resolve("terrain-position")));
        if (!panelVisible) {
            clearRoute();
            preparedMenus = 0;
            openingDeadline = 0;
            if (!trackPosition && !shareRequested && !internetRequested) return;
        } else {
            preparedMenus = readMenuPolicy(root.resolve("menu-policy"));
        }
        try {
            if (game == null) {
                ClassLoader loader = client.getClass().getClassLoader();
                game = new Access(loader);
                try {
                    recipeBook = new RecipeBookPanel(loader);
                } catch (ReflectiveOperationException | LinkageError error) {
                    disableRecipeBook(error);
                }
                clientInstance = client;
            }
            Object player = game.player.get(client);
            Object world = game.world.get(client);
            if (shareRequested && Files.deleteIfExists(lanRequest)) {
                int port;
                try {
                    port = player == null || world == null ? 0 : LocalWorldShare.open(game.integratedServer.invoke(client));
                } catch (ReflectiveOperationException | LinkageError error) {
                    port = -1;
                    System.err.println("[BIOMERIA] No se pudo abrir la sala LAN: " + error);
                }
                writeAtomically(root.resolve("lan-result"), Integer.toString(port).getBytes(StandardCharsets.US_ASCII));
            }
            boolean snapshotTick = ticks % SNAPSHOT_TICKS == 0;
            if (internetRequested && snapshotTick) {
                internetWorld.update(root, game.integratedServer.invoke(client), world != null && player != null);
            }
            if (!panelVisible) {
                if (trackPosition && snapshotTick) updateTerrain(root, client, world, player, mapVisible);
                return;
            }
            Object screen = game.currentScreen.get(client);
            if (screen == null) {
                openingScreen = null;
                openingDeadline = 0;
            }
            Object personal = player == null ? null : game.playerHandler.get(player);
            Object active = player == null ? null : game.currentHandler.get(player);
            Layout layout = game.layout(screen, active);
            Object handler = layout == Layout.PERSONAL ? personal : active;
            long before = generation;
            if (world != previousWorld || player != previousPlayer) {
                previousWorld = world;
                previousPlayer = player;
                previousMap = null;
                writtenMapRevision = -1;
                generation++;
            }
            if (handler != previousHandler || screen != previousScreen) {
                previousHandler = handler;
                previousScreen = screen;
                generation++;
            }
            Object interactions = game.interactions.get(client);
            boolean canUse = world != null && handler != null && interactions != null
                    && (layout != Layout.PERSONAL || (screen == null && active == personal))
                    && !(boolean) game.spectator.invoke(player);
            boolean route = canUse && layout != Layout.PERSONAL
                    && hasGenerationMarker(root.resolve("routed-menu"));
            if (!route || routedScreen != screen) clearRoute();
            if (route) {
                routedScreen = screen;
                routedLayout = layout;
            }
            if (consumeRequests(root, player, world, handler, interactions, canUse, layout, screen)) {
                clearRoute();
                return;
            }
            if (snapshotTick || generation != before) {
                boolean recipesHere = layout == Layout.PERSONAL
                        || (layout == Layout.CRAFTING_TABLE && routedLayout == Layout.CRAFTING_TABLE);
                if (recipeBook != null && canUse && recipesHere && hasGenerationMarker(root.resolve("recipes-open"))) {
                    try {
                        recipeBook.publish(root.resolve("recipes"), generation, player, world, handler);
                    } catch (ReflectiveOperationException | LinkageError error) {
                        disableRecipeBook(error);
                    }
                }
                publishSnapshot(root, player, world, handler, canUse, layout, screen);
            }
            if (trackPosition && snapshotTick) updateTerrain(root, client, world, player, mapVisible);
        } catch (ReflectiveOperationException | LinkageError error) {
            disabled = true;
            clearRoute();
            System.err.println("[BIOMERIA] El panel Fabric no pudo leer la sesión del juego: " + error.getClass().getSimpleName());
        } catch (IOException ignored) {
            // Android can close the panel or dispose the session between ticks.
        }
    }

    private static boolean quitting;

    /**
     * Android asks to leave by writing "quit". MinecraftClient.scheduleStop (method_1592) ends the
     * game loop the same way as closing the window: the world is saved before Minecraft exits.
     * Checked before the panel guards so it works even when the inventory panel is off.
     */
    private static void checkQuit(Object client) {
        if (quitting) return;
        Path request = Path.of(SESSION).resolve("quit");
        if (!Files.isRegularFile(request)) return;
        quitting = true;
        try {
            Files.deleteIfExists(request);
            client.getClass().getMethod("method_1592").invoke(client);
            System.out.println("[BIOMERIA] Guardando el mundo y cerrando Minecraft.");
        } catch (IOException | ReflectiveOperationException | LinkageError error) {
            quitting = false;
            System.err.println("[BIOMERIA] No se pudo cerrar Minecraft de forma ordenada: " + error);
        }
    }

    private static void updateTerrain(Path root, Object client, Object world, Object player, boolean mapVisible) {
        try {
            if (world == null || player == null) {
                Files.deleteIfExists(root.resolve("terrain-state"));
                return;
            }
            if (terrain == null) terrain = new TerrainMap(client.getClass().getClassLoader(), Path.of(TERRAIN));
            double px = (double) game.x.invoke(player), py = (double) game.y.invoke(player), pz = (double) game.z.invoke(player);
            terrain.update(root, client, world, player, px, py, pz, ticks, mapVisible);
            updateRadar(root, client, world, player, px, py, pz, mapVisible);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            terrainFailed = true;
            System.err.println("[BIOMERIA] Mapa de terreno no disponible: " + error);
        } catch (IOException ignored) {
            // A tile write failure must not interrupt the container handoff.
        }
    }

    /** Entities appear only in worlds this device hosts (singleplayer or its own LAN room), never on servers. */
    private static void updateRadar(Path root, Object client, Object world, Object player,
                                    double x, double y, double z, boolean mapVisible) throws IOException {
        Path target = root.resolve("terrain-entities");
        boolean wanted = mapVisible && !radarFailed && Files.isRegularFile(root.resolve("entities-enabled"));
        try {
            if (!wanted || game.integratedServer.invoke(client) == null) {
                Files.deleteIfExists(target);
                return;
            }
            if (radar == null) radar = new EntityRadar(client.getClass().getClassLoader());
            radar.publish(target, world, player, x, y, z);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            radarFailed = true;
            Files.deleteIfExists(target);
            System.err.println("[BIOMERIA] Radar de criaturas no disponible: " + error);
        }
    }

    private static int readMenuPolicy(Path marker) {
        try {
            if (Files.isRegularFile(marker) && Files.size(marker) <= 4) {
                return Integer.parseInt(Files.readString(marker, StandardCharsets.US_ASCII));
            }
        } catch (IOException | NumberFormatException ignored) {
            // Keep Minecraft's regular screen if the lower display is unavailable.
        }
        return 0;
    }

    private static boolean hasGenerationMarker(Path marker) {
        try {
            return Files.isRegularFile(marker) && Files.size(marker) <= 20
                    && Files.readString(marker, StandardCharsets.US_ASCII).equals(Long.toString(generation));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void disableRecipeBook(Throwable error) {
        recipeBook = null;
        System.err.println("[BIOMERIA] Libro de recetas no disponible: " + error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private static void clearRoute() {
        routedScreen = null;
        routedLayout = Layout.PERSONAL;
    }

    public static boolean hidesRoutedScreen(Object screen) {
        if (MenuBridge.hides(screen)) return true;
        if (screen == null || disabled) return false;
        if (routedScreen == screen) {
            openingScreen = screen;
            openingDeadline = 0;
            return true;
        }
        if (openingScreen != screen) {
            openingScreen = screen;
            openingDeadline = 0;
            if (preparedMenus != 0 && isPreparedContainer(screen)) {
                // Avoid drawing the vanilla menu while Android presents its first frame.
                openingDeadline = System.nanoTime() + MENU_OPEN_GRACE_NS;
            }
        }
        return preparedMenus != 0 && openingDeadline != 0 && System.nanoTime() - openingDeadline < 0;
    }

    private static boolean isPreparedContainer(Object screen) {
        try {
            if (screen != game.currentScreen.get(clientInstance)) return false;
            Object player = game.player.get(clientInstance);
            if (player == null || game.world.get(clientInstance) == null ||
                    game.interactions.get(clientInstance) == null || (boolean) game.spectator.invoke(player)) return false;
            Layout layout = game.layout(screen, game.currentHandler.get(player));
            return layout != Layout.PERSONAL && (preparedMenus & (1 << layout.ordinal())) != 0;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean blocksRoutedMouse() {
        if (game == null || (routedScreen == null && preparedMenus == 0)) return false;
        try {
            return hidesRoutedScreen(game.currentScreen.get(clientInstance));
        } catch (IllegalAccessException ignored) {
            return false;
        }
    }

    private static boolean consumeRequests(Path root, Object player, Object world, Object handler, Object interactions,
                                           boolean canUse, Layout layout, Object screen)
            throws IOException, ReflectiveOperationException {
        List<Path> requests;
        try (Stream<Path> files = Files.list(root)) {
            requests = files.filter(path -> path.getFileName().toString().matches("command-[0-9]{12}"))
                    .sorted().limit(8).toList();
        }
        for (Path request : requests) {
            String command = Files.size(request) <= 64 ? Files.readString(request, StandardCharsets.US_ASCII) : "";
            Files.deleteIfExists(request);
            if (!canUse || (layout != Layout.PERSONAL && routedScreen != screen)) continue;
            String[] parts = command.split(" ");
            if (parts.length != 3) continue;
            try {
                if (Long.parseLong(parts[0]) != generation) continue;
                int slot = Integer.parseInt(parts[2]);
                switch (parts[1]) {
                    case "pick" -> click(player, handler, interactions, slot, 0, game.pickup, layout.slots);
                    case "split" -> click(player, handler, interactions, slot, 1, game.pickup, layout.slots);
                    case "quick" -> click(player, handler, interactions, slot, 0, game.quickMove, layout.slots);
                    // Drops one item from the slot, as pressing Q over it does.
                    case "throw" -> click(player, handler, interactions, slot, 0, game.throwStack, layout.slots);
                    case "hotbar" -> {
                        if (layout == Layout.PERSONAL && slot >= 0 && slot <= 8) game.selectSlot.invoke(game.inventory.invoke(player), slot);
                    }
                    case "close" -> {
                        if (layout != Layout.PERSONAL && slot == 0) {
                            game.closeScreen.invoke(screen);
                            return true;
                        }
                    }
                    case "inspect", "recipe", "recipeall" -> {
                        if ((layout != Layout.CRAFTING_TABLE && layout != Layout.PERSONAL) || recipeBook == null || slot < 0) continue;
                        if (parts[1].equals("inspect")) {
                            recipeBook.inspect(slot, player);
                        } else if ((boolean) game.empty.invoke(game.cursorStack.invoke(handler))) {
                            try {
                                recipeBook.place(generation, slot, player, world, handler, interactions, game.syncId.getInt(handler),
                                        parts[1].equals("recipeall"));
                            } catch (ReflectiveOperationException | LinkageError error) {
                                disableRecipeBook(error);
                            }
                        }
                    }
                    default -> { }
                }
            } catch (NumberFormatException ignored) {
                // A malformed request cannot change game state.
            }
        }
        return false;
    }

    private static void click(Object player, Object handler, Object interactions, int slot, int button, Object action, int slotCount)
            throws ReflectiveOperationException {
        List<?> slots = (List<?>) game.slots.get(handler);
        if (slot >= 0 && slot < slotCount && slot < slots.size()) {
            game.clickSlot.invoke(interactions, game.syncId.getInt(handler), slot, button, action, player);
        }
    }

    private static void publishSnapshot(Path root, Object player, Object world, Object handler, boolean canUse,
                                        Layout layout, Object screen)
            throws IOException, ReflectiveOperationException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(MAGIC);
            data.writeLong(generation);
            boolean present = player != null && world != null && handler != null;
            data.writeBoolean(present);
            if (present) {
                data.writeBoolean(canUse);
                data.writeByte(layout.ordinal());
                data.writeInt(game.selectedSlot.getInt(game.inventory.invoke(player)));
                data.writeDouble((double) game.x.invoke(player));
                data.writeDouble((double) game.y.invoke(player));
                data.writeDouble((double) game.z.invoke(player));
                List<String> ids = new java.util.ArrayList<>(layout.slots + 1);
                ids.add(writeStack(data, game.cursorStack.invoke(handler)));
                List<?> slots = (List<?>) game.slots.get(handler);
                for (int i = 0; i < layout.slots; i++) ids.add(writeStack(data, i < slots.size() ? game.slotStack.invoke(slots.get(i)) : null));
                data.writeInt(publishHeldMap(root, player, world));
                data.writeBoolean((layout == Layout.CRAFTING_TABLE || layout == Layout.PERSONAL) && recipeBook != null);
                String title = layout == Layout.PERSONAL ? "" : (String) game.text.invoke(game.title.invoke(screen));
                data.writeUTF(title.substring(0, Math.min(title.length(), 96)));
                if (layout == Layout.FURNACE) {
                    data.writeFloat((float) game.cookProgress.invoke(handler));
                    data.writeFloat((float) game.fuelProgress.invoke(handler));
                    data.writeBoolean((boolean) game.furnaceBurning.invoke(handler));
                }
                // Item ids (minecraft:torch, mod:item) after everything else, so Android can draw the
                // game's own icons. Older readers stop before this block and keep working.
                data.writeShort(ids.size());
                for (String id : ids) data.writeUTF(id);
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

    /** Writes the name and count of a stack and returns its item id ("" when empty or unknown). */
    private static String writeStack(DataOutputStream data, Object stack) throws IOException, ReflectiveOperationException {
        if (stack == null || (boolean) game.empty.invoke(stack)) {
            data.writeUTF("");
            data.writeInt(0);
            return "";
        }
        String name = (String) game.text.invoke(game.name.invoke(stack));
        data.writeUTF(name.substring(0, Math.min(name.length(), 96)));
        data.writeInt((int) game.count.invoke(stack));
        return itemId(stack);
    }

    private static String itemId(Object stack) {
        if (game.itemOf == null) return "";
        try {
            Object id = game.registryId.invoke(game.itemRegistry, game.itemOf.invoke(stack));
            String value = id == null ? "" : id.toString();
            return value.length() <= 128 ? value : "";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return "";
        }
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

    static void writeAtomically(Path target, byte[] data) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.write(part, data);
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Intermediary names for Fabric 1.21.4. The APK build does not bundle Mojang classes. */
    private static final class Access {
        final Field player, world, currentScreen, interactions, playerHandler, currentHandler, slots, syncId, selectedSlot, mapColors;
        final Method slotStack, cursorStack, empty, name, text, count, inventory, selectSlot, clickSlot, spectator;
        final Method x, y, z, mainHand, offHand, component, mapState, mapColor, integratedServer;
        final Method screenHandler, closeScreen, title, chestRows, cookProgress, fuelProgress, furnaceBurning;
        final Class<?> craftingScreen, craftingHandler, chestScreen, chestHandler, furnaceScreen, furnaceHandler;
        final Object pickup, quickMove, throwStack, mapId;
        /** Optional containers: shulker box, hopper, dispenser/dropper and every furnace kind. */
        final Class<?> shulkerScreen, shulkerHandler, hopperScreen, hopperHandler, dispenserScreen, dispenserHandler;
        final Class<?> anyFurnaceScreen, anyFurnaceHandler;
        /** Optional: ItemStack.getItem, Registries.ITEM and Registry.getId. Icons are skipped if missing. */
        final Method itemOf, registryId;
        final Object itemRegistry;

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
            integratedServer = client.getMethod("method_1576");
            craftingScreen = type(loader, 479);
            craftingHandler = type(loader, 1714);
            chestScreen = type(loader, 476);
            chestHandler = type(loader, 1707);
            furnaceScreen = type(loader, 3873);
            furnaceHandler = type(loader, 3858);
            chestRows = chestHandler.getMethod("method_17388");
            Class<?> furnace = type(loader, 1720);
            cookProgress = furnace.getMethod("method_17363");
            fuelProgress = furnace.getMethod("method_17364");
            furnaceBurning = furnace.getMethod("method_17365");
            screenHandler = type(loader, 465).getMethod("method_17577");
            closeScreen = type(loader, 437).getMethod("method_25419");
            title = type(loader, 437).getMethod("method_25440");
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
            throwStack = field(action, "field_7795").get(null);
            Method item = null;
            Method id = null;
            Object registry = null;
            try {
                item = stack.getMethod("method_7909");
                registry = field(type(loader, 7923), "field_41178").get(null);
                id = type(loader, 2378).getMethod("method_10221", Object.class);
            } catch (ReflectiveOperationException | LinkageError error) {
                item = null;
                System.err.println("[BIOMERIA] Íconos de objetos no disponibles: " + error);
            }
            itemOf = item;
            registryId = id;
            itemRegistry = registry;
            shulkerScreen = optional(loader, 495);
            shulkerHandler = optional(loader, 1733);
            hopperScreen = optional(loader, 488);
            hopperHandler = optional(loader, 1722);
            dispenserScreen = optional(loader, 480);
            dispenserHandler = optional(loader, 1716);
            anyFurnaceScreen = optional(loader, 489);
            anyFurnaceHandler = furnace;
        }

        Layout layout(Object screen, Object handler) throws ReflectiveOperationException {
            if (screen == null || handler == null) return Layout.PERSONAL;
            Class<?> screenType = screen.getClass();
            Class<?> handlerType = handler.getClass();
            if (screenType == craftingScreen && handlerType == craftingHandler && screenHandler.invoke(screen) == handler) {
                return Layout.CRAFTING_TABLE;
            }
            if (screenType == chestScreen && handlerType == chestHandler && screenHandler.invoke(screen) == handler) {
                int rows = (int) chestRows.invoke(handler);
                if (rows == 3) return Layout.CHEST;
                if (rows == 6) return Layout.DOUBLE_CHEST;
            }
            if (screenType == furnaceScreen && handlerType == furnaceHandler && screenHandler.invoke(screen) == handler) {
                return Layout.FURNACE;
            }
            // Smoker and blast furnace share the furnace slots and progress methods.
            if (anyFurnaceScreen != null && anyFurnaceScreen.isInstance(screen) && anyFurnaceHandler.isInstance(handler)
                    && screenHandler.invoke(screen) == handler) {
                return Layout.FURNACE;
            }
            if (matches(screenType, shulkerScreen, handlerType, shulkerHandler, screen, handler)) return Layout.CHEST;
            if (matches(screenType, hopperScreen, handlerType, hopperHandler, screen, handler)) return Layout.HOPPER;
            if (matches(screenType, dispenserScreen, handlerType, dispenserHandler, screen, handler)) return Layout.DISPENSER;
            return Layout.PERSONAL;
        }

        private boolean matches(Class<?> screenType, Class<?> expectedScreen, Class<?> handlerType, Class<?> expectedHandler,
                                Object screen, Object handler) throws ReflectiveOperationException {
            return expectedScreen != null && expectedHandler != null && screenType == expectedScreen
                    && handlerType == expectedHandler && screenHandler.invoke(screen) == handler;
        }

        /** A missing class only hides that one container from the lower screen. */
        private static Class<?> optional(ClassLoader loader, int id) {
            try {
                return type(loader, id);
            } catch (ClassNotFoundException | LinkageError error) {
                System.err.println("[BIOMERIA] Contenedor class_" + id + " no disponible en el panel.");
                return null;
            }
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
