-- Launch pricing: tell pre-release and early (still settling) prices apart
-- from real trends.
--
-- Mirrors card_first_priced, note_first_priced() and its trigger, and
-- tracked_categories.early_days in schema.sql.
--
-- Measured 2026-09-29 across 14 Pokemon and 15 One Piece main sets released
-- since spring 2024: prices appear a week to a few months before release on
-- some cards, sitting at roughly 3x where they settle; on release day they are
-- still 1.5-2x; Pokemon then loses about a quarter in week one and 4-7% a week
-- for the rest of the month, One Piece slides for about six weeks; by week 8
-- both are flat. Owner's call the same day: that is noise, and trend screens
-- should leave it out by default, with a checkbox for each part.
--
-- WHY A TABLE. Telling which part of its life a price came from needs each
-- card's first price date. Looking that up per card costs one index seek, and
-- a Movers or Sets request touches ~50,000 cards: measured at 4.3s against a
-- 0.3s screen. So it is kept here, one row per (product, variant).
--
-- WHY A TRIGGER. Prices arrive three ways: the daily pull, the archive
-- backfill, and a restore from a backup with psql COPY.
-- Only a trigger sees all three. It is statement-level with a transition
-- table, so a COPY of a whole day is one upsert, and it only ever moves a
-- date EARLIER, so replaying old days is harmless. It never touches
-- price_snapshots itself.
--
-- early_days is per game and data, like modern_since: moving it is an UPDATE.

CREATE TABLE IF NOT EXISTS card_first_priced (
    product_id    INTEGER NOT NULL,
    sub_type_name TEXT    NOT NULL,
    first_date    DATE    NOT NULL,
    PRIMARY KEY (product_id, sub_type_name)
);

CREATE OR REPLACE FUNCTION note_first_priced() RETURNS trigger AS $$
BEGIN
    INSERT INTO card_first_priced (product_id, sub_type_name, first_date)
    SELECT i.product_id, i.sub_type_name, MIN(i.snapshot_date)
    FROM inserted i
    GROUP BY 1, 2
    ON CONFLICT (product_id, sub_type_name) DO UPDATE
        SET first_date = EXCLUDED.first_date
        WHERE EXCLUDED.first_date < card_first_priced.first_date;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Created before the backfill, and in the same transaction: creating it locks
-- out concurrent inserts until this commits, so no row can land in the gap
-- between the backfill's scan and the trigger existing.
CREATE OR REPLACE TRIGGER price_snapshots_first_priced
    AFTER INSERT ON price_snapshots
    REFERENCING NEW TABLE AS inserted
    FOR EACH STATEMENT EXECUTE FUNCTION note_first_priced();

INSERT INTO card_first_priced (product_id, sub_type_name, first_date)
SELECT product_id, sub_type_name, MIN(snapshot_date)
FROM price_snapshots
GROUP BY 1, 2
ON CONFLICT (product_id, sub_type_name) DO UPDATE
    SET first_date = EXCLUDED.first_date
    WHERE EXCLUDED.first_date < card_first_priced.first_date;

ALTER TABLE tracked_categories ADD COLUMN IF NOT EXISTS early_days INTEGER NOT NULL DEFAULT 56
    CHECK (early_days >= 0);
