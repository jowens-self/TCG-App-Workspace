package com.cardtracker.model;

import java.time.LocalDate;

/**
 * Whether price data is still arriving, per tracked game.
 *
 * Exists because the scheduled pull once failed silently for five days,
 * and an empty log was indistinguishable from a quiet success. Reading the
 * data answers the question the exit status could not.
 *
 * Two days is the threshold rather than one because snapshot_date anchors to
 * the mirror's dataAsOf(), which is normally yesterday - a healthy system is
 * one day behind by design.
 */
public record DataFreshness(
        int categoryId,
        String game,
        LocalDate newestSnapshot,
        int daysStale) {

    public static final int STALE_AFTER_DAYS = 2;

    public boolean stale() {
        return daysStale >= STALE_AFTER_DAYS;
    }
}
