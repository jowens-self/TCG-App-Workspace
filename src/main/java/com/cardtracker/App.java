package com.cardtracker;

import com.cardtracker.db.PriceRepository;
import com.cardtracker.model.Category;
import com.cardtracker.model.Group;
import com.cardtracker.model.PriceSnapshot;
import com.cardtracker.model.Product;
import com.cardtracker.source.PriceSource;
import com.cardtracker.source.tcgcsv.TcgCsvPriceSource;
import com.cardtracker.source.tcgcsv.TcgCsvUrls;
import com.cardtracker.source.tcgcsv.Throttle;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Daily price pull, for every category in `tracked_categories` (see
 * sql/schema.sql, where adding a card game is an INSERT, not a code change).
 *
 * Meant to run once a day, after the mirror's ~20:00 UTC refresh. Rerunning
 * is free: a game that already holds the mirror's day is skipped.
 */
public class App {

    public static void main(String[] args) throws Exception {
        PriceSource source = new TcgCsvPriceSource(Throttle.politeDefault());

        // Resolved from the mirror, not from this machine's clock. The UTC
        // rule below is still right and still applies everywhere else. This
        // is a different problem to the host-timezone bug, and fixing that
        // one could not have prevented this one.
        //
        // The live price endpoints carry no date: they answer "what are the
        // prices now". tcgcsv refreshes once daily at ~20:00 UTC, so between
        // midnight and that refresh "now" is still yesterday's data, and
        // stamping it with today's date writes a day that never happened.
        // That is exactly what a 02:15 UTC run did on 2026-08-20, storing
        // 51,287 rows identical to 2026-08-19.
        //
        // The archive path never had this problem because its URL names the
        // date (prices-2026-08-19.ppmd.7z). This gives the live path the same
        // property: ask the source what day it is serving, then trust the
        // answer. A premature run now re-writes a day we already hold, which
        // ON CONFLICT DO NOTHING makes a free no-op, so being early is safe
        // rather than corrupting.
        LocalDate snapshotDate = source.dataAsOf();
        System.out.printf("Mirror's newest data is %s (this machine: %s UTC)%n",
                snapshotDate, LocalDate.now(ZoneOffset.UTC));

        try (Connection conn = Env.connect()) {
            PriceRepository repo = new PriceRepository(conn);
            List<Integer> categoryIds = repo.trackedCategoryIds();

            boolean anyFailed = false;

            // No archive gap catch-up. Until 2026-09-30 this ran on every pull
            // and made a missed day free: BackfillArchive refilled it from
            // tcgcsv's daily archives. tcgcsv has since taken the archive down
            // (server costs and moderation burden; the notice is served as the
            // body of every archive URL's 403) and asks clients to use the live
            // category/group/price endpoints instead. From ~2026-09-08 the
            // catch-up died on its first download every run, which is what made
            // every pull exit 1 for three weeks while the data itself was fine.
            //
            // So a day that is not pulled while the mirror is serving it is now
            // gone for good. BackfillArchive stays in the tree as a manual tool
            // for the day the archive comes back; re-enabling it here is a
            // decision to make then, against whatever terms it returns under.

            for (int categoryId : categoryIds) {
                try {
                    pullCategory(source, repo, categoryId, snapshotDate);
                } catch (Exception e) {
                    anyFailed = true;
                    System.out.printf("[%d] FAILED: %s: %s%n",
                            categoryId, e.getClass().getSimpleName(), e.getMessage());
                }
            }
            if (anyFailed) {
                throw new IllegalStateException(
                        "One or more tracked categories failed - see ingest_runs for details.");
            }
        }
    }

    /**
     * One category's full live pull: catalog (category/groups/products) and
     * the prices for the mirror's day (snapshotDate, from dataAsOf(), never
     * this machine's clock). Failures here are caught by the caller so one
     * category failing doesn't stop the others from being attempted.
     */
    private static void pullCategory(PriceSource source, PriceRepository repo, int categoryId,
            LocalDate snapshotDate) throws Exception {
        String runSource = "tcgcsv-" + categoryId;

        // This is what makes retries free. Any ingest path counts,
        // including the days BackfillArchive filled before it went dormant.
        if (repo.hasDateCovered(categoryId, snapshotDate)) {
            System.out.printf("[%d] Already have %s - skipping fetch.%n",
                    categoryId, snapshotDate);
            return;
        }

        // Throttle is shared across every category on purpose. The
        // 9000-request circuit breaker has to apply to the whole process,
        // not reset per category. requestsUsed() is therefore cumulative,
        // so this category's own count is a delta, not the running total.
        int requestsBefore = source.requestsUsed();
        long runId = repo.startRun(runSource, snapshotDate);
        int totalRows = 0;

        try {
            Category category = source.categories().stream()
                    .filter(c -> c.categoryId() == categoryId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("category " + categoryId + " not found"));
            repo.upsertCategory(category);

            List<Group> groups = source.groups(categoryId);
            System.out.printf("[%d] Found %d groups%n", categoryId, groups.size());

            for (Group g : groups) {
                repo.upsertGroup(g);

                List<Product> products = source.products(categoryId, g.groupId());
                Set<Integer> excludedInGroup = new HashSet<>();
                for (Product p : products) {
                    // Code cards are a Pokemon-specific quirk, a digital TCG Live
                    // redemption bundled in some sealed Pokemon product,
                    // not something to assume any other game also has.
                    // Other categories may turn up their own quirks later;
                    // add them the same way (excluded_products + reason)
                    // rather than folding everything into this one check.
                    if (categoryId == TcgCsvUrls.POKEMON && isCodeCard(p.name())) {
                        repo.upsertExcludedProduct(p.productId(), categoryId, p.name(), "code_card");
                        excludedInGroup.add(p.productId());
                        continue;
                    }
                    repo.upsertProduct(p);
                }

                List<PriceSnapshot> prices = source.pricesForGroup(categoryId, g.groupId(), snapshotDate)
                        .stream()
                        .filter(ps -> !excludedInGroup.contains(ps.productId()))
                        .toList();
                int written = repo.insertBatch(prices);
                totalRows += written;
                System.out.printf("  %-45s %5d products, %5d prices, %5d new%n",
                        truncate(g.name(), 45), products.size() - excludedInGroup.size(), prices.size(), written);
            }

            int requestsForCategory = source.requestsUsed() - requestsBefore;
            repo.finishRun(runId, "ok", requestsForCategory, totalRows, null);
            System.out.printf("[%d] Done. %d rows written in %d requests.%n%n",
                    categoryId, totalRows, requestsForCategory);

        } catch (Exception e) {
            // Record the failure before rethrowing, so a broken run is
            // visible in the database rather than only in the console.
            int requestsForCategory = source.requestsUsed() - requestsBefore;
            repo.finishRun(runId, "failed", requestsForCategory, totalRows,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * Digital code-card redemptions, a separate TCGplayer catalog listing
     * for the online code packed inside some sealed product, not a physical
     * card. Behaves nothing like a real card price-wise, so it's excluded everywhere, not just filtered
     * at query time. Pokemon-specific; see the call site.
     */
    static boolean isCodeCard(String name) {
        return name != null && name.regionMatches(true, 0, "Code Card", 0, "Code Card".length());
    }
}
