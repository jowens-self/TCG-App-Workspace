-- Does game_set_overview() still agree with set_value() and set_return()?
--
-- game_set_overview() exists because the screen needs every set at once and
-- the per-set primitives would be 604 round trips (302 groups x 2). That
-- makes it a SECOND definition of numbers set_value()/set_return() already
-- define, exactly the duplication the "handlers call SQL functions, not
-- inline SQL" rule exists to prevent. The duplication is only acceptable
-- while this check passes.
--
-- Run it after touching either side:
--   docker compose exec -T db psql -U cardtracker -d cardtracker \
--     -f - < sql/checks/set_overview_matches_primitives.sql
--
-- Every column must read `t`. IS NOT DISTINCT FROM is used rather than =
-- so that null matches null: a set with no common basket has a NULL return
-- on both sides, and `NULL = NULL` would report that agreement as failure.
--
-- Checks the top 20 by value rather than all 302, since it calls two functions
-- per set, so the full sweep is slow, and the high-value sets are where a
-- divergence would matter most. Raise the LIMIT for a fuller sweep.
--
-- DESPIKING IS DELIBERATELY OFF here (the FALSE argument). set_return() is
-- a raw two-point comparison, so with despiking on the two legitimately
-- disagree wherever a suspect reading was replaced, and that is the feature
-- working, not a regression. Passing FALSE keeps this check testing the
-- aggregation logic, which is what it exists for.
--
-- LAUNCH PRICING IS INCLUDED here for the same reason: set_return() knows
-- nothing about pre-release or early prices, so with them left out the two
-- disagree on every new set, by design (migration 004).
WITH ov AS (
  SELECT group_id, set_name, n_priced, total_value, n_common,
         total_start, total_end, pct_return, start_date, end_date
  FROM game_set_overview(3, 30, NULL, FALSE,  -- despiking OFF, see above
                         p_include_prerelease => TRUE, p_include_early => TRUE)
  ORDER BY total_value DESC NULLS LAST
  LIMIT 20
),
ref AS (
  SELECT ov.group_id,
         (SELECT sv.n_variants  FROM set_value(ov.group_id, ov.end_date) sv)  AS ref_n_priced,
         (SELECT sv.total_value FROM set_value(ov.group_id, ov.end_date) sv)  AS ref_value,
         (SELECT sr.n_variants  FROM set_return(ov.group_id, ov.start_date, ov.end_date) sr) AS ref_n_common,
         (SELECT sr.total_start FROM set_return(ov.group_id, ov.start_date, ov.end_date) sr) AS ref_start,
         (SELECT sr.total_end   FROM set_return(ov.group_id, ov.start_date, ov.end_date) sr) AS ref_end,
         (SELECT sr.pct_return  FROM set_return(ov.group_id, ov.start_date, ov.end_date) sr) AS ref_pct
  FROM ov
)
SELECT ov.set_name,
       (ov.n_priced    IS NOT DISTINCT FROM ref.ref_n_priced) AS n_ok,
       (ov.total_value IS NOT DISTINCT FROM ref.ref_value)    AS val_ok,
       (ov.n_common    IS NOT DISTINCT FROM ref.ref_n_common) AS nc_ok,
       (ov.total_start IS NOT DISTINCT FROM ref.ref_start)    AS start_ok,
       (ov.total_end   IS NOT DISTINCT FROM ref.ref_end)      AS end_ok,
       (ov.pct_return  IS NOT DISTINCT FROM ref.ref_pct)      AS pct_ok
FROM ov JOIN ref USING (group_id)
ORDER BY ov.set_name;
