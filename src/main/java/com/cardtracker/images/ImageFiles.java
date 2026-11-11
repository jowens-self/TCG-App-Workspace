package com.cardtracker.images;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Finds a product's cached thumbnail on disk, without asking the database.
 *
 * ImportCardImages writes every file as {@code <category_id>/<product_id>.jpg},
 * so the path follows from the id once the category directories are known.
 *
 * The API used to read that path out of card_images, which cost one JDBC
 * connection per thumbnail. A 50-row search page opened 50 of them, and 300
 * concurrent thumbnail requests exhausted Postgres' max_connections outright:
 * measured 2026-08-27, 200 of 300 returned 500 with "sorry, too many clients
 * already". Resolving the file on disk instead drops that to zero connections.
 *
 * The category list is cached because it only changes when a new game's images
 * are imported, which is an offline batch job. A miss re-reads the list once
 * before giving up, so a newly imported game is served without restarting the
 * API. Re-reading is cheap: the image root holds one entry per game, not one
 * per card.
 */
public final class ImageFiles {

    private final Path root;
    private final AtomicReference<List<Path>> categories = new AtomicReference<>();

    public ImageFiles(Path root) {
        this.root = root;
    }

    /**
     * The file holding this product's thumbnail, or null when we have none.
     *
     * No caller-controlled string reaches the path: the id is an int and the
     * directory came from our own listing of the image root, so this cannot
     * escape that root the way a stored path could have.
     */
    public Path find(int productId) {
        String name = productId + ".jpg";
        Path hit = probe(cached(), name);
        return hit != null ? hit : probe(refresh(), name);
    }

    private Path probe(List<Path> dirs, String name) {
        for (Path dir : dirs) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    private List<Path> cached() {
        List<Path> now = categories.get();
        return now != null ? now : refresh();
    }

    private List<Path> refresh() {
        List<Path> dirs = List.of();
        try (Stream<Path> entries = Files.list(root)) {
            dirs = entries.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            // An unreadable image root is the same outcome as an empty one:
            // every lookup misses and the client shows its placeholder.
        }
        categories.set(dirs);
        return dirs;
    }
}
