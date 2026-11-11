package com.cardtracker.model;

/**
 * extendedData is kept as raw JSON, not parsed into fields, because its shape
 * (card number, rarity, HP, attacks...) varies by category and isn't worth
 * modeling in Java. It lands straight in the `products.extended_data` jsonb
 * column, queryable there if needed.
 *
 * isSealed is derived from that same data at construction time: a real
 * single card always carries a "Number" field (its card number), sealed
 * product (boxes, packs, tins, cases, bundles...) never does. Verified
 * against real data spanning 1999 Base Set through 2026 sets before being
 * trusted.
 */
public record Product(
        int productId,
        int groupId,
        int categoryId,
        String name,
        String cleanName,
        String imageUrl,
        String url,
        String modifiedOn,
        boolean isSealed,
        String extendedDataJson) {
}
