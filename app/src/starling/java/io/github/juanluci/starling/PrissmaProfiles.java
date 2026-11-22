package io.github.juanluci.starling;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Maps the PRISSMA card to Starling's private data path and to its Files document root. */
public final class PrissmaProfiles {
    public static final String EXTRA_SLOT = "com.prissma.launcher.rust.SLOT";
    public static final String EXTRA_TITLE = "com.prissma.launcher.rust.TITLE";
    static final String LEGACY = "legacy";
    private static final String LEGACY_INSTANCE = "rust-120fps";
    private static final String PROFILE_DIRECTORY = "starling-instances";
    private static final String PROFILE_TITLE = ".prissma-title";
    private static final String DOCUMENT_PREFIX = "starling-";
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PrissmaProfiles() {}

    public static String slotForInstance(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Missing instance id");
        if (LEGACY_INSTANCE.equals(id)) return LEGACY;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            char[] result = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                result[2 * i] = HEX[(bytes[i] & 0xff) >>> 4];
                result[2 * i + 1] = HEX[bytes[i] & 0x0f];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static String slot(String value) {
        if (value == null || LEGACY.equals(value)) return LEGACY;
        if (!HASH.matcher(value).matches()) throw new IllegalArgumentException("Invalid Rust profile");
        return value;
    }

    static File directory(File files, String value) {
        String slot = slot(value);
        return LEGACY.equals(slot) ? files : new File(new File(files, PROFILE_DIRECTORY), slot);
    }

    static File prepare(File files, String slot, String title) throws IOException {
        File root = directory(files, slot);
        if (root.equals(files)) return files;
        Files.createDirectories(root.getParentFile().toPath());
        requireLocalDirectory(root.getParentFile());
        Files.createDirectories(root.toPath());
        requireLocalDirectory(root);
        if (title != null && !title.trim().isEmpty()) {
            String label = title.trim();
            label = label.substring(0, Math.min(label.length(), 120));
            if (!label.equals(title(root))) {
                Path staging = Files.createTempFile(root.toPath(), "title-", ".tmp");
                try {
                    Files.write(staging, label.getBytes(StandardCharsets.UTF_8));
                    Files.move(staging, new File(root, PROFILE_TITLE).toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(staging);
                }
            }
        }
        return root;
    }

    static File existing(File files, String slot) throws FileNotFoundException {
        File folder = directory(files, slot);
        if (!folder.equals(files)) requireLocalDirectory(folder.getParentFile());
        requireLocalDirectory(folder);
        return folder;
    }

    private static void requireLocalDirectory(File folder) throws FileNotFoundException {
        if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new FileNotFoundException("Rust profile folder is unavailable");
        }
    }

    static String title(File root) {
        Path name = new File(root, PROFILE_TITLE).toPath();
        try {
            if (Files.isRegularFile(name, LinkOption.NOFOLLOW_LINKS) && Files.size(name) <= 512) {
                return new String(Files.readAllBytes(name), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // The directory can still be opened under its default name.
        }
        return null;
    }

    static List<String> slots(File files) {
        List<String> slots = new ArrayList<>();
        File parent = new File(files, PROFILE_DIRECTORY);
        if (Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            File[] entries = parent.listFiles();
            if (entries != null) for (File entry : entries) {
                if (HASH.matcher(entry.getName()).matches()
                        && Files.isDirectory(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    slots.add(entry.getName());
                }
            }
        }
        Collections.sort(slots);
        slots.add(0, LEGACY);
        return slots;
    }

    static String documentRoot(String slot) {
        return LEGACY.equals(slot(slot)) ? GameFolder.ROOT : DOCUMENT_PREFIX + slot;
    }

    static String slotForDocument(String id) throws FileNotFoundException {
        if (id == null) throw new FileNotFoundException("Missing Rust document id");
        int slash = id.indexOf('/');
        String root = slash < 0 ? id : id.substring(0, slash);
        if (GameFolder.ROOT.equals(root)) return LEGACY;
        if (root.startsWith(DOCUMENT_PREFIX) && HASH.matcher(root.substring(DOCUMENT_PREFIX.length())).matches()) {
            return root.substring(DOCUMENT_PREFIX.length());
        }
        throw new FileNotFoundException(id);
    }
}
