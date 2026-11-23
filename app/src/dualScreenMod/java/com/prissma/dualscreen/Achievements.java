package com.prissma.dualscreen;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Hands every advancement Minecraft would announce with a toast to Biomeria, which celebrates it
 * on the top screen. Biomeria's own animation replaces the vanilla toast only while the app asks
 * for it; if anything here fails the vanilla toast shows as usual.
 * Intermediary names for Fabric 1.21.4, verified against yarn.
 */
public final class Achievements {
    static final int MAGIC = 0x50414331;
    private static Access access;
    private static boolean failed;
    private static long sequence;

    private Achievements() {}

    /** Returns true when Biomeria shows the advancement itself, so the vanilla toast is skipped. */
    public static boolean onToast(Object toast) {
        if (failed || toast == null || GamePanelBridge.SESSION.isEmpty()) return false;
        try {
            Path session = Path.of(GamePanelBridge.SESSION);
            if (!Files.isRegularFile(session.resolve("achievements-enabled"))) return false;
            if (access == null) access = new Access(toast.getClass().getClassLoader());
            if (!access.toast.isInstance(toast)) return false;
            Object entry = access.toastEntry.get(toast);
            Object advancement = access.entryValue.invoke(entry);
            Optional<?> display = (Optional<?>) access.display.getAccessor().invoke(advancement);
            if (display.isEmpty()) return false;
            Object shown = display.get();
            int[] counts = counts();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeUTF(clip(access.entryId.invoke(entry).toString(), 128));
                out.writeUTF(clip((String) access.frameId.get(access.frame.invoke(shown)), 16));
                out.writeUTF(clip(text(access.title.invoke(shown)), 96));
                out.writeUTF(clip(text(access.description.invoke(shown)), 200));
                Object icon = access.icon.invoke(shown);
                Object id = access.registryId.invoke(access.itemRegistry, access.stackItem.invoke(icon));
                out.writeUTF(clip(id == null ? "" : id.toString(), 96));
                out.writeShort(Math.min(65535, counts[0]));
                out.writeShort(Math.min(65535, counts[1]));
            }
            String name = String.format("achievement-%013d-%04d", System.currentTimeMillis(), ++sequence % 10000);
            GamePanelBridge.writeAtomically(session.resolve(name), bytes.toByteArray());
            return true;
        } catch (Exception | LinkageError error) {
            failed = true;
            System.err.println("[PRISSMA] Logros de Biomeria no disponibles; se usa el aviso de Minecraft: " + error);
            return false;
        }
    }

    /** Done and total advancements that have a display (the ones on Minecraft's advancement screen). */
    private static int[] counts() throws ReflectiveOperationException {
        Object client = access.instance.invoke(null);
        Object network = access.networkHandler.invoke(client);
        if (network == null) return new int[] {0, 0};
        Object manager = access.advancementHandler.invoke(network);
        Map<?, ?> progress = (Map<?, ?>) access.progresses.get(manager);
        int done = 0, total = 0;
        for (Map.Entry<?, ?> item : progress.entrySet()) {
            Object advancement = access.entryValue.invoke(item.getKey());
            if (((Optional<?>) access.display.getAccessor().invoke(advancement)).isEmpty()) continue;
            total++;
            if ((boolean) access.isDone.invoke(item.getValue())) done++;
        }
        return new int[] {done, total};
    }

    private static String text(Object value) throws ReflectiveOperationException {
        return value == null ? "" : (String) access.textString.invoke(value);
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class Access {
        final Class<?> toast;
        final Field toastEntry, frameId, progresses;
        final Method entryValue, entryId, title, description, icon, frame, textString, stackItem, registryId;
        final Method instance, networkHandler, advancementHandler, isDone;
        final RecordComponent display;
        final Object itemRegistry;

        Access(ClassLoader loader) throws ReflectiveOperationException {
            toast = type(loader, "367");
            toastEntry = toast.getDeclaredField("field_2205");
            toastEntry.setAccessible(true);
            Class<?> entry = type(loader, "8779");
            entryValue = entry.getMethod("comp_1920");
            entryId = entry.getMethod("comp_1919");
            Class<?> displayType = type(loader, "185");
            RecordComponent found = null;
            for (RecordComponent component : type(loader, "161").getRecordComponents()) {
                if (component.getType() == Optional.class && component.getGenericType().getTypeName().contains("class_185")) {
                    found = component;
                }
            }
            if (found == null) throw new NoSuchMethodException("Advancement.display");
            display = found;
            title = displayType.getMethod("method_811");
            description = displayType.getMethod("method_817");
            icon = displayType.getMethod("method_821");
            frame = displayType.getMethod("method_815");
            frameId = type(loader, "189").getDeclaredField("field_1251");
            frameId.setAccessible(true);
            textString = type(loader, "2561").getMethod("getString");
            Class<?> stack = type(loader, "1799");
            stackItem = stack.getMethod("method_7909");
            registryId = type(loader, "2378").getMethod("method_10221", Object.class);
            itemRegistry = type(loader, "7923").getField("field_41178").get(null);
            Class<?> client = type(loader, "310");
            instance = client.getMethod("method_1551");
            networkHandler = client.getMethod("method_1562");
            advancementHandler = type(loader, "634").getMethod("method_2869");
            progresses = type(loader, "632").getDeclaredField("field_3681");
            progresses.setAccessible(true);
            isDone = type(loader, "167").getMethod("method_740");
        }

        private static Class<?> type(ClassLoader loader, String number) throws ClassNotFoundException {
            return loader.loadClass("net.minecraft.class_" + number);
        }
    }
}
