package io.github.juanluci.starling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class BiomeriaProfilesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void originalCardKeepsTheExistingEngineData() throws Exception {
        File files = folder.getRoot();
        File options = new File(files, "options.txt");
        Files.write(options.toPath(), "old settings".getBytes(StandardCharsets.UTF_8));
        String legacy = BiomeriaProfiles.slotForInstance("rust-120fps");
        assertEquals(files, BiomeriaProfiles.prepare(files, legacy, "Starling original"));
        assertEquals(files, BiomeriaProfiles.existing(files, null));
        assertEquals("starling", BiomeriaProfiles.documentRoot(legacy));
        assertEquals(legacy, BiomeriaProfiles.slotForDocument("starling/saves/old world"));

        File other = BiomeriaProfiles.prepare(files, BiomeriaProfiles.slotForInstance("new-card"), "Nueva");
        assertFalse(new File(other, "options.txt").exists());
        assertTrue(options.isFile());
    }

    @Test public void cardsKeepIndependentWorldsModsAndSettingsWhenRenamed() throws Exception {
        File files = folder.getRoot();
        String first = BiomeriaProfiles.slotForInstance("../../survival");
        String second = BiomeriaProfiles.slotForInstance("creativo");
        assertNotEquals(first, second);
        File a = BiomeriaProfiles.prepare(files, first, "Supervivencia");
        File b = BiomeriaProfiles.prepare(files, second, "Creativo");
        for (String name : List.of("saves", "import-inbox", "horizon-mods", "options.txt", "android-launcher.json")) {
            new File(a, name).createNewFile();
            assertFalse(new File(b, name).exists());
        }
        assertEquals(a, BiomeriaProfiles.prepare(files, first, "Mi mundo"));
        assertEquals("Mi mundo", BiomeriaProfiles.title(a));
        assertEquals("Creativo", BiomeriaProfiles.title(b));
        assertTrue(a.getCanonicalPath().startsWith(files.getCanonicalPath() + File.separator));
        assertEquals(3, BiomeriaProfiles.slots(files).size());
    }

    @Test public void documentIdsStayInTheirProfileAndDoNotExposePrivateFiles() throws Exception {
        File files = folder.getRoot();
        String first = BiomeriaProfiles.slotForInstance("first");
        String second = BiomeriaProfiles.slotForInstance("second");
        File a = BiomeriaProfiles.prepare(files, first, "First");
        File b = BiomeriaProfiles.prepare(files, second, "Second");
        String rootA = BiomeriaProfiles.documentRoot(first);
        String rootB = BiomeriaProfiles.documentRoot(second);
        GameFolder docsA = new GameFolder(a, rootA);
        GameFolder docsB = new GameFolder(b, rootB);
        docsA.children(rootA);
        docsB.children(rootB);
        String saved = docsA.create(rootA + "/saves", "Mundo", true);

        assertEquals(first, BiomeriaProfiles.slotForDocument(saved));
        assertEquals(List.of(rootA, rootA + "/saves", saved), docsA.path(null, saved));
        assertEquals(saved, docsA.idOf(docsA.file(saved)));
        assertNull(docsB.idOf(docsA.file(saved)));
        assertFalse(docsB.isChild(rootB, saved));
        assertThrows(FileNotFoundException.class, () -> docsB.file(saved));
        assertThrows(FileNotFoundException.class, () -> docsA.file(rootA + "/saves/../import-inbox"));
        assertThrows(FileNotFoundException.class, () -> docsA.file(rootA + "/android-launcher.json"));
        assertThrows(FileNotFoundException.class, () -> docsA.file(rootA + "/.biomeria-title"));
    }

    @Test public void rejectsUnknownSlotsAndDoesNotFollowProfileLinks() throws Exception {
        File files = folder.getRoot();
        assertThrows(IllegalArgumentException.class, () -> BiomeriaProfiles.directory(files, "../../files"));
        assertThrows(FileNotFoundException.class, () -> BiomeriaProfiles.slotForDocument("starling-unknown/saves"));

        String slot = BiomeriaProfiles.slotForInstance("linked");
        File directory = BiomeriaProfiles.directory(files, slot);
        assertTrue(directory.getParentFile().mkdirs());
        Files.createSymbolicLink(directory.toPath(), files.toPath());

        assertThrows(FileNotFoundException.class, () -> BiomeriaProfiles.existing(files, slot));
        assertFalse(BiomeriaProfiles.slots(files).contains(slot));
    }
}
