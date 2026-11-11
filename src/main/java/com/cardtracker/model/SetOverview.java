package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of game_set_overview(): a set's current value and how it moved
 * over a window.
 *
 * Per SET, and singles only: sealed product never contributes to a set
 * aggregate (see sql/functions.sql header and CLAUDE.md). Sealed items stay
 * individually trackable through item_return()/item_all_time_high().
 *
 * Two count/total pairs, and they are not redundant. {@code nPriced} and
 * {@code totalValue} cover every single priced on {@code endDate};
 * {@code nCommon}, {@code totalStart} and {@code totalEnd} cover only the
 * rows priced on BOTH dates, which is the basket {@code pctReturn} is
 * computed over. They diverge whenever a card was priced today but not at
 * the start of the window, and a client should say so rather than show two
 * numbers that look like they ought to match.
 *
 * The top* fields are a deliberate honesty measure, not decoration.
 * Measured 2026-08-18: among Pokemon sets with a >=2% 30-day move, the
 * single biggest contributing row is routinely 50-87% of all movement in
 * the set. A percentage on its own invites a reader to treat a set-wide
 * verdict as broad when one thin-market card produced it. {@code topShare}
 * is a share of GROSS movement (sum of absolute deltas), so it stays
 * meaningful for sets whose gains and losses nearly cancel.
 *
 * {@code pctReturn} is null, never zero, when the starting basket is <= 0.
 *
 * {@code nDespiked} counts readings replaced by the median of their two
 * neighbours because they swung hard and came straight back, which is an upstream
 * error rather than a move (see game_set_overview()). It is usually 0, and
 * that is expected: the effect is rare but heavy-tailed. When it fires it
 * can be decisive. On 2026-08-04, one substitution moved EX Dragon
 * Frontiers from -20.91% to +8.43%. Show it wherever the return is shown;
 * a corrected number that doesn't say it was corrected is worse than either
 * honest alternative.
 *
 * {@code modern} is whether the set counts as modern for its game; the rule
 * lives in modern_group_ids().
 *
 * {@code settlesOn} is set when the set itself was still new at the window
 * start: its release plus its game's early_days. With launch pricing left out
 * (the default) such a set usually has no change yet, and this says why.
 */
public record SetOverview(
        int groupId,
        String setName,
        String game,
        LocalDate publishedOn,
        int nPriced,
        BigDecimal totalValue,
        int nCommon,
        BigDecimal totalStart,
        BigDecimal totalEnd,
        BigDecimal pctReturn,
        Integer topProductId,
        String topName,
        String topSubType,
        BigDecimal topDelta,
        BigDecimal topShare,
        String topUrl,
        int nDespiked,
        BigDecimal despikeUsd,
        LocalDate startDate,
        LocalDate endDate,
        boolean modern,
        LocalDate settlesOn) {
}
