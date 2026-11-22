package com.prissma.dualscreen;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Optional links to the user's installed Xaero mods. The launcher never bundles either mod. */
final class XaeroPanel {
    private final WorldMap worldMap;
    private final Minimap minimap;

    XaeroPanel(ClassLoader loader) {
        WorldMap worldMap = null;
        Minimap minimap = null;
        try {
            worldMap = new WorldMap(loader);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // World Map is optional and may use a different API.
        }
        try {
            minimap = new Minimap(loader);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Minimap is optional and may use a different API.
        }
        this.worldMap = worldMap;
        this.minimap = minimap;
    }

    boolean openWorldMap() {
        return worldMap != null && worldMap.queueOpen();
    }

    void write(DataOutputStream out) throws IOException {
        out.writeBoolean(worldMap != null);
        out.writeBoolean(minimap != null);
        List<Waypoint> marks = minimap == null ? List.of() : minimap.currentWaypoints();
        out.writeByte(marks.size());
        for (Waypoint mark : marks) {
            out.writeUTF(mark.name());
            out.writeInt(mark.x());
            out.writeInt(mark.y());
            out.writeInt(mark.z());
        }
    }

    private static final class WorldMap {
        private final Object openKey;
        private final Field presses;

        WorldMap(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> controls = loader.loadClass("xaero.map.controls.ControlsRegister");
            openKey = controls.getField("keyOpenMap").get(null);
            presses = loader.loadClass("net.minecraft.class_304").getDeclaredField("field_1661");
            presses.setAccessible(true);
        }

        boolean queueOpen() {
            try {
                // Feed the registered Xaero binding instead of simulating a shared keyboard key.
                if (presses.getInt(openKey) == 0) presses.setInt(openKey, 1);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
    }

    private record Waypoint(String name, int x, int y, int z) {}

    private static final class Minimap {
        private final Method session, manager, currentSet, size, get, disabled, name, x, y, z;

        Minimap(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> sessionType = loader.loadClass("xaero.common.XaeroMinimapSession");
            session = sessionType.getMethod("getCurrentSession");
            manager = sessionType.getMethod("getWaypointsManager");
            currentSet = manager.getReturnType().getMethod("getWaypoints");
            Class<?> set = currentSet.getReturnType();
            size = set.getMethod("size");
            get = set.getMethod("get", int.class);
            Class<?> waypoint = get.getReturnType();
            disabled = waypoint.getMethod("isDisabled");
            name = waypoint.getMethod("getName");
            x = waypoint.getMethod("getX");
            y = waypoint.getMethod("getY");
            z = waypoint.getMethod("getZ");
        }

        List<Waypoint> currentWaypoints() {
            try {
                Object active = session.invoke(null);
                if (active == null) return List.of();
                Object set = currentSet.invoke(manager.invoke(active));
                if (set == null) return List.of();
                List<Waypoint> marks = new ArrayList<>();
                int total = Math.min(256, (int) size.invoke(set));
                for (int i = 0; i < total && marks.size() < 24; i++) {
                    Object waypoint = get.invoke(set, i);
                    if (waypoint == null || (boolean) disabled.invoke(waypoint)) continue;
                    String label = (String) name.invoke(waypoint);
                    if (label != null && !label.isBlank()) {
                        marks.add(new Waypoint(label.substring(0, Math.min(label.length(), 96)),
                                (int) x.invoke(waypoint), (int) y.invoke(waypoint), (int) z.invoke(waypoint)));
                    }
                }
                return marks;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Xaero can be switching worlds while the panel is requesting the active set.
                return List.of();
            }
        }
    }
}
