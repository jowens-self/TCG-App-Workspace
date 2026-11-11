package com.cardtracker.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * item_search(): name search over products, one row per product.
 *
 * The rules worth a test are the ones a result list silently depends on:
 * card number and rarity ride on every row (one set reprints a card at
 * several rarities), sealed and game filters actually filter, and a product
 * with several variants is still one hit.
 */
@EnabledIf("com.cardtracker.sql.TestDatabase#available")
@DisplayName("item_search")
class ItemSearchSqlTest {

    private static TestDatabase db;

    @BeforeAll
    static void setUp() throws Exception {
        db = TestDatabase.create();
        Fixtures.load(db);
        // Search only returns products that have price history.
        for (int id : new int[]{42382, 42383, 42384, 42385, 42386, 42387, 42388,
                                479852, 900001, 99001}) {
            Fixtures.prices(db, id, "Holofoil", "10.00", "10.00");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
    }

    private String count(String query) throws Exception {
        return db.scalar("SELECT COUNT(*)::TEXT FROM item_search("
                + "'" + query.replace("'", "''") + "', NULL, TRUE, 50, 0)");
    }

    // ------------------------------------------------------------------

    @Test
    void findsByPlainName() throws Exception {
        assertTrue(Integer.parseInt(count("Charizard")) >= 2,
                "two Charizards exist in the fixture set");
    }

    /**
     * A set commonly reprints one card at several rarities across a huge price
     * range. SV03 has four Charizard ex from $5.65 to $108.58. Without these
     * fields a result list shows identical rows.
     */
    @Test
    void resultsCarryCardNumberAndRarity() throws Exception {
        String row = db.scalar("""
            SELECT card_number || '|' || rarity FROM item_search('Charizard', NULL, TRUE, 50, 0)
            WHERE product_id = 42382
            """);
        assertEquals("4/102|Holo Rare", row,
                "read from extendedData, never parsed out of the name");
    }

    /** A full name is matched as typed, punctuation and all. */
    @Test
    void aFullNameWithPunctuationMatchesAsTyped() throws Exception {
        assertEquals("1", count("Monkey D. Luffy"));
    }

    /** Leading and trailing spaces are what a search box sends, not part of the name. */
    @Test
    void surroundingSpacesAreIgnored() throws Exception {
        assertEquals(count("Charizard"), count("  Charizard "));
    }

    @Test
    void sealedCanBeExcluded() throws Exception {
        String withSealed = db.scalar(
                "SELECT COUNT(*)::TEXT FROM item_search('Booster Box', NULL, TRUE, 50, 0)");
        String withoutSealed = db.scalar(
                "SELECT COUNT(*)::TEXT FROM item_search('Booster Box', NULL, FALSE, 50, 0)");
        assertEquals("1", withSealed);
        assertEquals("0", withoutSealed, "singles-only must drop sealed product");
    }

    @Test
    void searchCanBeScopedToOneGame() throws Exception {
        String pokemonOnly = db.scalar(
                "SELECT COUNT(*)::TEXT FROM item_search('Luffy', 3, TRUE, 50, 0)");
        assertEquals("0", pokemonOnly, "a One Piece card must not appear under Pokemon");
    }

    /**
     * One hit per product carrying variant_count. Anything that puts a single
     * "price" on a multi-variant product violates the sub-type invariant.
     */
    @Test
    void oneRowPerProductNotPerVariant() throws Exception {
        db.exec("""
            INSERT INTO price_snapshots (product_id, sub_type_name, snapshot_date,
                                         market_price, low_price, mid_price, high_price)
            VALUES (42382, 'Reverse Holofoil', DATE '%s', 77.00, 77.00, 77.00, 99999.00)
            """.formatted(Fixtures.TODAY));

        String rows = db.scalar(
                "SELECT COUNT(*)::TEXT FROM item_search('Charizard', NULL, TRUE, 50, 0) "
                + "WHERE product_id = 42382");
        assertEquals("1", rows, "two variants must still be one search result");

        String variants = db.scalar(
                "SELECT variant_count::TEXT FROM item_search('Charizard', NULL, TRUE, 50, 0) "
                + "WHERE product_id = 42382");
        assertEquals("2", variants, "the row must report how many variants exist");
    }

    /**
     * A picture that is provably another printing's says so on its row, and
     * names whose it is; the printing it belongs to says nothing.
     * Both claim one catalog picture, and only one is qualified.
     */
    @Test
    void aBorrowedPictureIsMarkedOnItsRowOnly() throws Exception {
        db.exec("""
            INSERT INTO products (product_id, group_id, category_id, name, is_sealed, url) VALUES
              (990600, 604, 3, 'Zapdos', FALSE, 'https://x/z'),
              (990601, 604, 3, 'Zapdos (Prerelease)', FALSE, 'https://x/zp');
            INSERT INTO card_images (product_id, source_id, status, file_path, bytes, stripped_qualifier) VALUES
              (990600, 'base1-16', 'ok', '3/990600.jpg', 5, NULL),
              (990601, 'base1-16', 'ok', '3/990601.jpg', 5, 'Prerelease');
            """);
        Fixtures.prices(db, 990600, "Holofoil", "10.00", "10.00");
        Fixtures.prices(db, 990601, "Holofoil", "10.00", "10.00");

        assertEquals("Zapdos|false|-,Zapdos (Prerelease)|true|Zapdos", db.scalar("""
            SELECT string_agg(name || '|' || art_borrowed || '|' || COALESCE(art_owner_name, '-'),
                              ',' ORDER BY product_id)
            FROM item_search('Zapdos', NULL, TRUE, 50, 0)
            """));
        assertEquals("true|Zapdos", db.scalar(
                "SELECT art_borrowed || '|' || art_owner_name FROM item_product(990601)"),
                "a product looked up by id is described the same way");
    }
}
