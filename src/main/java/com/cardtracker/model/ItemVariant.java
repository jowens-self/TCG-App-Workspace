package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One variant (sub-type) of a product, from item_variants().
 *
 * The bridge between search and item_return()/item_all_time_high(): both of
 * those need a subTypeName, and a caller cannot guess which variants a given
 * product actually has. It varies per product and changes over time, which
 * is why firstDate is reported rather than assumed (Altaria 83514's Normal
 * variant starts 2025-03-28 while its Holofoil goes back to 2024-02-08).
 *
 * latestMarket and allTimeHigh are market price only, so "no market price"
 * reads as null instead of silently becoming a mid or low listing price.
 * allTimeHigh matches item_all_time_high() exactly; null when the variant has
 * never had a market price.
 */
public record ItemVariant(
        String subTypeName,
        LocalDate firstDate,
        LocalDate latestDate,
        BigDecimal latestMarket,
        BigDecimal allTimeHigh) {
}
