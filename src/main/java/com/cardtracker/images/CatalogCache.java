package com.cardtracker.images;

import com.cardtracker.source.pokemontcg.PokemonTcgCatalog.Card;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * On-disk copy of the pokemontcg.io card catalog.
 *
 * Exists because downloading it takes 3-5 minutes and ~174 requests, and the
 * importer needs it on every invocation, including a `--group` run that only
 * cares about a hundred cards. That tax made importing set-by-set impractical,
 * which is the natural way to do it given how long a full run takes.
 *
 * **Only a complete catalog is ever cached.** A partial one would be worse
 * than none: it would be reused for a week while silently missing sets, and
 * an unmatched product would look like a genuine miss rather than a set we
 * failed to download. The caller enforces this by saving only when no set
 * failed.
 *
 * The TTL is deliberately long. New Pokemon sets appear every few months and
 * the upstream catalog lags them anyway, so a week-old copy costs nothing
 * real. Delete the file to force a refresh.
 */
public class CatalogCache {

    private static final Duration TTL = Duration.ofDays(7);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public CatalogCache(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Cached cards, or null when absent, stale, or unreadable. */
    public List<Card> load() {
        try {
            if (!Files.isRegularFile(file)) return null;
            if (age().compareTo(TTL) > 0) return null;
            List<Card> cards = mapper.readValue(Files.readAllBytes(file),
                    new TypeReference<List<Card>>() {
                    });
            // A truncated or empty file is treated as a miss rather than an
            // error. Re-downloading is always safe, so there is no reason to
            // fail the run over a bad cache.
            return (cards == null || cards.isEmpty()) ? null : cards;
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort write; a failure here must never fail the import. */
    public void save(List<Card> cards) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, mapper.writeValueAsBytes(cards));
        } catch (Exception e) {
            System.out.printf("  (could not cache catalog: %s)%n", e.getMessage());
        }
    }

    public String ageDescription() {
        try {
            long h = age().toHours();
            return h < 1 ? "under an hour" : h + "h";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private Duration age() throws Exception {
        return Duration.between(Files.getLastModifiedTime(file).toInstant(), Instant.now());
    }
}
