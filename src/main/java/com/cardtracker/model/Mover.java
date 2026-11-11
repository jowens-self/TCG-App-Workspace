package com.cardtracker.model;

import java.math.BigDecimal;

/**
 * One row of item_movers(): a (product, variant) whose price moved over a
 * window.
 *
 * Note this is per VARIANT, unlike ItemSearchResult which is per product.
 * That isn't an inconsistency: a mover is a specific market's move, and
 * Holofoil / Reverse Holofoil of the same card move independently. Merging
 * them here would be the sub-type violation, not the reverse.
 *
 * thenPrice and nowPrice are the MEDIAN of the smoothing window at each end,
 * not single-day observations and not an average. See item_movers() in
 * sql/functions.sql for why (thinly-traded cards lurch on single sales, and
 * an average additionally leaks prices across a step, which misreported a
 * real +200% move as +80% until this was changed on 2026-08-18).
 */
public record Mover(
        int productId,
        String name,
        String cardNumber,
        String rarity,
        String setName,
        String game,
        String subTypeName,
        boolean isSealed,
        BigDecimal thenPrice,
        BigDecimal nowPrice,
        BigDecimal pctChange,
        String url) {
}
