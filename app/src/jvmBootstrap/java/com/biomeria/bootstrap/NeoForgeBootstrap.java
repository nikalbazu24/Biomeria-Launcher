package com.biomeria.bootstrap;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

public final class NeoForgeBootstrap {
    private static final Pattern EARLY_WINDOW = Pattern.compile("(?m)^[ \\t]*earlyWindowControl[ \\t]*=[^\\r\\n]*");

    public static void main(String[] args) throws Throwable {
        var plan = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(System.getProperty("biomeria.neoforge.plan")))) {
            plan.load(input);
        }
        prepareClient(plan);

        // NeoForge's desktop splash would create its own GLFW window before Minecraft.
        var config = Path.of(System.getProperty("user.dir"), "config", "fml.toml");
        String text = Files.exists(config) ? Files.readString(config) : "";
        var earlyWindow = EARLY_WINDOW.matcher(text);
        String updated = earlyWindow.find() ? earlyWindow.replaceFirst("earlyWindowControl = false")
                : "earlyWindowControl = false\n" + text;
        if (!updated.equals(text)) writeAtomically(config, updated);

        Path helper = Path.of(NeoForgeBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String separator = File.pathSeparator;
        String legacy = String.join(separator, Arrays.stream(System.getProperty("java.class.path")
                .split(Pattern.quote(separator))).filter(path -> !Path.of(path).equals(helper)).toList());
        System.setProperty("legacyClassPath", legacy);
        System.out.println("[BIOMERIA] Arrancando el cliente de NeoForge.");
        invokeMain(Class.forName("cpw.mods.bootstraplauncher.BootstrapLauncher"), args);
    }

    private static void prepareClient(Properties plan) throws Throwable {
        Path directory = Path.of(plan.getProperty("cache"));
        Path marker = directory.resolve("client-ready-v1");
        Path[] generated = paths(plan, "generated");
        Path[] runtime = paths(plan, "runtime");
        try (FileChannel channel = FileChannel.open(directory.resolve("client.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            if (Files.isRegularFile(marker) && validGameJars(runtime)) return;

            Files.deleteIfExists(marker);
            for (Path output : generated) {
                Files.deleteIfExists(output);
                Files.createDirectories(output.getParent());
            }

            int count = Integer.parseInt(plan.getProperty("count"));
            for (int index = 0; index < count; index++) {
                System.out.printf("[BIOMERIA] Preparando NeoForge · %d/%d%n", index + 1, count);
                runProcessor(plan, index);
            }
            if (!validGameJars(runtime)) throw new IllegalStateException("NeoForge no produjo los archivos del cliente.");
            writeAtomically(marker, "ok\n");
        }
    }

    private static void runProcessor(Properties plan, int index) throws Throwable {
        String main;
        try (var jar = new JarFile(plan.getProperty(index + ".jar"))) {
            main = jar.getManifest().getMainAttributes().getValue("Main-Class");
        }
        Path[] dependencies = paths(plan, index + ".classpath");
        URL[] urls = new URL[dependencies.length];
        for (int i = 0; i < urls.length; i++) urls[i] = dependencies[i].toUri().toURL();
        String[] arguments = new String[Integer.parseInt(plan.getProperty(index + ".argc"))];
        for (int i = 0; i < arguments.length; i++) arguments[i] = plan.getProperty(index + ".arg." + i);

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            invokeMain(Class.forName(main, true, loader), arguments);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void invokeMain(Class<?> main, String[] args) throws Throwable {
        try {
            main.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static Path[] paths(Properties plan, String key) {
        return Arrays.stream(plan.getProperty(key).split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).toArray(Path[]::new);
    }

    private static boolean validGameJars(Path[] outputs) {
        for (Path path : outputs) {
            try (var jar = new JarFile(path.toFile(), false)) {
                if (jar.size() == 0) return false;
            } catch (java.io.IOException failure) {
                return false;
            }
        }
        return true;
    }

    private static void writeAtomically(Path path, String text) throws java.io.IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "neoforge-", ".part");
        try {
            Files.writeString(temporary, text);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
