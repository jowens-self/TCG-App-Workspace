package com.cardtracker;

import com.cardtracker.db.PriceRepository;
import com.cardtracker.model.PriceSnapshot;
import com.cardtracker.source.tcgcsv.TcgCsvPriceSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Dormant: tcgcsv withdrew its daily archives in September 2026 and every
 * archive URL now answers 403. Kept for the day they return; running it again
 * is a decision to make then (CLAUDE.md).
 *
 * One-time (or resumable) backfill from tcgcsv's daily archives, covering
 * 2024-02-08 onward. Each archive bundles every TCG category for one day,
 * so one download per day serves every category in `tracked_categories`,
 * not just Pokemon. See CLAUDE.md.
 *
 * Idempotent by (category, day): before touching a date for a category,
 * checks ingest_runs for a prior "ok" run with source sourceFor(categoryId).
 * If a day is already covered for every tracked category, it's skipped
 * without downloading anything, so re-running this after a crash, months
 * later to catch up, or right after a new category is added to
 * tracked_categories, only does the (category, day) pairs it doesn't
 * already have.
 *
 * Requires the `7z` CLI on PATH (brew install p7zip). Not part of the daily
 * App run. This is a separate, occasional batch job.
 */
public final class BackfillArchive {

    public static final LocalDate EARLIEST_ARCHIVE = LocalDate.of(2024, 2, 8);

    /** Category-scoped so each tracked game gets independent idempotency/gap tracking. */
    public static String sourceFor(int categoryId) {
        return "tcgcsv-archive-" + categoryId;
    }

    public static void main(String[] args) throws Exception {
        LocalDate start = args.length > 0 ? LocalDate.parse(args[0]) : EARLIEST_ARCHIVE;
        // UTC, not host-local, matching App's snapshot_date convention.
        LocalDate end = args.length > 1 ? LocalDate.parse(args[1]) : LocalDate.now(ZoneOffset.UTC).minusDays(1);

        try (Connection conn = Env.connect()) {
            run(start, end, new PriceRepository(conn));
        }
    }

    /**
     * Backfills [start, end], for every category in tracked_categories, into
     * repo, skipping any (category, day) that already has a successful run.
     * Safe to call with a wide range even when most of it is already
     * covered: each already-done (category, day) costs one indexed lookup,
     * not a download, and a day already covered for every tracked category
     * is skipped before any download happens at all.
     */
    public static void run(LocalDate start, LocalDate end, PriceRepository repo) throws Exception {
        List<Integer> categoryIds = repo.trackedCategoryIds();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        ObjectMapper mapper = new ObjectMapper();
        Path workDir = Files.createTempDirectory("cardtracker-backfill");
        // Archives only ever carry a bare product_id, never a name, so
        // exclusion here can only go by ID -
        // sourced from what App has already recorded via upsertExcludedProduct.
        // Global across categories: a product_id belongs to exactly one
        // category already, so this needs no per-category filtering.
        Set<Integer> excludedIds = repo.excludedProductIds();

        int skipped = 0, done = 0, failed = 0;
        try {
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                List<Integer> pending = new ArrayList<>();
                for (int categoryId : categoryIds) {
                    if (repo.hasSuccessfulRun(sourceFor(categoryId), date)) {
                        skipped++;
                    } else {
                        pending.add(categoryId);
                    }
                }
                if (pending.isEmpty()) {
                    continue;
                }

                Path archive = workDir.resolve(date + ".ppmd.7z");
                Path extractDir = workDir.resolve(date + "-extracted");
                try {
                    downloadArchive(http, date, archive);
                    extract(archive, extractDir);

                    for (int categoryId : pending) {
                        long runId = repo.startRun(sourceFor(categoryId), date);
                        int rowsForDay = 0;
                        try {
                            Path categoryDir = extractDir.resolve(date.toString()).resolve(String.valueOf(categoryId));
                            if (Files.isDirectory(categoryDir)) {
                                try (var groupDirs = Files.list(categoryDir)) {
                                    for (Path groupDir : (Iterable<Path>) groupDirs::iterator) {
                                        Path pricesFile = groupDir.resolve("prices");
                                        if (!Files.isRegularFile(pricesFile)) continue;

                                        JsonNode root = mapper.readTree(Files.readString(pricesFile));
                                        if (!root.path("success").asBoolean(false)) continue;

                                        List<PriceSnapshot> snapshots =
                                                TcgCsvPriceSource.toPriceSnapshots(root.path("results"), date)
                                                        .stream()
                                                        .filter(ps -> !excludedIds.contains(ps.productId()))
                                                        .toList();
                                        rowsForDay += repo.insertBatch(snapshots);
                                    }
                                }
                            }

                            repo.finishRun(runId, "ok", 0, rowsForDay, null);
                            done++;
                            System.out.printf("%s [%d]: %d rows%n", date, categoryId, rowsForDay);
                        } catch (Exception e) {
                            repo.finishRun(runId, "failed", 0, rowsForDay,
                                    e.getClass().getSimpleName() + ": " + e.getMessage());
                            failed++;
                            System.out.printf("%s [%d]: FAILED - %s%n", date, categoryId, e.getMessage());
                        }
                    }
                } finally {
                    deleteRecursive(archive);
                    deleteRecursive(extractDir);
                }

                // Politeness pacing for the archive CDN, distinct from Throttle,
                // which enforces tcgcsv's documented live-API limits specifically.
                // Once per day regardless of how many categories were processed
                // from that day's shared download.
                Thread.sleep(300);
            }
        } finally {
            deleteRecursive(workDir);
        }

        System.out.printf("%nDone. %d already had, %d backfilled, %d failed.%n", skipped, done, failed);
    }

    private static void downloadArchive(HttpClient http, LocalDate date, Path dest) throws Exception {
        String archiveUrl = "https://tcgcsv.com/archive/tcgplayer/prices-" + date + ".ppmd.7z";
        HttpRequest req = HttpRequest.newBuilder(URI.create(archiveUrl))
                .header("User-Agent", TcgCsvPriceSource.USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + archiveUrl);
        }
    }

    private static void extract(Path archive, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        Process p = new ProcessBuilder("7z", "x", archive.toString(), "-o" + destDir, "-y")
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes(); // drain so the process can't block on a full output pipe
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("7z exited " + exit + " for " + archive);
        }
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of our own temp dir
                }
            });
        }
    }

}
