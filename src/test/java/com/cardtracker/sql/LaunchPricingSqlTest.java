package com.cardtracker.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pre-release and early ("still settling") prices: which prices count as
 * which, and that the trend screens leave them out unless asked.
 *
 * Its own database, because the cards here need histories of very different
 * lengths, and the earliest price in the table is part of the rule.
 *
 * The cast, all relative to Fixtures.TODAY:
 *   990401  old promo, priced since history began (TODAY-119): settled
 *   990301  card from a set released TODAY-5, preorder-priced from TODAY-20
 *   990402  new promo filed in a promo group dated 400 days ago, first
 *           priced TODAY-30: its own release is its first price
 * Every one of them drops over the last week, so only the launch rules
 * decide who is on the board.
 */
@EnabledIf("com.cardtracker.sql.TestDatabase#available")
@DisplayName("launch pricing")
class LaunchPricingSqlTest {

    private static TestDatabase db;

    @BeforeAll
    static void setUp() throws Exception {
        db = TestDatabase.create();
        Fixtures.load(db);
        String today = "DATE '" + Fixtures.TODAY + "'";
        db.exec("""
            INSERT INTO groups (group_id, category_id, name, published_on) VALUES
                (990300, 3, 'ME: Brand New Set', %1$s - 5),
                (990400, 3, 'ME: Long-Running Promos', %1$s - 400);
            INSERT INTO products (product_id, group_id, category_id, name, is_sealed) VALUES
                (990301, 990300, 3, 'New Set Card', FALSE),
                (990401, 990400, 3, 'Old Promo', FALSE),
                (990402, 990400, 3, 'New Promo', FALSE);
            """.formatted(today));

        Fixtures.prices(db, 990401, "Holofoil", days(113, "10.00", 7, "5.00"));
        Fixtures.prices(db, 990301, "Holofoil", days(15, "60.00", 6, "40.00"));
        Fixtures.prices(db, 990402, "Holofoil", days(24, "30.00", 7, "15.00"));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
    }

    /** {@code before} days at one price, then {@code after} days at another, ending TODAY. */
    private static String[] days(int before, String priceBefore, int after, String priceAfter) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < before; i++) out.add(priceBefore);
        for (int i = 0; i < after; i++) out.add(priceAfter);
        return out.toArray(new String[0]);
    }

    private static String phaseOn(int productId, int daysAgo) throws Exception {
        return db.scalar("""
            SELECT COALESCE(phase, 'settled') FROM item_history(%d, 'Holofoil')
            WHERE snapshot_date = DATE '%s' - %d
            """.formatted(productId, Fixtures.TODAY, daysAgo));
    }

    private static String dropsBoard(String launchArgs) throws Exception {
        return db.scalar("""
            SELECT string_agg(product_id::TEXT, ',' ORDER BY product_id) FROM item_movers(
                7, 3, NULL, FALSE, 1, 'down', 3, 50, DATE '%s'%s)
            """.formatted(Fixtures.TODAY, launchArgs));
    }

    // ------------------------------------------------------------------

    /** A preorder price is pre-release; from release day it is early. */
    @Test
    void aSetCardIsReleasedWithItsSet() throws Exception {
        assertEquals("pre_release", phaseOn(990301, 20), "a preorder, 15 days before release");
        assertEquals("pre_release", phaseOn(990301, 6), "the day before release");
        assertEquals("early", phaseOn(990301, 5), "release day");
        assertEquals("early", phaseOn(990301, 0));
    }

    /**
     * The 30th Celebration promos sit in a promo group dated 2025. Going by
     * the group, they'd count as long settled; going by their own first
     * price, they are new.
     */
    @Test
    void aPromoAddedToAnOldGroupIsNewFromItsFirstPrice() throws Exception {
        assertEquals("early", phaseOn(990402, 30));
        assertEquals("early", phaseOn(990402, 0));
    }

    /** Priced since before our history begins: nothing to know, nothing filtered. */
    @Test
    void aCardPricedSinceHistoryBeganIsSettled() throws Exception {
        assertEquals("settled", phaseOn(990401, 119));
        assertEquals("settled", phaseOn(990401, 0));
    }

    @Test
    void moversLeavesLaunchPricingOutByDefault() throws Exception {
        assertEquals("990401", dropsBoard(""));
    }

    /** Each checkbox brings back its own part, and only that part. */
    @Test
    void eachCheckboxBringsBackOnlyItsOwnPart() throws Exception {
        assertEquals("990301,990401", dropsBoard(", p_include_prerelease => TRUE"),
                "the new set card's week-ago price was a preorder");
        assertEquals("990401,990402", dropsBoard(", p_include_early => TRUE"),
                "the new promo's week-ago price was early, not pre-release");
        assertEquals("990301,990401,990402",
                dropsBoard(", p_include_prerelease => TRUE, p_include_early => TRUE"));
    }

    /**
     * The case that started this: a new set's change computed on preorder
     * prices. Its value still counts the card, since what it is worth today is
     * real either way; only the change leaves it out.
     */
    @Test
    void aNewSetShowsItsValueButNoChangeYet() throws Exception {
        String row = db.scalar("""
            SELECT n_priced || '|' || n_common || '|' || COALESCE(pct_return::TEXT, 'none')
                   || '|' || (settles_on - DATE '%1$s')
            FROM game_set_overview(3, 7, DATE '%1$s') WHERE group_id = 990300
            """.formatted(Fixtures.TODAY));
        assertEquals("1|0|none|51", row, "priced, not compared, settles 56 days after release");

        assertEquals("-33.33", db.scalar("""
            SELECT pct_return::TEXT FROM game_set_overview(3, 7, DATE '%s',
                p_include_prerelease => TRUE, p_include_early => TRUE)
            WHERE group_id = 990300
            """.formatted(Fixtures.TODAY)), "ticking both gives the raw comparison back");
    }

    /**
     * card_first_priced is kept by a trigger, so every way prices arrive keeps
     * it right, and it only ever moves a date earlier.
     */
    @Test
    void theTriggerKeepsTheEarliestPrice() throws Exception {
        String insert = "INSERT INTO price_snapshots (product_id, sub_type_name, snapshot_date, "
                + "market_price) VALUES (990499, 'Normal', DATE '" + Fixtures.TODAY + "' - %d, 1)";
        String first = "SELECT (DATE '" + Fixtures.TODAY + "' - first_date)::TEXT "
                + "FROM card_first_priced WHERE product_id = 990499";

        db.exec(insert.formatted(3));
        assertEquals("3", db.scalar(first));
        db.exec(insert.formatted(10));
        assertEquals("10", db.scalar(first), "an older day replayed moves it earlier");
        db.exec(insert.formatted(1));
        assertEquals("10", db.scalar(first), "a newer day never moves it later");
    }

    /** Checked against the table it summarises, not assumed. */
    @Test
    void firstPricesMatchTheHistory() throws Exception {
        assertNull(db.scalar("""
            SELECT string_agg(ps.product_id::TEXT, ',') FROM (
                SELECT product_id, sub_type_name, MIN(snapshot_date) AS d
                FROM price_snapshots GROUP BY 1, 2) ps
            LEFT JOIN card_first_priced f USING (product_id, sub_type_name)
            WHERE f.first_date IS DISTINCT FROM ps.d
            """), "every card's recorded first price must be its earliest row");
    }
}
