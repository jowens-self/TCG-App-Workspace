package com.cardtracker.images;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Thumbnails are resolved on disk so that serving one costs no database
 * connection. These pin the parts of that which could regress quietly.
 */
@DisplayName("ImageFiles")
class ImageFilesTest {

    private static Path image(Path root, String category, int productId) throws IOException {
        Path dir = Files.createDirectories(root.resolve(category));
        Path file = dir.resolve(productId + ".jpg");
        Files.writeString(file, "jpeg");
        return file;
    }

    @Test
    void findsAnImageByProductIdAlone(@TempDir Path root) throws IOException {
        Path expected = image(root, "3", 42382);
        assertEquals(expected, new ImageFiles(root).find(42382));
    }

    /**
     * Absence is the ordinary case, not an error: roughly 40% of Pokemon
     * singles have no upstream image and One Piece has none at all, so the
     * client renders a placeholder off the back of this null.
     */
    @Test
    void returnsNullWhenWeHoldNoImage(@TempDir Path root) throws IOException {
        image(root, "3", 42382);
        assertNull(new ImageFiles(root).find(999999));
    }

    /**
     * The image root holds one entry per game plus the catalog cache, which is
     * a file. Treating that file as a category would be harmless but pointless;
     * treating a game directory as a file would lose every image it holds.
     */
    @Test
    void ignoresNonDirectoryEntriesInTheRoot(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".catalog-cache"), "{}");
        Path expected = image(root, "3", 42382);
        assertEquals(expected, new ImageFiles(root).find(42382));
    }

    /**
     * The one that would rot silently. The category list is cached, so a game
     * whose images are imported while the API is running would 404 forever if
     * a miss did not re-read the root. The old code read card_images per
     * request and so never had this failure mode; keeping it absent is the
     * price of dropping the query.
     */
    @Test
    void picksUpAGameImportedAfterTheFirstLookup(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("3"));
        ImageFiles files = new ImageFiles(root);
        assertNull(files.find(700001), "nothing on disk for this id yet");

        Path added = image(root, "68", 700001);
        assertEquals(added, files.find(700001),
                "a category directory created after the cache was built must still be found");
    }
}
