package com.cardtracker.sql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What counts as "modern", and that every screen asks the same question.
 *
 * Its own database rather than a corner of PriceMathSqlTest's, because the
 * movers cases need a mover in every era and those extra prices would shift
 * that class's paper-checked numbers.
 */
@EnabledIf("com.cardtracker.sql.TestDatabase#available")
@DisplayName("modern sets")
class ModernSetsSqlTest {

    /** Pokemon, no release date: the shape of Miscellaneous Cards & Products. */
    private static final int UNDATED_GROUP = 990100;

    private static TestDatabase db;

    @BeforeAll
    static void setUp() throws Exception {
        db = TestDatabase.create();
        Fixtures.load(db);
        db.exec("""
            INSERT INTO groups (group_id, category_id, name) VALUES
                (990100, 3, 'Miscellaneous Cards & Products');
            INSERT INTO products (product_id, group_id, category_id, name, is_sealed) VALUES
                (990101, 990100, 3, 'Pikachu (Jumbo)', FALSE);
            """);

        // Every one of these doubles over the window, one per era, so the only
        // thing deciding who appears on a modern board is the era.
        for (int productId : new int[]{42382, 42388, 900001, 990101}) {
            Fixtures.prices(db, productId, "Holofoil",
                    "10.00", "10.00", "10.00", "10.00", "10.00", "10.00",
                    "10.00", "20.00", "20.00", "20.00");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
    }

    private static boolean modern(int groupId) throws Exception {
        return "1".equals(db.scalar(
                "SELECT COUNT(*)::TEXT FROM modern_group_ids() WHERE group_id = " + groupId));
    }

    private static String movers(boolean modernOnly) throws Exception {
        return db.scalar("""
            SELECT string_agg(product_id::TEXT, ',' ORDER BY product_id) FROM item_movers(
                7, NULL, NULL, FALSE, 1, 'up', 3, 50, DATE '%s', %s)
            """.formatted(Fixtures.TODAY, modernOnly));
    }

    // ------------------------------------------------------------------

    @Test
    void pokemonIsModernFromSwordAndShield() throws Exception {
        assertTrue(modern(23237), "151 (2023) is after the cutoff");
        assertFalse(modern(604), "Base Set (1999) is before it");
        assertFalse(modern(2282), "World Championship Decks (2018) is before it");
    }

    /**
     * Not the first SWSH set's date (2020-02-07): the SWSH promo line started
     * three months earlier with the video games, and belongs to the block.
     */
    @Test
    void theSwordAndShieldPromosCountWithTheirBlock() throws Exception {
        db.exec("""
            INSERT INTO groups (group_id, category_id, name, published_on) VALUES
                (990200, 3, 'SWSH: Sword & Shield Promo Cards', DATE '2019-11-15'),
                (990201, 3, 'SM - Cosmic Eclipse',               DATE '2019-11-01')
            """);
        assertTrue(modern(990200));
        assertFalse(modern(990201), "the last Sun & Moon set stays out");
    }

    /** One Piece has no cutoff: the game began in 2022. */
    @Test
    void everyOnePieceSetIsModern() throws Exception {
        assertTrue(modern(17659));
    }

    /**
     * The owner's rule: a set that can't be placed in a block is left out
     * rather than guessed at.
     */
    @Test
    void aSetWithNoReleaseDateIsNeverModern() throws Exception {
        assertFalse(modern(UNDATED_GROUP));
    }

    /**
     * Filtered before the LIMIT, so a modern board is a full board of modern
     * cards rather than the all-eras board with the old ones cut out.
     */
    @Test
    void moversCanBeLimitedToModernSets() throws Exception {
        assertEquals("42382,42388,900001,990101", movers(false), "every era by default");
        assertEquals("42388,900001", movers(true), "151 and One Piece only");
    }

    /** The dropdown and the Sets table read the same rule as the board. */
    @Test
    void setListsCarryTheSameAnswer() throws Exception {
        assertEquals("true", db.scalar("SELECT is_modern::TEXT FROM game_sets(3) WHERE group_id = 23237"));
        assertEquals("false", db.scalar("SELECT is_modern::TEXT FROM game_sets(3) WHERE group_id = 604"));
        assertEquals("true", db.scalar("""
            SELECT is_modern::TEXT FROM game_set_overview(3, 7, DATE '%s') WHERE group_id = 23237
            """.formatted(Fixtures.TODAY)));
        assertEquals("false", db.scalar("""
            SELECT is_modern::TEXT FROM game_set_overview(3, 7, DATE '%s') WHERE group_id = 604
            """.formatted(Fixtures.TODAY)));
    }
}
