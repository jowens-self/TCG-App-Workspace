package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One day of a variant's price history, from item_history().
 *
 * No highPrice field, on purpose. Sellers "price park" by listing at absurd
 * values so nothing sells, so high_price is noise the project stores but
 * never computes on. Leaving it off this record (and out of item_history())
 * makes that rule structural instead of something a future chart author has
 * to remember, the same way set_return() hardcodes sealed product out rather
 * than offering a parameter.
 *
 * A chart plots marketPrice alone. lowPrice and midPrice are listing
 * prices, here to read, never to chart (owner, 2026-10-29: filling a day with
 * no sale from them put parked listings on charts).
 *
 * phase is "pre_release", "early" or null (settled), from price_phase(), so a
 * chart can shade or hide launch pricing without knowing the rule.
 */
public record PricePoint(
        LocalDate snapshotDate,
        BigDecimal marketPrice,
        BigDecimal lowPrice,
        BigDecimal midPrice,
        BigDecimal directLowPrice,
        String phase) {
}
