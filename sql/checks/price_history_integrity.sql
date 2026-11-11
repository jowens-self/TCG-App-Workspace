-- Is the price history still whole?
--
-- A MISSING day self-heals: `App` runs gap catch-up unconditionally, tcgcsv's
-- archives persist to 2024-02-08 (verified by request 2026-08-21, not
-- assumed), and the next run fills it. Skipping a week costs seven days of
-- delay and nothing else.
--
-- A SHORT day does not. A partial ingest leaves the date PRESENT, so gap
-- catch-up will never look at it again, since there is no gap to find. It stays
-- wrong until a person notices, and until 2026-08-21 nobody was watching.
-- That asymmetry is what this file is for; the rest is cheap to carry
-- alongside.
--
--   docker compose exec -T db psql -U cardtracker -d cardtracker \
--     -f - < sql/checks/price_history_integrity.sql
--
-- Every `ok` column must read `t`. `detail` is a number, never a verdict, so
-- a failing row says how bad it is rather than only that it is bad.
--
-- COST: ~20s, most of it the one scan of price_snapshots joined to products
-- (41M rows). That is why day_counts is MATERIALIZED. Without it the planner
-- re-runs that scan for each assertion that reads it.
--
-- Two things deliberately NOT asserted:
--
--   * Duplicate (product_id, snapshot_date). Multiple rows per product per day
--     are correct: TCGplayer prices each printing separately, and the primary
--     key is (product_id, sub_type_name, snapshot_date). The PK already makes
--     a true duplicate impossible, so a check here would only restate the
--     schema. Written down because the naive version of this check flagged
--     12,245,214 "duplicates" that were all legitimate printings.
--
--   * Orphan snapshots existing at all. 134 products (53,278 rows, 0.13%)
--     have price history and no `products` row: BackfillArchive sees a bare
--     product_id from an archive and never a name, so a product that left
--     tcgcsv's catalog keeps its history without ever gaining a catalog entry.
--     That is the append-only design working. They are invisible to every
--     query (all of them join products), so no number is wrong. What IS
--     asserted is that no orphan is still receiving snapshots, which would
--     mean the daily pull had started writing prices for products it cannot
--     name, which is a different thing entirely.
--
-- NOTHING HERE COMPARES A DATA DATE TO A WALL CLOCK, and that is deliberate.
-- The first draft asserted "newest snapshot is no more than 1 day old" and
-- failed on a perfectly healthy database, because CURRENT_DATE inside this
-- container is UTC while the machine writing the data may not be: for hours
-- every evening the two can disagree about what day it is. That is the
-- same defect CLAUDE.md calls the rule most likely to be "simplified" back
-- into a bug. PriceSource.dataAsOf() exists precisely so the pull dates
-- itself from the mirror rather than from a clock, and SourceGuardsTest
-- fails the build on a bare LocalDate.now(). A check that reintroduced it
-- would have been worse than no check: a suite that cries wolf every
-- evening teaches people to stop reading it.
--
-- Freshness is a real question, but it is an OPERATIONAL one and it belongs
-- to sql/checks/data_freshness.sql, which reports how old each game's newest
-- prices are. This file answers "is what we have intact", which is a question
-- the data can answer about itself.
WITH day_counts AS MATERIALIZED (
  SELECT p.category_id, ps.snapshot_date, COUNT(*) AS n
    FROM price_snapshots ps
    JOIN products p ON p.product_id = ps.product_id
   GROUP BY 1, 2
),
span AS (
  SELECT category_id,
         COUNT(*) AS days_present,
         (MAX(snapshot_date) - MIN(snapshot_date)) + 1 AS days_expected
    FROM day_counts GROUP BY 1
),
local AS (
  -- A partial ingest leaves no gap (the date is present, just short), so it
  -- can only be caught by size. Centred 31-day mean rather than a global one
  -- so ordinary catalog growth is not mistaken for damage. Observed spread on
  -- 2026-08-21 was 96.2%-104.2% of local, so the 85% floor has ~11 points of
  -- headroom below anything real while still catching a run that died midway.
  SELECT category_id, snapshot_date, n,
         AVG(n) OVER (PARTITION BY category_id ORDER BY snapshot_date
                      ROWS BETWEEN 15 PRECEDING AND 15 FOLLOWING) AS local_avg
    FROM day_counts
)
SELECT 'no missing days in any tracked game' AS check,
       bool_and(days_present = days_expected) AS ok,
       SUM(days_expected - days_present)      AS detail
  FROM span
UNION ALL
SELECT 'no day ingested partially (< 85% of its 31-day local mean)',
       COUNT(*) = 0,
       COUNT(*)
  FROM local WHERE local_avg > 0 AND n < 0.85 * local_avg
UNION ALL
SELECT 'no negative prices',
       COUNT(*) = 0, COUNT(*)
  FROM price_snapshots
 WHERE market_price < 0 OR low_price < 0 OR mid_price < 0
    OR high_price < 0 OR direct_low_price < 0
UNION ALL
SELECT 'no snapshot dated in the future',
       COUNT(*) = 0, COUNT(*)
  FROM price_snapshots WHERE snapshot_date > CURRENT_DATE
UNION ALL
-- Code cards crater toward $0 and wreck set aggregates, so they are
-- excluded at INGEST rather than filtered at query time. One reappearing in
-- products means the ingest-time rule stopped firing.
SELECT 'no code cards in products',
       COUNT(*) = 0, COUNT(*)
  FROM products WHERE name LIKE 'Code Card%'
UNION ALL
-- Scoped to AFTER the exclusion, not "has any rows at all".
--
-- A code card is discovered by name during the pull, but it can be priced
-- before that happens: App runs the archive backfill first, and the backfill
-- knows product ids only, so a brand new code card gets a day or two of prices
-- in the same run that later excludes it. That residue is expected and
-- harmless - excluded products are gone from `products`, so nothing joins to
-- them and no aggregate sees them.
--
-- Asserting "no rows at all" made this permanently red every time a new code
-- card appeared, and the only way to clear it was deleting real observations
-- from an append-only table. The honest question is whether exclusion actually
-- STOPPED the ingest, and a row dated after the exclusion is the thing that
-- would prove it did not.
SELECT 'no excluded product gained prices after exclusion',
       COUNT(*) = 0, COUNT(*)
  FROM price_snapshots ps
  JOIN excluded_products e ON e.product_id = ps.product_id
 WHERE ps.snapshot_date > e.excluded_at::date
UNION ALL
-- Bounded to a recent window so this reads the date index rather than the
-- whole table. The bound is deliberately generous: it only has to be recent,
-- not precise, because what it is looking for is a change in behaviour.
--
-- Deliberately-excluded products are not orphans in the sense meant here. This
-- looks for a product that left `products` for an UNKNOWN reason while prices
-- kept arriving; one we removed on purpose is accounted for by the check above.
SELECT 'no orphan product is still receiving snapshots',
       COUNT(*) = 0, COUNT(*)
  FROM (SELECT DISTINCT ps.product_id
          FROM price_snapshots ps
          LEFT JOIN products p ON p.product_id = ps.product_id
         WHERE ps.snapshot_date >= (SELECT MAX(snapshot_date) FROM price_snapshots) - 14
           AND p.product_id IS NULL
           AND NOT EXISTS (SELECT 1 FROM excluded_products e
                            WHERE e.product_id = ps.product_id)) x
UNION ALL
-- Every day of data must trace to a run that COMPLETED. This is the honest
-- form of "did the pull work": it asks whether the data present was fully
-- written, not whether a pull happened recently. A run that died midway is
-- recorded `failed` and its day re-ingested later, so a historical failure is
-- the table working. `ingest_runs` currently holds exactly one, the
-- DateTimeParseException on the mirror's 7-digit fractional timestamp, and
-- 2026-08-12 has an ok run alongside it.
SELECT 'every day of price data traces to a completed ingest run',
       COUNT(*) = 0, COUNT(*)
  FROM (SELECT DISTINCT ps.snapshot_date
          FROM price_snapshots ps
         WHERE NOT EXISTS (SELECT 1 FROM ingest_runs r
                            WHERE r.snapshot_date = ps.snapshot_date
                              AND r.status = 'ok')) y
UNION ALL
SELECT 'no ingest run started and never finished',
       COUNT(*) = 0, COUNT(*)
  FROM ingest_runs WHERE finished_at IS NULL;
