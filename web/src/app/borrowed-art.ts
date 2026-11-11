/**
 * What to say when our picture of a card is another printing's: the image
 * source has no photo of a stamped, foil or promo printing, so the plain
 * printing's picture stands in (card_image_provenance() 'base_art').
 *
 * One sentence for both places that show it, the band on the card's artwork
 * and the chip on its search row, so the two can't drift apart.
 *
 * @param printing what this printing is ("Prerelease", "Cracked Ice Holo"),
 *   when known
 * @param ownerName the product whose picture is shown, when one is listed
 */
export function borrowedArtNote(printing: string | null, ownerName: string | null): string {
  const which = printing ? ` (${printing})` : '';
  const shown = ownerName ? `"${ownerName}"` : 'another printing';
  return (
    `Our image source has no photo of this printing${which}, ` +
    `so ${shown} is shown instead. The prices are this card's; ` +
    `the picture may not be.`
  );
}
