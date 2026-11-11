-- Sets: store the release date as a date, and stop storing a fake one.
--
-- Mirrors groups.published_on in schema.sql.
--
-- Two problems with the TIMESTAMPTZ this replaces:
--
--   * 19 catch-all and promo groups (Miscellaneous Cards & Products, POP
--     Series 1-9, Burger King Promos, the EX Trainer Kits...) have no release
--     date upstream. The mirror stamps them with the moment it built the file,
--     so every day they read as released today and sorted above every real new
--     set. They become NULL here, and TcgCsvPriceSource.releaseDate keeps them
--     NULL on every pull after.
--
--   * A release date is a calendar day, but it was stored as an instant, read
--     in whatever timezone the ingesting JVM happened to run in. It only came
--     out right because this machine is west of UTC. A DATE has no timezone to
--     get wrong.
--
-- Telling them apart: real dates were written at local midnight, which is
-- always a whole hour in UTC. The placeholders never are (20:00:06.35 local).
-- Taking the UTC day keeps the right date for anything written west of UTC,
-- which is everything here, and the next pull rewrites every real date from
-- the source regardless.
--
-- Guarded on the column type so a second run is a no-op rather than a second
-- conversion of values that are already dates.
DO $$
BEGIN
    IF (SELECT data_type FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'groups' AND column_name = 'published_on') = 'timestamp with time zone' THEN
        ALTER TABLE groups ALTER COLUMN published_on TYPE DATE
            USING CASE
                WHEN (published_on AT TIME ZONE 'UTC') = date_trunc('hour', published_on AT TIME ZONE 'UTC')
                THEN (published_on AT TIME ZONE 'UTC')::date
            END;
    END IF;
END $$;
