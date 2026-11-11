package com.cardtracker.source.tcgcsv;

/**
 * Simple request pacer. Per tcgcsv.com/docs: keep a delay between requests
 * (they suggest >=100ms) and stay under 10,000 requests per 24h, or risk a
 * 10-minute IP throttle or an outright ban. This errs well inside both.
 */
public final class Throttle {

    /**
     * Hard stop well under the documented 10,000/24h ban threshold. A full
     * Pokemon sync needs a few hundred requests, so this only fires if
     * something is looping. It's a circuit breaker, not a normal ceiling.
     */
    private static final int MAX_REQUESTS_PER_RUN = 9000;

    private final long minIntervalMillis;
    private long lastRequestAt = 0;
    private int used = 0;

    private Throttle(long minIntervalMillis) {
        this.minIntervalMillis = minIntervalMillis;
    }

    public static Throttle politeDefault() {
        return new Throttle(750);
    }

    public synchronized void acquire() throws InterruptedException {
        if (used >= MAX_REQUESTS_PER_RUN) {
            throw new IllegalStateException(
                    "Refusing request " + (used + 1) + ": tcgcsv.com bans applications over "
                    + "10,000 requests/24h. Something is likely looping - aborting this run.");
        }
        long now = System.currentTimeMillis();
        long wait = lastRequestAt + minIntervalMillis - now;
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
        used++;
    }

    public int used() {
        return used;
    }
}
