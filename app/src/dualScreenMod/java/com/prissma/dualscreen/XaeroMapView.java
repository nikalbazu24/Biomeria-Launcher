package com.prissma.dualscreen;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Captures the installed Xaero screen and restores the world pixels before Minecraft presents its frame. */
final class XaeroMapView {
    private static final int MAGIC = 0x50584d31;
    private static final int READ_FRAMEBUFFER = 0x8ca8;
    private static final int DRAW_FRAMEBUFFER = 0x8ca9;
    private static final int COLOR_BUFFER_BIT = 0x4000;
    private static final int LINEAR = 0x2601;
    private static final int NEAREST = 0x2600;
    private static final int RGBA = 0x1908;
    private static final int UNSIGNED_BYTE = 0x1401;
    private static final int COORDINATE_MAX = 4095;
    private static final long FRAME_INTERVAL_NS = 90_000_000L;
    private static final int HEADER_BYTES = 20;

    private final Access access;
    private final Object client;
    private CameraPan cameraPan;
    private boolean reportedPanFailure;
    private Object worldBackup;
    private Object previewTarget;
    private ByteBuffer readPixels;
    private ByteBuffer writeFrame;
    private byte[] lastFrame;
    private long nextFrame;
    private boolean rendering;
    private boolean failed;
    private boolean pressed;
    private double pointerX = .5;
    private double pointerY = .5;

    private XaeroMapView(ClassLoader loader, Object client) throws ReflectiveOperationException {
        this.access = new Access(loader);
        this.client = client;
        try {
            cameraPan = new CameraPan(access.mapScreen);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Camera fields are optional. The regular map and its touch input still work.
        }
    }

    static XaeroMapView create(ClassLoader loader, Object client) {
        try {
            return new XaeroMapView(loader, client);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Xaero's World Map is optional. The regular top-screen shortcut stays available.
            return null;
        }
    }

    boolean available() { return !failed; }

    boolean isMap(Object screen) { return screen != null && screen.getClass() == access.mapScreen; }

    boolean render(Object screen, Object draw, float delta, Path destination, long generation) {
        if (rendering || failed) return false;
        long now = System.nanoTime();
        if (now < nextFrame) return true;
        nextFrame = now + FRAME_INTERVAL_NS;

        rendering = true;
        Object original = null;
        int width = 0;
        int height = 0;
        boolean saved = false;
        try {
            original = access.mainTarget.get(client);
            access.flush.invoke(draw);
            width = access.targetWidth.getInt(original);
            height = access.targetHeight.getInt(original);
            if (width <= 0 || height <= 0) return false;
            double scale = Math.min(1.0, Math.min(640.0 / width, 480.0 / height));
            int previewWidth = Math.max(1, (int) Math.round(width * scale));
            int previewHeight = Math.max(1, (int) Math.round(height * scale));
            worldBackup = target(worldBackup, width, height);
            previewTarget = target(previewTarget, previewWidth, previewHeight);

            // Xaero's render layers can bind the main framebuffer themselves. Save the world there first.
            blit(original, worldBackup, width, height, width, height, NEAREST);
            saved = true;
            access.beginWrite.invoke(original, true);
            withPointer(screen, (x, y) -> access.render.invoke(screen, draw, (int) x, (int) y, delta));
            access.flush.invoke(draw);
            blit(original, previewTarget, width, height, previewWidth, previewHeight, LINEAR);
            capture(destination, generation, previewWidth, previewHeight);
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError error) {
            fail(error);
        } finally {
            rendering = false;
            if (original != null) {
                try {
                    if (saved) blit(worldBackup, original, width, height, width, height, NEAREST);
                    access.beginWrite.invoke(original, true);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                    fail(error);
                }
            }
        }
        // A compatibility failure puts the original screen back on the game display.
        return !failed;
    }

    private Object target(Object existing, int width, int height) throws ReflectiveOperationException {
        if (existing == null) return access.newTarget.newInstance(width, height, false);
        if (access.targetWidth.getInt(existing) != width || access.targetHeight.getInt(existing) != height) {
            access.resizeTarget.invoke(existing, width, height);
        }
        return existing;
    }

    private void blit(Object source, Object destination, int width, int height, int toWidth, int toHeight, int filter)
            throws ReflectiveOperationException {
        access.bindTarget.invoke(null, READ_FRAMEBUFFER, access.targetId.getInt(source));
        access.bindTarget.invoke(null, DRAW_FRAMEBUFFER, access.targetId.getInt(destination));
        access.blitTarget.invoke(null, 0, 0, width, height, 0, 0, toWidth, toHeight, COLOR_BUFFER_BIT, filter);
    }

    private void capture(Path destination, long generation, int previewWidth, int previewHeight)
            throws ReflectiveOperationException, IOException {
        access.bindTarget.invoke(null, READ_FRAMEBUFFER, access.targetId.getInt(previewTarget));

        int rowBytes = previewWidth * 4;
        int size = rowBytes * previewHeight;
        if (readPixels == null || readPixels.capacity() != size) {
            readPixels = ByteBuffer.allocateDirect(size);
            writeFrame = ByteBuffer.allocate(HEADER_BYTES + size);
        }
        readPixels.clear();
        access.read.invoke(null, 0, 0, previewWidth, previewHeight, RGBA, UNSIGNED_BYTE, readPixels);
        writeFrame.clear();
        writeFrame.putInt(MAGIC).putLong(generation).putInt(previewWidth).putInt(previewHeight);
        for (int row = previewHeight - 1; row >= 0; row--) {
            readPixels.limit((row + 1) * rowBytes).position(row * rowBytes);
            writeFrame.put(readPixels);
        }
        byte[] frame = writeFrame.array();
        if (!Arrays.equals(lastFrame, frame) || !Files.isRegularFile(destination)) {
            GamePanelBridge.writeAtomically(destination, frame);
            lastFrame = frame.clone();
        }
    }

    void input(Object screen, String command, int value) {
        if (failed) return;
        if (command.equals("map-pan")) {
            pan(screen, value);
            return;
        }
        try {
            if (command.equals("map-zoom")) {
                if (value == -1 || value == 1) {
                    withPointer(screen, (x, y) -> access.scroll.invoke(screen, x, y, 0.0, (double) value));
                    nextFrame = 0;
                }
                return;
            }
            if (value < 0 || value > 0xffffff) return;
            pointerX = (value >>> 12) / (double) COORDINATE_MAX;
            pointerY = (value & COORDINATE_MAX) / (double) COORDINATE_MAX;
            if (command.equals("map-down") && !pressed) {
                withPointer(screen, (x, y) -> access.press.invoke(screen, x, y, 0));
                pressed = true;
                nextFrame = 0;
            } else if (command.equals("map-up") && pressed) {
                release(screen);
                nextFrame = 0;
            }
            // GuiMap pans from the current mouse coordinates while its left button is held.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    private void pan(Object screen, int value) {
        if (pressed || value <= 0 || value > 0xffff) return;
        int horizontal = (byte) (value >>> 8);
        int vertical = (byte) value;
        if (horizontal == -128 || vertical == -128) return;
        if (cameraPan != null) {
            try {
                cameraPan.move(screen, horizontal, vertical);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                cameraPan = null;
            }
        }
        if (!reportedPanFailure) {
            reportedPanFailure = true;
            System.err.println("[PRISSMA] Esta versión de Xaero no admite mover el mapa con el stick.");
        }
    }

    void detach(Object screen) {
        try {
            if (pressed && screen == access.activeScreen.get(client)) release(screen);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            fail(error);
        } finally {
            pressed = false;
            if (worldBackup != null) disposeTarget(worldBackup);
            if (previewTarget != null) disposeTarget(previewTarget);
            worldBackup = null;
            previewTarget = null;
            lastFrame = null;
            nextFrame = 0;
            pointerX = .5;
            pointerY = .5;
        }
    }

    private void release(Object screen) throws ReflectiveOperationException {
        pressed = false;
        withPointer(screen, (x, y) -> access.release.invoke(screen, x, y, 0));
    }

    private void disposeTarget(Object target) {
        try {
            access.deleteTarget.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            fail(error);
        }
    }

    private void withPointer(Object screen, PointerAction action) throws ReflectiveOperationException {
        Object mouse = access.mouse.get(client);
        Object window = access.window.invoke(client);
        double oldX = access.mouseX.getDouble(mouse);
        double oldY = access.mouseY.getDouble(mouse);
        try {
            access.mouseX.setDouble(mouse, pointerX * (int) access.windowWidth.invoke(window));
            access.mouseY.setDouble(mouse, pointerY * (int) access.windowHeight.invoke(window));
            action.run(pointerX * Math.max(0, access.screenWidth.getInt(screen) - 1),
                    pointerY * Math.max(0, access.screenHeight.getInt(screen) - 1));
        } finally {
            access.mouseX.setDouble(mouse, oldX);
            access.mouseY.setDouble(mouse, oldY);
        }
    }

    private void fail(Throwable error) {
        if (!failed) System.err.println("[PRISSMA] No se pudo mostrar Xaero abajo: " + error);
        failed = true;
    }

    private interface PointerAction {
        void run(double x, double y) throws ReflectiveOperationException;
    }

    private static final class CameraPan {
        private final Field cameraX, cameraZ, scale, attached, reinit, destination, animationX, animationZ;

        CameraPan(Class<?> screen) throws ReflectiveOperationException {
            cameraX = Access.field(screen, "cameraX");
            cameraZ = Access.field(screen, "cameraZ");
            scale = Access.field(screen, "scale");
            attached = Access.field(screen, "attachedCamera");
            reinit = Access.field(screen, "shouldReinit");
            destination = Access.field(screen, "cameraDestination");
            animationX = Access.field(screen, "cameraDestinationAnimX");
            animationZ = Access.field(screen, "cameraDestinationAnimZ");
        }

        void move(Object screen, int horizontal, int vertical) throws ReflectiveOperationException {
            double zoom = scale.getDouble(screen);
            if (!Double.isFinite(zoom) || zoom <= 0) return;
            // Use the same screen-to-world scale as Xaero's drag, without clicking any map widgets.
            double step = 30.0 / (127 * zoom);
            if (attached.getBoolean(screen)) {
                attached.setBoolean(screen, false);
                reinit.setBoolean(screen, true);
            }
            destination.set(screen, null);
            animationX.set(screen, null);
            animationZ.set(screen, null);
            cameraX.setDouble(screen, cameraX.getDouble(screen) + horizontal * step);
            cameraZ.setDouble(screen, cameraZ.getDouble(screen) + vertical * step);
        }
    }

    private static final class Access {
        final Class<?> mapScreen;
        final Constructor<?> newTarget;
        final Field mainTarget, activeScreen, targetWidth, targetHeight, targetId;
        final Field mouse, mouseX, mouseY, screenWidth, screenHeight;
        final Method resizeTarget, deleteTarget, beginWrite, bindTarget, blitTarget, read, flush;
        final Method render, window, windowWidth, windowHeight, press, release, scroll;

        Access(ClassLoader loader) throws ReflectiveOperationException {
            mapScreen = loader.loadClass("xaero.map.gui.GuiMap");
            Class<?> client = type(loader, 310);
            Class<?> screen = type(loader, 437);
            Class<?> draw = type(loader, 332);
            Class<?> framebuffer = type(loader, 276);
            Class<?> mouseType = type(loader, 312);
            Class<?> windowType = type(loader, 1041);
            Class<?> element = type(loader, 364);
            Class<?> gl = loader.loadClass("com.mojang.blaze3d.platform.GlStateManager");
            mainTarget = field(client, "field_1689");
            activeScreen = field(client, "field_1755");
            targetWidth = field(framebuffer, "field_1480");
            targetHeight = field(framebuffer, "field_1477");
            targetId = field(framebuffer, "field_1476");
            newTarget = type(loader, 6367).getConstructor(int.class, int.class, boolean.class);
            resizeTarget = framebuffer.getMethod("method_1234", int.class, int.class);
            deleteTarget = framebuffer.getMethod("method_1238");
            beginWrite = framebuffer.getMethod("method_1235", boolean.class);
            bindTarget = gl.getMethod("_glBindFramebuffer", int.class, int.class);
            blitTarget = gl.getMethod("_glBlitFrameBuffer", int.class, int.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, int.class, int.class);
            read = gl.getMethod("_readPixels", int.class, int.class, int.class, int.class, int.class, int.class, ByteBuffer.class);
            flush = draw.getMethod("method_51452");
            render = screen.getMethod("method_47413", draw, int.class, int.class, float.class);
            mouse = field(client, "field_1729");
            mouseX = field(mouseType, "field_1795");
            mouseY = field(mouseType, "field_1794");
            screenWidth = field(screen, "field_22789");
            screenHeight = field(screen, "field_22790");
            window = client.getMethod("method_22683");
            windowWidth = windowType.getMethod("method_4480");
            windowHeight = windowType.getMethod("method_4507");
            press = element.getMethod("method_25402", double.class, double.class, int.class);
            release = element.getMethod("method_25406", double.class, double.class, int.class);
            scroll = element.getMethod("method_25401", double.class, double.class, double.class, double.class);
        }

        private static Class<?> type(ClassLoader loader, int id) throws ClassNotFoundException {
            return loader.loadClass("net.minecraft.class_" + id);
        }

        private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }
}
