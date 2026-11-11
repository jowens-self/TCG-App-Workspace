package com.cardtracker.sql;

import com.cardtracker.db.PriceRepository;
import com.cardtracker.model.Group;
import com.cardtracker.model.PriceSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guarantees that protect the history itself.
 *
 * `price_snapshots` is append-only and there is no way back from a bad write,
 * so these are the assertions where a regression is unrecoverable rather than
 * merely wrong.
 */
@EnabledIf("com.cardtracker.sql.TestDatabase#available")
@DisplayName("ingestion")
class IngestionSqlTest {

    private static TestDatabase db;
    private static PriceRepository repo;

    @BeforeAll
    static void setUp() throws Exception {
        db = TestDatabase.create();
        Fixtures.load(db);
        repo = new PriceRepository(db.conn());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
    }

    private static PriceSnapshot snapshot(int productId, String market) {
        return new PriceSnapshot(productId, "Holofoil", LocalDate.parse(Fixtures.TODAY),
                new BigDecimal(market), new BigDecimal(market), new BigDecimal(market),
                new BigDecimal("99999.00"), null);
    }

    private String storedPrice(int productId) throws Exception {
        return db.scalar("SELECT market_price::TEXT FROM price_snapshots WHERE product_id = "
                + productId + " AND snapshot_date = DATE '" + Fixtures.TODAY + "'");
    }

    // ------------------------------------------------------------------

    /**
     * Re-running a pull must be safe. This is what makes "missing a day costs
     * nothing" true, and what lets a premature run become a free no-op now
     * that the date comes from the mirror.
     */
    @Test
    void insertingTheSameDayTwiceWritesNothingTheSecondTime() throws Exception {
        int first = repo.insertBatch(List.of(snapshot(42382, "100.00")));
        int second = repo.insertBatch(List.of(snapshot(42382, "100.00")));

        assertEquals(1, first, "the first insert should write one row");
        assertEquals(0, second, "a repeat must write nothing");
        assertEquals("100.00", storedPrice(42382));
    }

    /**
     * The one that cannot be allowed to regress. ON CONFLICT DO NOTHING, never
     * DO UPDATE, is what stops a bad run from destroying a good observation.
     * The history is the whole asset.
     */
    @Test
    void aLaterRunCanNeverOverwriteAnObservation() throws Exception {
        repo.insertBatch(List.of(snapshot(42383, "50.00")));
        int written = repo.insertBatch(List.of(snapshot(42383, "999.00")));

        assertEquals(0, written, "a conflicting row must be discarded, not applied");
        assertEquals("50.00", storedPrice(42383),
                "the original observation must survive a contradicting re-run");
    }

    /**
     * Sub-types are separate markets. Normal / Holofoil / Reverse Holofoil of
     * one product genuinely trade at different prices, so they must never
     * collapse onto each other.
     */
    @Test
    void variantsOfOneProductAreSeparateRows() throws Exception {
        repo.insertBatch(List.of(snapshot(42384, "10.00")));
        repo.insertBatch(List.of(new PriceSnapshot(42384, "Reverse Holofoil",
                LocalDate.parse(Fixtures.TODAY), new BigDecimal("77.00"),
                new BigDecimal("77.00"), new BigDecimal("77.00"),
                new BigDecimal("99999.00"), null)));

        assertEquals("2", db.scalar(
                "SELECT COUNT(*)::TEXT FROM price_snapshots WHERE product_id = 42384 "
                + "AND snapshot_date = DATE '" + Fixtures.TODAY + "'"),
                "one product, two sub-types, two rows");
    }

    /**
     * excluded_products is standalone on purpose: BackfillArchive only ever
     * sees a bare product_id from an archive, never a name, so once an
     * excluded product leaves `products` there would be no way to know to skip
     * it. It records category_id too, so a quirk found in one game is never
     * mistaken for a universal rule.
     */
    @Test
    void anExclusionSurvivesTheProductRowBeingGone() throws Exception {
        db.exec("""
            INSERT INTO excluded_products (product_id, category_id, name, reason)
            VALUES (555001, 3, 'Code Card - Test', 'code card')
            """);

        assertTrue(repo.excludedProductIds().contains(555001),
                "an exclusion must be readable without the product row existing");

        assertEquals("3", db.scalar(
                "SELECT category_id::TEXT FROM excluded_products WHERE product_id = 555001"),
                "category is recorded so the quirk stays scoped to the game it came from");
    }

    /**
     * A pull where the catalog sends no date (see
     * TcgCsvPriceSource.releaseDate) must not erase one we already hold, and
     * the date must come back as the same calendar day it went in.
     */
    @Test
    void aSetKeepsItsReleaseDateWhenTheCatalogStopsSendingIt() throws Exception {
        repo.upsertGroup(new Group(990001, 3, "SWSH01: Sword & Shield Base Set", "SWSH01", false,
                LocalDate.of(2020, 2, 7), null));
        repo.upsertGroup(new Group(990001, 3, "SWSH01: Sword & Shield Base Set", "SWSH01", false,
                null, null));

        assertEquals("2020-02-07", db.scalar(
                "SELECT published_on::TEXT FROM groups WHERE group_id = 990001"));
    }
}
