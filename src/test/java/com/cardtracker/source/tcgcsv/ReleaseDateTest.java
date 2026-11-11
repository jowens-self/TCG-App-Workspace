package com.cardtracker.source.tcgcsv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Set release dates, read from literal strings for the same reason as
 * {@link TcgCsvPriceSourceTest}: a test suite owes the mirror no traffic.
 */
@DisplayName("set release dates")
class ReleaseDateTest {

    /** Exactly as the mirror serves Sword & Shield base. */
    @Test
    void aRealReleaseDateIsKept() {
        assertEquals(LocalDate.of(2020, 2, 7), TcgCsvPriceSource.releaseDate("2020-02-07T00:00:00"));
        assertEquals(LocalDate.of(2020, 2, 7), TcgCsvPriceSource.releaseDate("2020-02-07T00:00:00Z"));
    }

    /**
     * The shape the dateless groups (Miscellaneous Cards & Products, POP
     * Series) arrive in: the mirror's own build time, seven fractional
     * digits and all. Kept as a date, it read as "released today" every day.
     */
    @Test
    void theMirrorsBuildTimeIsNotAReleaseDate() {
        assertNull(TcgCsvPriceSource.releaseDate("2026-09-28T20:00:06.3524870"));
        assertNull(TcgCsvPriceSource.releaseDate("2026-09-28T20:00:06"));
    }

    /** Unknown rather than an exception: a missing date must never stop a pull. */
    @Test
    void missingOrUnreadableMeansUnknown() {
        for (String junk : new String[]{null, "", "   ", "soon", "2026-13-01T00:00:00"}) {
            assertNull(TcgCsvPriceSource.releaseDate(junk), "should be unknown: " + junk);
        }
    }
}
