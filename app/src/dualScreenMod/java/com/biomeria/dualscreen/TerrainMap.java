package com.biomeria.dualscreen;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Samples only chunks already loaded on the Fabric client, then publishes tiles for Android to draw. */
final class TerrainMap {
    private static final int STATE_MAGIC = 0x50544d31;
    private static final int SIZE = 16;
    private static final int RADIUS = 8;
    private static final List<Offset> NEAREST = offsets();

    private record Offset(int x, int z) {}

    private final Method chunkManager, chunk, surface, state, mapColor, position;
    private final Method worldKey, keyValue, localServer, savePath, serverInfo, yaw;
    private final Field color, address;
    private final Object full, worldSurface, oceanFloor, mutablePosition, clear, water, rootPath;
    private final Map<Long, Integer> sampled = new LinkedHashMap<>(768, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> entry) { return size() > 768; }
    };
    private final Path tiles;
    private Object worldInstance;
    private String scope;
    private Path worldTiles;

    TerrainMap(ClassLoader loader, Path tiles) throws ReflectiveOperationException {
        this.tiles = tiles;
        Class<?> world = type(loader, "638");
        Class<?> chunkStatus = type(loader, "2806");
        Class<?> heightmapType = type(loader, "2902$class_2903");
        Class<?> pos = type(loader, "2338");
        Class<?> mutable = type(loader, "2338$class_2339");
        Class<?> map = type(loader, "3620");
        Class<?> client = type(loader, "310");
        Class<?> save = type(loader, "5218");
        chunkManager = world.getMethod("method_2935");
        chunk = type(loader, "631").getMethod("method_2857", int.class, int.class, chunkStatus, boolean.class);
        full = chunkStatus.getField("field_12803").get(null);
        worldSurface = heightmapType.getField("field_13202").get(null);
        oceanFloor = heightmapType.getField("field_13200").get(null);
        surface = type(loader, "2791").getMethod("method_12005", heightmapType, int.class, int.class);
        state = type(loader, "2818").getMethod("method_8320", pos);
        mutablePosition = mutable.getConstructor().newInstance();
        position = mutable.getMethod("method_10103", int.class, int.class, int.class);
        mapColor = type(loader, "2680").getMethod("method_26205", type(loader, "1922"), pos);
        clear = map.getField("field_16008").get(null);
        water = map.getField("field_16019").get(null);
        color = map.getField("field_16011");
        worldKey = world.getMethod("method_27983");
        keyValue = type(loader, "5321").getMethod("method_29177");
        localServer = client.getMethod("method_1576");
        serverInfo = client.getMethod("method_1558");
        address = type(loader, "642").getField("field_3761");
        savePath = loader.loadClass("net.minecraft.server.MinecraftServer").getMethod("method_27050", save);
        rootPath = save.getField("field_24188").get(null);
        yaw = type(loader, "1297").getMethod("method_36454");
    }

    void update(Path session, Object client, Object world, Object player, double x, double y, double z, int tick, boolean mapVisible)
            throws ReflectiveOperationException, IOException {
        if (world != worldInstance) {
            sampled.clear();
            worldInstance = world;
            Object integrated = localServer.invoke(client);
            Object remote = serverInfo.invoke(client);
            String identity;
            if (integrated != null) {
                Path save = ((Path) savePath.invoke(integrated, rootPath)).normalize();
                identity = "save:" + save.getFileName();
            } else if (remote != null) {
                identity = "server:" + ((String) address.get(remote)).trim().toLowerCase(Locale.ROOT);
            } else {
                identity = "session:" + session.getFileName() + ":" + tick;
            }
            String dimension = keyValue.invoke(worldKey.invoke(world)).toString();
            scope = worldScope(identity, dimension);
            worldTiles = tiles.resolve(scope);
            Files.createDirectories(worldTiles);
        }

        if (mapVisible) sample(world, x, z, tick);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(70);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(STATE_MAGIC);
            out.writeUTF(scope);
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeFloat((float) yaw.invoke(player));
        }
        GamePanelBridge.writeAtomically(session.resolve("terrain-state"), bytes.toByteArray());
    }

    private void sample(Object world, double x, double z, int tick) throws ReflectiveOperationException, IOException {
        Object manager = chunkManager.invoke(world);
        int cx = Math.floorDiv((int) Math.floor(x), SIZE);
        int cz = Math.floorDiv((int) Math.floor(z), SIZE);
        int budget = 4;
        for (Offset offset : NEAREST) {
            if (budget == 0) break;
            int tx = cx + offset.x, tz = cz + offset.z;
            long key = ((long) tx << 32) | (tz & 0xffffffffL);
            if (sampled.getOrDefault(key, 0) > tick) continue;
            Object loaded = chunk.invoke(manager, tx, tz, full, false);
            sampled.put(key, tick + (loaded == null ? 40 : 600));
            if (loaded == null) continue;
            writeTile(world, manager, loaded, tx, tz);
            budget--;
        }

    }

    private void writeTile(Object world, Object manager, Object loaded, int cx, int cz)
            throws ReflectiveOperationException, IOException {
        int[] heights = new int[SIZE * SIZE];
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                heights[z * SIZE + x] = (int) surface.invoke(loaded, worldSurface, x, z);
            }
        }
        // The western and northern edge of the neighbouring chunks, so relief has no seams.
        int[] west = new int[SIZE], north = new int[SIZE];
        Object westChunk = chunk.invoke(manager, cx - 1, cz, full, false);
        Object northChunk = chunk.invoke(manager, cx, cz - 1, full, false);
        for (int i = 0; i < SIZE; i++) {
            west[i] = westChunk == null ? heights[i * SIZE] : (int) surface.invoke(westChunk, worldSurface, SIZE - 1, i);
            north[i] = northChunk == null ? heights[i] : (int) surface.invoke(northChunk, worldSurface, i, SIZE - 1);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(SIZE * SIZE * Integer.BYTES);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    int index = z * SIZE + x;
                    int elevation = heights[index];
                    int blockX = cx * SIZE + x, blockZ = cz * SIZE + z;
                    Object material = clear;
                    for (int depth = 0; depth < 4 && material == clear; depth++) {
                        position.invoke(mutablePosition, blockX, elevation - depth, blockZ);
                        Object block = state.invoke(loaded, mutablePosition);
                        material = mapColor.invoke(block, world, mutablePosition);
                    }
                    // Light from the north-west: slopes facing it brighten, the others darken,
                    // and higher ground is a little lighter so mountains read at a glance.
                    int left = x == 0 ? west[z] : heights[z * SIZE + x - 1];
                    int up = z == 0 ? north[x] : heights[(z - 1) * SIZE + x];
                    int slope = elevation - left + elevation - up;
                    int altitude = Math.max(-18, Math.min(18, (elevation - 64) / 5));
                    int brightness = 232 + altitude + Math.max(-80, Math.min(40, slope * 11));
                    if (material == water) {
                        int depth = Math.max(0, Math.min(14, elevation - (int) surface.invoke(loaded, oceanFloor, x, z)));
                        out.writeInt(shade(0x3e9ba8, 222 - depth * 7));
                    } else {
                        out.writeInt(material == clear ? 0 : shade(color.getInt(material), brightness));
                    }
                }
            }
        }
        GamePanelBridge.writeAtomically(worldTiles.resolve(cx + "_" + cz + ".tile"), bytes.toByteArray());
    }

    private static int shade(int rgb, int brightness) {
        int r = Math.min(255, (rgb >>> 16 & 255) * brightness / 255);
        int g = Math.min(255, (rgb >>> 8 & 255) * brightness / 255);
        int b = Math.min(255, (rgb & 255) * brightness / 255);
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    static String worldScope(String identity, String dimension) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((identity + "\0" + dimension).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<Offset> offsets() {
        List<Offset> result = new ArrayList<>();
        for (int z = -RADIUS; z <= RADIUS; z++) {
            for (int x = -RADIUS; x <= RADIUS; x++) result.add(new Offset(x, z));
        }
        result.sort(Comparator.comparingInt(point -> point.x * point.x + point.z * point.z));
        return List.copyOf(result);
    }

    private static Class<?> type(ClassLoader loader, String id) throws ClassNotFoundException {
        return loader.loadClass("net.minecraft.class_" + id);
    }
}
