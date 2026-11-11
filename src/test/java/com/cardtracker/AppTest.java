package com.cardtracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code-card exclusion.
 *
 * Digital redemption codes crater toward $0 and wreck set aggregates. Removing
 * the 1,253 already backfilled cost 810,451 price rows, which is why they are
 * excluded at ingest rather than filtered at query time, and why the rule
 * that recognises them is worth pinning.
 *
 * Pokemon only: it is a packaging quirk, not an industry fact. A new game's
 * quirk gets its own condition alongside this one, never a broadened check.
 */
@DisplayName("App.isCodeCard")
class AppTest {

    @Test
    void recognisesTheNamePrefix() {
        assertTrue(App.isCodeCard("Code Card - Battle Styles"));
        assertTrue(App.isCodeCard("Code Card - Brilliant Stars"));
    }

    /** Matched case-insensitively, because the catalog is not consistent. */
    @Test
    void isCaseInsensitive() {
        assertTrue(App.isCodeCard("code card - lowercase"));
        assertTrue(App.isCodeCard("CODE CARD - SHOUTED"));
    }

    /**
     * It is a PREFIX rule. A card merely containing the words later in its
     * name is an ordinary product, and excluding it would delete real price
     * history that cannot be recovered.
     */
    @Test
    void doesNotSwallowOrdinaryCards() {
        assertFalse(App.isCodeCard("Decidueye"));
        assertFalse(App.isCodeCard("Rapid Strike Urshifu VMAX"));
        assertFalse(App.isCodeCard("Trainer's Code Card Holder"));
        assertFalse(App.isCodeCard("Pokemon Code Card"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertFalse(App.isCodeCard(null));
        assertFalse(App.isCodeCard(""));
    }
}
