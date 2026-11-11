-- Where "modern" starts, per game.
--
-- Mirrors tracked_categories in schema.sql.
--
-- Owner's call, 2026-09-29: modern Pokemon begins at Sword & Shield, where
-- print runs jumped. Expected to move later (Scarlet & Violet printing dwarfs
-- SWSH), which is why this is one value in a table rather than a date in
-- code: moving it is an UPDATE.
--
-- 2019-11-15, not 2020-02-07. The SWSH promo line started with the Sword &
-- Shield video games, three months before the first SWSH set, and those
-- promos belong to the block. Nothing else is dated in that gap.
--
-- NULL means the whole game is modern. One Piece started in 2022.
--
-- A set with no release date is never modern, since it can't be placed in a
-- block. Today that is 19 Pokemon promo and catch-all groups (Miscellaneous
-- Cards & Products, the POP Series, Professor Program Promos...). The rule
-- itself lives in modern_group_ids() in functions.sql.
ALTER TABLE tracked_categories ADD COLUMN IF NOT EXISTS modern_since DATE;

UPDATE tracked_categories SET modern_since = DATE '2019-11-15'
 WHERE category_id = 3 AND modern_since IS NULL;
