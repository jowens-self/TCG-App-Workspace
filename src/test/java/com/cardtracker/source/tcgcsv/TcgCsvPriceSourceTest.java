package com.cardtracker.source.tcgcsv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The freshness marker, which is what stops a pull dating itself from the
 * local clock.
 *
 * No test here makes a request. tcgcsv is a free, no-SLA mirror run by one
 * person with a documented ban threshold, and a test suite runs far more often
 * than a daily pull, so the parsing is exercised against literal strings and
 * the HTTP call is left to the one real run that already proved it.
 */
@DisplayName("last-updated.txt")
class TcgCsvPriceSourceTest {

    /**
     * The exact body served on 2026-08-20. The mapping from this timestamp to
     * a snapshot date is verified, not assumed: while the marker read this,
     * the live endpoints returned data identical to archive
     * prices-2026-08-19, all 51,287 rows.
     */
    @Test
    void readsTheDateTheMirrorIsActuallyServing() {
        assertEquals(LocalDate.of(2026, 8, 19),
                TcgCsvPriceSource.parseLastUpdated("2026-08-19T20:05:19+0000"));
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertEquals(LocalDate.of(2026, 8, 19),
                TcgCsvPriceSource.parseLastUpdated("  2026-08-19T20:05:19+0000\n"));
    }

    /**
     * The refresh lands at ~20:00 UTC, so a non-UTC offset must be converted
     * rather than truncated, since reading the local date off the raw string would
     * land on the wrong day for anything east of UTC.
     */
    @Test
    void convertsOffsetsToUtcBeforeTakingTheDate() {
        // 2026-08-20T02:05+0600 is 2026-08-19T20:05Z, still the 19th.
        assertEquals(LocalDate.of(2026, 8, 19),
                TcgCsvPriceSource.parseLastUpdated("2026-08-20T02:05:19+0600"));
    }

    /**
     * The important one. A fallback to the local clock here is exactly the bug
     * that wrote a duplicate day, so an unreadable marker must stop the run -
     * which is safe, because missing a day costs nothing: the archive backfill
     * picks it up on the next run.
     */
    @Test
    void refusesRatherThanGuessingWhenTheMarkerIsUnreadable() {
        for (String junk : new String[]{"", "   ", "not a date", "2026-08-19", "<html>502</html>", null}) {
            assertThrows(IllegalStateException.class,
                    () -> TcgCsvPriceSource.parseLastUpdated(junk),
                    "should refuse to date a pull from: " + junk);
        }
    }
}
