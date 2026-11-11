package com.cardtracker.model;

/**
 * Whose artwork a card's cached thumbnail actually shows, from
 * card_image_provenance().
 *
 * One pokemontcg.io card is one picture, so several of our products can end
 * up sharing it: a printing with a stamp or a foil treatment has no catalog
 * entry of its own and matches the base printing's image instead. This says
 * which case a given product is in:
 *
 * <ul>
 *   <li>{@code own_art}: the picture is this printing's own.</li>
 *   <li>{@code base_art}: the picture belongs to another printing, named by
 *       {@link #ownerName()}. What the card physically has that the picture
 *       does not is {@link #strippedQualifier()}.</li>
 *   <li>{@code unknown}: the qualifier is not in {@code image_qualifier_class}
 *       and the structural test could not settle it. Deliberately not a guess.</li>
 * </ul>
 *
 * Only {@code base_art} is worth showing a caution for, and that is the whole
 * reason this reaches the UI at all: an earlier marker flagged
 * every labelled product, because at the time nothing could tell a borrowed
 * picture from an ordinary one. This can.
 *
 * The verdict is computed per request and never stored, because a later import or one
 * more row in the vocabulary table would silently invalidate a stored value.
 */
public record ImageProvenance(
        int productId,
        String name,
        String sourceId,
        /** The word the matcher had to drop to find a picture; null if none. */
        String strippedQualifier,
        /** 'treatment', 'identity', or null when unclassified. */
        String qualifierClass,
        /** How many of our products share this one catalog picture. */
        int claimants,
        /** 'own_art', 'base_art', or 'unknown'. */
        String provenance,
        /** The printing the picture belongs to; null unless borrowed. */
        Integer ownerProductId,
        String ownerName) {
}
