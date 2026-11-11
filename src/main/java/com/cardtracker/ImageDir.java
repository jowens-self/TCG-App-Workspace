package com.cardtracker;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the local card-image directory, and refuses to start when the
 * default cannot mean what it says.
 *
 * Both sides of that directory resolve through here so they cannot disagree:
 * {@code ImportCardImages} writes thumbnails into it, {@code ApiServer} serves
 * them out of it, and they are only the same directory when both processes
 * were launched from the same place.
 *
 * **Why this refuses rather than resolving against the jar.** The default
 * {@code images} is a relative path, so it names a different directory for
 * every working directory a process is launched from. Launched anywhere but
 * the project root, the importer created a fresh empty tree and downloaded
 * into it while the API went on serving 404s from the real one, and neither side
 * erroring, which is why it went unnoticed for a day (CLAUDE.md, "Run tools
 * from the project root"). Resolving against the jar instead would put images
 * under {@code target/}, which {@code mvn package} is free to wipe, so the
 * directory stays where it is and the launch is checked instead.
 *
 * An explicit {@code CARDTRACKER_IMAGE_DIR} is always honoured unchecked, so a
 * path someone set is a decision, not an accident.
 */
public final class ImageDir {

    private ImageDir() {
    }

    /**
     * Files that together mean "this is the project root". {@code pom.xml}
     * alone would be satisfied by any Maven project on the machine.
     */
    private static final String[] ROOT_MARKERS = {"pom.xml", "sql/schema.sql"};

    /**
     * The image root as an absolute path.
     *
     * @throws IllegalStateException when the default is in play and the
     *         working directory is not the project root. Deliberately fatal,
     *         because every alternative here fails silently.
     */
    public static Path resolve() {
        return resolve(Path.of("").toAbsolutePath().normalize(),
                       System.getenv("CARDTRACKER_IMAGE_DIR"));
    }

    /**
     * The decision itself, with the working directory and the configured path
     * passed in rather than read from the process.
     *
     * Package-private purely so it can be tested: {@code user.dir} cannot be
     * changed once a JVM is running, so a test that could not supply its own
     * cwd would have to reimplement this rule to check it. A test that
     * reimplements the thing it is testing proves nothing.
     */
    static Path resolve(Path cwd, String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }

        for (String marker : ROOT_MARKERS) {
            if (!Files.exists(cwd.resolve(marker))) {
                throw new IllegalStateException(
                        "Refusing to use a relative image directory from " + cwd
                        + " (no " + marker + " here, so this is not the project root).\n"
                        + "The default image directory is relative, so running from here would "
                        + "read or write an images/ tree that the rest of the app never looks at, "
                        + "without either side reporting an error.\n"
                        + "Fix: run from the project root, or set CARDTRACKER_IMAGE_DIR to an "
                        + "absolute path.");
            }
        }
        return cwd.resolve("images").normalize();
    }
}
