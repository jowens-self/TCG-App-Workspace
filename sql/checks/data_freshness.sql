-- Is data still arriving?
--
-- Once, the scheduled pull failed silently for five days and nobody noticed,
-- because a launchd job that cannot reach its own repository exits without
-- writing anything, and an empty log looks exactly like a quiet success. The
-- conclusion was that a job whose failure is indistinguishable
-- from its success needs a check that reads the DATA rather than the exit
-- status. This is that check.
--
-- Two days, not one. The pull anchors snapshot_date to the mirror's own
-- dataAsOf(), which is normally yesterday, so a healthy system is one day
-- behind by design and a one-day threshold would cry wolf every morning.
-- Two days means a pull was genuinely missed. On the 2026-08-30 outage this
-- would have fired on 09-01, three days before it was actually found.
--
-- Per tracked category, not globally: one game silently stopping while another
-- keeps arriving is the failure a global MAX() would hide completely.
-- Calls data_freshness() rather than restating it. The query's shape is
-- load-bearing (the natural formulation takes 22.5 seconds) and it has three
-- callers, so it has one definition; see sql/functions.sql.
SELECT 'price data is no more than 2 days stale: ' || f.game,
       f.days_stale <= 2 AS ok,
       f.days_stale
  FROM data_freshness() f
UNION ALL
-- A pull that starts and dies leaves a 'running' row forever. Distinct from
-- staleness: the data can still look recent while every new run is failing.
SELECT 'no ingest run has been running for over 6 hours',
       COUNT(*) = 0, COUNT(*)
  FROM ingest_runs
 WHERE status = 'running'
   AND started_at < now() - interval '6 hours';
