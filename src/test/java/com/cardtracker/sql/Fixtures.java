package com.cardtracker.sql;

/**
 * A hand-built catalog small enough that every expected value can be worked
 * out on paper.
 *
 * That is deliberate. Snapshotting real output would pin whatever the
 * functions currently do, including any bug, and this project has shipped
 * numbers that looked plausible and were wrong, like a set worth $25,100.93 that
 * was really $2,792.05, a +200% step reported as +80%. A fixture you can do
 * arithmetic on is the only kind that can catch that.
 *
 * The products are real printings with their real shapes, because the rules
 * under test were each written for a specific card.
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** The anchor date all price fixtures are relative to. */
    public static final String TODAY = "2026-08-19";

    public static void load(TestDatabase db) throws Exception {
        db.exec("""
            INSERT INTO categories (category_id, name) VALUES
                (3, 'Pokemon'), (68, 'One Piece Card Game');

            -- schema.sql already seeds tracked_categories; adding the same
            -- rows here would be a second source of truth for which games are
            -- tracked, which is exactly what that table exists to prevent.
            INSERT INTO tracked_categories (category_id, name) VALUES
                (3, 'Pokemon'), (68, 'One Piece Card Game')
            ON CONFLICT (category_id) DO NOTHING;

            -- Real release dates, which put sets on both sides of Pokemon's
            -- modern cutoff (Sword & Shield, migration 003).
            INSERT INTO groups (group_id, category_id, name, published_on) VALUES
                (604,  3,  'Base Set',                 DATE '1999-01-09'),
                (2282, 3,  'World Championship Decks', DATE '2018-07-01'),
                (1663, 3,  'Base Set (Shadowless)',    DATE '1999-01-09'),
                (23237, 3, 'SV: Scarlet & Violet 151', DATE '2023-09-22'),
                (17659, 68, 'Romance Dawn',            DATE '2022-12-02');
            """);

        // ------------------------------------------------------------------
        // Singles. extended_data carries Number and Rarity for 100% of real
        // singles, which is why search reads them from there and never parses
        // them out of the name.
        // ------------------------------------------------------------------
        db.exec("""
            INSERT INTO products (product_id, group_id, category_id, name, is_sealed, extended_data, url) VALUES
              -- Plain single. The control for most tests.
              (42382, 604, 3, 'Charizard', FALSE,
                 '[{"name":"Number","value":"4/102"},{"name":"Rarity","value":"Holo Rare"}]', 'https://x/1'),

              -- Same card name, different number and rarity. A set commonly
              -- reprints one card across a wide price range, which is why
              -- search results must carry both fields.
              (42383, 604, 3, 'Charizard', FALSE,
                 '[{"name":"Number","value":"104/102"},{"name":"Rarity","value":"Secret Rare"}]', 'https://x/2'),

              -- Name ends in a letter-number token that is NOT a variant.
              (42384, 604, 3, 'Alakazam (H1)', FALSE,
                 '[{"name":"Number","value":"H1"},{"name":"Rarity","value":"Holo Rare"}]', 'https://x/3'),

              -- Parenthesised CARD NUMBER, not a printing.
              (42385, 604, 3, 'Aerodactyl (1)', FALSE,
                 '[{"name":"Number","value":"1/62"},{"name":"Rarity","value":"Holo Rare"}]', 'https://x/4'),

              -- A genuine printing qualifier.
              (42386, 1663, 3, 'Blastoise - 13/95 (Cracked Ice Holo)', FALSE,
                 '[{"name":"Number","value":"13/95"},{"name":"Rarity","value":"Holo Rare"}]', 'https://x/5'),

              -- World Championship deck: the parenthetical is the DECK PILOT,
              -- never a variant, so the YEAR is promoted instead.
              (479852, 2282, 3, 'Aaron''s Collection - 2009 (Tsubasa Nakamura)', FALSE,
                 '[{"name":"Number","value":"88/111"},{"name":"Rarity","value":"Uncommon"}]', 'https://x/6'),

              -- Trailing bracket faces the same validity test as a
              -- parenthetical, or all 26 Unown get a one-letter "variant".
              (42387, 604, 3, 'Unown [A]', FALSE,
                 '[{"name":"Number","value":"1/64"},{"name":"Rarity","value":"Rare"}]', 'https://x/7'),

              -- Qualifier sitting behind a card number: the number comes off
              -- first or the qualifier is never visible to a trailing match.
              (42388, 23237, 3, 'Professor''s Research [Professor Magnolia] - 223', FALSE,
                 '[{"name":"Number","value":"223/198"},{"name":"Rarity","value":"Ultra Rare"}]', 'https://x/8'),

              -- A One Piece single, so every game-scoped rule has two games
              -- to tell apart.
              (900001, 17659, 68, 'Monkey D. Luffy', FALSE,
                 '[{"name":"Number","value":"OP01-001"},{"name":"Rarity","value":"Leader"}]', 'https://x/10');
            """);

        // ------------------------------------------------------------------
        // Sealed product. A single $8,441 display case once made up a third of
        // a set's "total value", which is why set aggregates exclude sealed
        // internally rather than as a caller option.
        // ------------------------------------------------------------------
        db.exec("""
            INSERT INTO products (product_id, group_id, category_id, name, is_sealed, extended_data, url) VALUES
              (99001, 604, 3, 'Base Set Booster Box', TRUE, NULL, 'https://x/box');
            """);
    }

    /**
     * Prices for one product/variant across a run of days ending at TODAY.
     * Index 0 is the OLDEST day.
     */
    public static void prices(TestDatabase db, int productId, String subType, String... marketByDay)
            throws Exception {
        int days = marketByDay.length;
        StringBuilder sb = new StringBuilder(
                "INSERT INTO price_snapshots (product_id, sub_type_name, snapshot_date, "
                + "market_price, low_price, mid_price, high_price) VALUES ");
        for (int i = 0; i < days; i++) {
            String p = marketByDay[i];
            String value = "null".equals(p) ? "NULL" : p;
            sb.append(i > 0 ? "," : "")
              .append("(").append(productId).append(", '").append(subType).append("', ")
              .append("DATE '").append(TODAY).append("' - ").append(days - 1 - i).append(", ")
              .append(value).append(", ")
              // low/mid track market; nothing charts or totals them. high is
              // parked absurdly high on purpose, since nothing may ever
              // compute on it.
              .append(value).append(", ").append(value).append(", 99999.00)");
        }
        db.exec(sb.toString());
    }
}
