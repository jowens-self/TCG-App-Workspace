package com.cardtracker.model;

/**
 * Which launch pricing a trend screen should count: prices from before a
 * card's release, and prices from its first weeks while it settles. Both are
 * left out by default because both are mostly noise (see
 * sql/migrations/004_launch_pricing.sql for what they look like in the data).
 * Which prices fall in each is decided in SQL, by launch_noise().
 */
public record LaunchPricing(boolean includePreRelease, boolean includeEarly) {

    /** The default: only settled prices count. */
    public static final LaunchPricing SETTLED_ONLY = new LaunchPricing(false, false);
}
