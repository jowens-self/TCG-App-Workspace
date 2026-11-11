package com.cardtracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch guard.
 *
 * The default image directory is a relative path, so it names a different
 * directory for every working directory a process starts from. Launched
 * anywhere but the project root, the importer created a fresh empty tree and
 * downloaded into it while the API served 404s from the real one, and neither
 * side erroring, which is why it went unnoticed for a day.
 */
@DisplayName("ImageDir")
class ImageDirTest {

    private static Path projectRoot(Path dir) throws Exception {
        Files.createFile(dir.resolve("pom.xml"));
        Files.createDirectories(dir.resolve("sql"));
        Files.createFile(dir.resolve("sql/schema.sql"));
        return dir;
    }

    @Test
    void resolvesUnderTheWorkingDirectoryWhenItIsTheProjectRoot(@TempDir Path tmp) throws Exception {
        Path resolved = ImageDir.resolve(projectRoot(tmp), null);
        assertEquals(tmp.resolve("images"), resolved);
        assertTrue(resolved.isAbsolute(), "must be absolute so writer and reader agree");
    }

    /**
     * The failure this exists to prevent. Note the second assertion: refusing
     * is only useful if it happens before the directory is created, since the decoy
     * tree left behind is what made the original bug invisible.
     */
    @Test
    void refusesAndCreatesNothingOutsideTheProjectRoot(@TempDir Path tmp) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ImageDir.resolve(tmp, null));

        assertFalse(Files.exists(tmp.resolve("images")),
                "refusal must precede createDirectories, or the decoy tree comes back");
        assertTrue(e.getMessage().contains(tmp.toString()),
                "the error must name the directory it actually found");
        assertTrue(e.getMessage().contains("CARDTRACKER_IMAGE_DIR"),
                "the error must name a way out");
    }

    /**
     * Both markers are required. pom.xml alone is satisfied by any Maven
     * checkout on the machine, which is most of them.
     */
    @Test
    void oneMarkerAloneIsNotEnough(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("pom.xml"));
        assertThrows(IllegalStateException.class, () -> ImageDir.resolve(tmp, null),
                "pom.xml alone must not identify this project");
    }

    @Test
    void theOtherMarkerAloneIsNotEnoughEither(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("sql"));
        Files.createFile(tmp.resolve("sql/schema.sql"));
        assertThrows(IllegalStateException.class, () -> ImageDir.resolve(tmp, null));
    }

    /**
     * An explicit path is a decision, not an accident, so it is honoured with
     * no root check at all, which is what lets a deliberate run happen from
     * anywhere, including the one-off imports this project actually does.
     */
    @Test
    void anExplicitDirectoryIsHonouredFromAnywhere(@TempDir Path tmp) {
        Path elsewhere = tmp.resolve("somewhere-else");
        assertEquals(elsewhere.toAbsolutePath().normalize(),
                ImageDir.resolve(tmp, elsewhere.toString()),
                "an explicit path must win even though tmp is not a project root");
    }

    @Test
    void blankConfigurationFallsBackToTheDefaultRule(@TempDir Path tmp) throws Exception {
        assertEquals(tmp.resolve("images"), ImageDir.resolve(projectRoot(tmp), "   "),
                "a blank env var is unset, not a choice");
    }
}
