package com.biomeria.dualscreen;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lists living entities near the player for the Biomeria map. Only the launcher's own worlds
 * (singleplayer or a LAN room hosted here) use it: on servers the radar would be an unfair advantage.
 * Intermediary names for Fabric 1.21.4, verified against yarn.
 */
final class EntityRadar {
    static final int MAGIC = 0x50454e31;
    private static final double RADIUS = 64;
    private static final int LIMIT = 80;

    static final byte PLAYER = 0, HOSTILE = 1, PASSIVE = 2, OTHER = 3;

    private record Seen(byte kind, String id, String name, double x, double y, double z, double distance) {}

    private final Method entities, type, group, groupName, removed, invisible, name, text, x, y, z, registryId;
    private final Class<?> living, playerType;
    private final Object entityRegistry;

    EntityRadar(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> entity = type(loader, "1297");
        entities = type(loader, "638").getMethod("method_18112");
        type = entity.getMethod("method_5864");
        group = type(loader, "1299").getMethod("method_5891");
        groupName = type(loader, "1311").getMethod("method_6133");
        removed = entity.getMethod("method_31481");
        invisible = entity.getMethod("method_5767");
        name = entity.getMethod("method_5477");
        text = type(loader, "2561").getMethod("getString");
        x = entity.getMethod("method_23317");
        y = entity.getMethod("method_23318");
        z = entity.getMethod("method_23321");
        living = type(loader, "1309");
        playerType = type(loader, "1657");
        var field = type(loader, "7923").getField("field_41177");
        entityRegistry = field.get(null);
        registryId = type(loader, "2378").getMethod("method_10221", Object.class);
    }

    void publish(Path target, Object world, Object self, double px, double py, double pz)
            throws ReflectiveOperationException, IOException {
        List<Seen> found = new ArrayList<>();
        for (Object entity : (Iterable<?>) entities.invoke(world)) {
            if (entity == self || !living.isInstance(entity)) continue;
            if ((boolean) removed.invoke(entity) || (boolean) invisible.invoke(entity)) continue;
            double ex = (double) x.invoke(entity), ey = (double) y.invoke(entity), ez = (double) z.invoke(entity);
            double distance = Math.hypot(ex - px, ez - pz);
            if (distance > RADIUS || Math.abs(ey - py) > RADIUS) continue;
            Object entityType = type.invoke(entity);
            byte kind;
            String label = "";
            if (playerType.isInstance(entity)) {
                kind = PLAYER;
                label = (String) text.invoke(name.invoke(entity));
            } else {
                String spawn = (String) groupName.invoke(group.invoke(entityType));
                kind = switch (spawn) {
                    case "monster" -> HOSTILE;
                    case "creature", "ambient", "axolotls", "water_creature", "water_ambient", "underground_water_creature" -> PASSIVE;
                    default -> OTHER;
                };
            }
            Object id = registryId.invoke(entityRegistry, entityType);
            String key = id == null ? "" : id.toString();
            found.add(new Seen(kind, clip(key, 64), clip(label, 32), ex, ey, ez, distance));
        }
        found.sort(Comparator.comparingDouble(Seen::distance));
        int count = Math.min(LIMIT, found.size());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + count * 48);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeShort(count);
            for (int i = 0; i < count; i++) {
                Seen seen = found.get(i);
                out.writeByte(seen.kind);
                out.writeUTF(seen.id);
                out.writeUTF(seen.name);
                out.writeDouble(seen.x);
                out.writeFloat((float) seen.y);
                out.writeDouble(seen.z);
            }
        }
        GamePanelBridge.writeAtomically(target, bytes.toByteArray());
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Class<?> type(ClassLoader loader, String id) throws ClassNotFoundException {
        return loader.loadClass("net.minecraft.class_" + id);
    }
}
