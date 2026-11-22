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

public final class PrissmaProfilesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void originalCardKeepsTheExistingEngineData() throws Exception {
        File files = folder.getRoot();
        File options = new File(files, "options.txt");
        Files.write(options.toPath(), "old settings".getBytes(StandardCharsets.UTF_8));
        String legacy = PrissmaProfiles.slotForInstance("rust-120fps");
        assertEquals(files, PrissmaProfiles.prepare(files, legacy, "Starling original"));
        assertEquals(files, PrissmaProfiles.existing(files, null));
        assertEquals("starling", PrissmaProfiles.documentRoot(legacy));
        assertEquals(legacy, PrissmaProfiles.slotForDocument("starling/saves/old world"));

        File other = PrissmaProfiles.prepare(files, PrissmaProfiles.slotForInstance("new-card"), "Nueva");
        assertFalse(new File(other, "options.txt").exists());
        assertTrue(options.isFile());
    }

    @Test public void cardsKeepIndependentWorldsModsAndSettingsWhenRenamed() throws Exception {
        File files = folder.getRoot();
        String first = PrissmaProfiles.slotForInstance("../../survival");
        String second = PrissmaProfiles.slotForInstance("creativo");
        assertNotEquals(first, second);
        File a = PrissmaProfiles.prepare(files, first, "Supervivencia");
        File b = PrissmaProfiles.prepare(files, second, "Creativo");
        for (String name : List.of("saves", "import-inbox", "horizon-mods", "options.txt", "android-launcher.json")) {
            new File(a, name).createNewFile();
            assertFalse(new File(b, name).exists());
        }
        assertEquals(a, PrissmaProfiles.prepare(files, first, "Mi mundo"));
        assertEquals("Mi mundo", PrissmaProfiles.title(a));
        assertEquals("Creativo", PrissmaProfiles.title(b));
        assertTrue(a.getCanonicalPath().startsWith(files.getCanonicalPath() + File.separator));
        assertEquals(3, PrissmaProfiles.slots(files).size());
    }

    @Test public void documentIdsStayInTheirProfileAndDoNotExposePrivateFiles() throws Exception {
        File files = folder.getRoot();
        String first = PrissmaProfiles.slotForInstance("first");
        String second = PrissmaProfiles.slotForInstance("second");
        File a = PrissmaProfiles.prepare(files, first, "First");
        File b = PrissmaProfiles.prepare(files, second, "Second");
        String rootA = PrissmaProfiles.documentRoot(first);
        String rootB = PrissmaProfiles.documentRoot(second);
        GameFolder docsA = new GameFolder(a, rootA);
        GameFolder docsB = new GameFolder(b, rootB);
        docsA.children(rootA);
        docsB.children(rootB);
        String saved = docsA.create(rootA + "/saves", "Mundo", true);

        assertEquals(first, PrissmaProfiles.slotForDocument(saved));
        assertEquals(List.of(rootA, rootA + "/saves", saved), docsA.path(null, saved));
        assertEquals(saved, docsA.idOf(docsA.file(saved)));
        assertNull(docsB.idOf(docsA.file(saved)));
        assertFalse(docsB.isChild(rootB, saved));
        assertThrows(FileNotFoundException.class, () -> docsB.file(saved));
        assertThrows(FileNotFoundException.class, () -> docsA.file(rootA + "/saves/../import-inbox"));
        assertThrows(FileNotFoundException.class, () -> docsA.file(rootA + "/android-launcher.json"));
        assertThrows(FileNotFoundException.class, () -> docsA.file(rootA + "/.prissma-title"));
    }

    @Test public void rejectsUnknownSlotsAndDoesNotFollowProfileLinks() throws Exception {
        File files = folder.getRoot();
        assertThrows(IllegalArgumentException.class, () -> PrissmaProfiles.directory(files, "../../files"));
        assertThrows(FileNotFoundException.class, () -> PrissmaProfiles.slotForDocument("starling-unknown/saves"));

        String slot = PrissmaProfiles.slotForInstance("linked");
        File directory = PrissmaProfiles.directory(files, slot);
        assertTrue(directory.getParentFile().mkdirs());
        Files.createSymbolicLink(directory.toPath(), files.toPath());

        assertThrows(FileNotFoundException.class, () -> PrissmaProfiles.existing(files, slot));
        assertFalse(PrissmaProfiles.slots(files).contains(slot));
    }
}
