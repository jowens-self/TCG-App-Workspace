package com.cardtracker.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The numbers. This is where every figure the owner has ever questioned came
 * from, and where a plausible-looking wrong answer does the most damage.
 *
 * Fixtures are chosen so the right answer can be computed on paper; see
 * Fixtures. Where a case reproduces a specific historical bug, the comment
 * names it.
 */
@EnabledIf("com.cardtracker.sql.TestDatabase#available")
@DisplayName("price math")
class PriceMathSqlTest {

    private static TestDatabase db;

    @BeforeAll
    static void setUp() throws Exception {
        db = TestDatabase.create();
        Fixtures.load(db);

        // A clean flat line, then a genuine step up on the last two days.
        // Days are oldest -> newest, ending at Fixtures.TODAY.
        Fixtures.prices(db, 42382, "Holofoil",
                "100.00", "100.00", "100.00", "100.00", "100.00", "100.00",
                "100.00", "300.00", "300.00", "300.00");

        // A one-day spike that reverts: 50 -> 10 -> 50. The magnitude is huge
        // and the level either side is identical, which is the whole basis for
        // treating it as an error rather than a move.
        Fixtures.prices(db, 42383, "Holofoil",
                "50.00", "50.00", "50.00", "50.00", "50.00", "50.00",
                "50.00", "50.00", "10.00", "50.00");

        // Starts at zero. Percent change must be null, not infinity.
        Fixtures.prices(db, 42384, "Holofoil",
                "0.00", "0.00", "0.00", "0.00", "0.00", "0.00",
                "0.00", "0.00", "0.00", "25.00");

        // market_price absent on the newest day, so mid/low must carry the
        // all-time high, but never high_price.
        Fixtures.prices(db, 42385, "Holofoil",
                "10.00", "12.00", "14.00", "16.00", "18.00", "20.00",
                "22.00", "24.00", "26.00", "null");

        // Sealed product, priced far above every single in the same group.
        // A $8,441 display case was once a third of a set's "total value".
        Fixtures.prices(db, 99001, "Unopened",
                "8000.00", "8000.00", "8000.00", "8000.00", "8000.00",
                "8000.00", "8000.00", "8000.00", "8000.00", "8441.00");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
    }

    // ------------------------------------------------------------------
    // Percent change
    // ------------------------------------------------------------------

    /**
     * Do not "fix" this to 0 or infinity. A card with no starting price has no
     * meaningful return, and inventing one puts a fabricated number on screen.
     */
    @Test
    void percentChangeIsNullFromAZeroStartingPrice() throws Exception {
        String pct = db.scalar("""
            SELECT pct_return::TEXT FROM item_return(
                42384, 'Holofoil', DATE '%s' - 9, DATE '%s')
            """.formatted(Fixtures.TODAY, Fixtures.TODAY));
        assertNull(pct, "a zero starting price must yield null, not 0 or infinity");
    }

    @Test
    void percentChangeIsComputedOnARealMove() throws Exception {
        String pct = db.scalar("""
            SELECT pct_return::TEXT FROM item_return(
                42382, 'Holofoil', DATE '%s' - 9, DATE '%s')
            """.formatted(Fixtures.TODAY, Fixtures.TODAY));
        assertEquals("200.00", pct, "100.00 -> 300.00 is +200%");
    }

    // ------------------------------------------------------------------
    // All-time high
    // ------------------------------------------------------------------

    /**
     * Market price only: mid and low are listing prices, and a MAX over them
     * picked up parked listings (owner, 2026-10-29). Nor may it reach
     * high_price, which the fixtures park at 99,999.
     */
    @Test
    void allTimeHighIsMarketPriceOnly() throws Exception {
        db.exec("""
            INSERT INTO price_snapshots (product_id, sub_type_name, snapshot_date,
                                         market_price, low_price, mid_price, high_price)
            VALUES (42385, 'Holofoil', DATE '%s' - 401, NULL, 400.00, 500.00, 99999.00)
            """.formatted(Fixtures.TODAY));
        try {
            String ath = db.scalar("SELECT all_time_high::TEXT FROM item_all_time_high(42385, 'Holofoil')");
            assertNotNull(ath);
            assertEquals(26.00, Double.parseDouble(ath), 0.001,
                    "a day with only listing prices ($500 mid, $400 low) must not set the high");
        } finally {
            db.exec("DELETE FROM price_snapshots WHERE product_id = 42385 AND snapshot_date = DATE '"
                    + Fixtures.TODAY + "' - 401");
        }
    }

    /**
     * Computed on demand, never stored, so a backfill turning up an older,
     * higher price is reflected immediately rather than leaving a stale value.
     */
    @Test
    void allTimeHighReflectsABackfilledOlderPrice() throws Exception {
        db.exec("""
            INSERT INTO price_snapshots (product_id, sub_type_name, snapshot_date,
                                         market_price, low_price, mid_price, high_price)
            VALUES (42385, 'Holofoil', DATE '%s' - 400, 999.00, 999.00, 999.00, 99999.00)
            """.formatted(Fixtures.TODAY));

        String ath = db.scalar("SELECT all_time_high::TEXT FROM item_all_time_high(42385, 'Holofoil')");
        assertEquals(999.00, Double.parseDouble(ath), 0.001,
                "an older, higher price must raise the all-time high immediately");

        db.exec("DELETE FROM price_snapshots WHERE product_id = 42385 AND snapshot_date = DATE '"
                + Fixtures.TODAY + "' - 400");
    }

    // ------------------------------------------------------------------
    // Set aggregates
    // ------------------------------------------------------------------

    /**
     * is_sealed = FALSE is hardcoded INSIDE set_value/set_return and is not a
     * caller-adjustable parameter. Paldean Fates read $25,100.93 until sealed
     * was excluded; the truth was $2,792.05.
     */
    @Test
    void setValueExcludesSealedProduct() throws Exception {
        String total = db.scalar(
                "SELECT total_value::TEXT FROM set_value(604, DATE '" + Fixtures.TODAY + "')");
        assertNotNull(total);
        double value = Double.parseDouble(total);
        assertTrue(value < 8000,
                "an 8441.00 sealed box must not be inside a set total, got " + value);
    }

    /**
     * "Current" means the newest day the database HOLDS, not today.
     *
     * set_value() defaulted its as-of date to CURRENT_DATE until 2026-08-22,
     * which made the no-argument call (the one README documents) return
     * NULL for most of every day: pulls are on demand, so the newest snapshot
     * is routinely yesterday with nothing wrong, and the container reads UTC
     * where the host may not. Live at the time of the fix, set_value(2848)
     * gave 0 variants / NULL against 369 / $8,430.52 anchored on the data.
     *
     * The fixture makes this permanently detectable rather than luckily so:
     * Fixtures.TODAY is a fixed 2026-08-19 and recedes further into the past
     * with every real day, so a CURRENT_DATE default can never accidentally
     * agree with it.
     */
    @Test
    void setValueWithNoDateReadsTheNewestDayHeldNotToday() throws Exception {
        String dated = db.scalar(
                "SELECT total_value::TEXT FROM set_value(604, DATE '" + Fixtures.TODAY + "')");
        String defaulted = db.scalar("SELECT total_value::TEXT FROM set_value(604)");

        assertNotNull(defaulted,
                "the no-argument call must find the newest day held, not ask for today");
        assertEquals(dated, defaulted,
                "defaulting the as-of date must equal naming the newest day held");
    }

    /** An as-of date given explicitly is still obeyed, empty answer included. */
    @Test
    void setValueStillHonoursAnExplicitDate() throws Exception {
        String future = db.scalar(
                "SELECT total_value::TEXT FROM set_value(604, DATE '" + Fixtures.TODAY + "' + 1)");
        assertNull(future,
                "a day the set has no prices on must report none, not fall back to another day");
    }

    @Test
    void aSealedProductIsStillTrackableOnItsOwn() throws Exception {
        String ath = db.scalar("SELECT all_time_high::TEXT FROM item_all_time_high(99001, 'Unopened')");
        assertEquals(8441.00, Double.parseDouble(ath), 0.001,
                "excluding sealed from set totals must not make it untrackable");
    }

    // ------------------------------------------------------------------
    // Movers: median, and the window/smoothing relationship
    // ------------------------------------------------------------------

    /**
     * A mean damps a real move and leaks prices across a step; measured on live
     * data, a genuine 41.10 -> 123.32 step (+200.0%) was reported as +80.0%.
     * A median over the same data reports it exactly, which is what this
     * fixture reproduces: seven days at 100, then three at 300.
     */
    @Test
    void moversUsesAMedianSoARealStepIsReportedExactly() throws Exception {
        String pct = db.scalar("""
            SELECT ROUND(pct_change, 2)::TEXT FROM item_movers(
                7, 3, NULL, FALSE, 1, 'up', 3, 50, DATE '%s')
            WHERE product_id = 42382
            """.formatted(Fixtures.TODAY));
        assertEquals("200.00", pct,
                "a median must report the step exactly; a mean would damp it");
    }

    /**
     * The clamp added 2026-08-20. 'recent' spans [end-(smooth-1), end] and
     * 'prior' spans [end-days-(smooth-1), end-days], so they stay disjoint only
     * while smooth <= days. At the default smoothing of 3 a 1-day window
     * compared two medians sharing two of their three days, damping every real
     * move toward zero.
     */
    @Test
    void aOneDayWindowDoesNotCompareOverlappingMedians() throws Exception {
        // 42383 sits at 10.00 yesterday and 50.00 today, a true 1-day move of
        // +400%. With overlapping windows this reads far smaller.
        String pct = db.scalar("""
            SELECT ROUND(pct_change, 2)::TEXT FROM item_movers(
                1, 3, NULL, FALSE, 1, 'up', 3, 50, DATE '%s')
            WHERE product_id = 42383
            """.formatted(Fixtures.TODAY));
        assertEquals("400.00", pct,
                "smoothing must clamp to the window, or the two medians share days");
    }

    /** Sealed is excluded from the board by default, same reasoning as sets. */
    @Test
    void moversExcludesSealedByDefault() throws Exception {
        String found = db.scalar("""
            SELECT COUNT(*)::TEXT FROM item_movers(
                7, 3, NULL, FALSE, 1, 'up', 3, 50, DATE '%s')
            WHERE product_id = 99001
            """.formatted(Fixtures.TODAY));
        assertEquals("0", found);
    }

    @Test
    void moversCanIncludeSealedWhenAskedTo() throws Exception {
        String found = db.scalar("""
            SELECT COUNT(*)::TEXT FROM item_movers(
                7, 3, NULL, TRUE, 1, 'up', 3, 50, DATE '%s')
            WHERE product_id = 99001
            """.formatted(Fixtures.TODAY));
        assertEquals("1", found, "include_sealed = TRUE must actually include it");
    }

    /**
     * The min-price gate exists because a card moving from $0.02 to $0.10 is
     * +400% and worth nothing to a seller.
     */
    @Test
    void moversRespectsTheMinimumStartingPrice() throws Exception {
        String found = db.scalar("""
            SELECT COUNT(*)::TEXT FROM item_movers(
                7, 3, NULL, FALSE, 500, 'up', 3, 50, DATE '%s')
            WHERE product_id = 42382
            """.formatted(Fixtures.TODAY));
        assertEquals("0", found, "a 100.00 starting price is below a 500 floor");
    }
}
