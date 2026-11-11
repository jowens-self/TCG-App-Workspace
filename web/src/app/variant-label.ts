/**
 * Should a variant label be shown, given the name displayed next to it?
 *
 * `variant_label()` derives the label FROM the product name, so most of the
 * time it repeats words already on screen a couple of inches to the left:
 * "Professor's Research [Professor Magnolia]" labelled "Professor Magnolia".
 * Measured against the catalog on 2026-08-19, that is 78.7% of the 12,200
 * labelled products.
 *
 * The label earns its place in the other ~21%, where it reformats rather
 * than repeats:
 *
 *   "Night Stretcher - 2025 (Riley McKay)"  ->  "2025 World Championship"
 *   "Cleffa - 037 (Prerelease) [Staff]"     ->  "Prerelease · Staff"
 *
 * A containment test separates the two cleanly, and it cannot mis-fire
 * across cards: the label is always extracted from the very name it is
 * tested against, so a coincidental match on some other product's text is
 * not possible.
 *
 * Why this exists at all: the labels used to be shown always, as a filled
 * --warn pill with a matching corner wedge on the thumbnail. That styling
 * was chosen for a different job: warning that a picture might be another
 * printing's artwork. Applied to every labelled product it
 * made the loudest thing in each row a duplicate of the card's own name,
 * and implied doubt about images that are perfectly correct.
 */
export function variantLabelAddsInfo(name: string, label: string | null): boolean {
  if (!label) return false;
  return !name.toLowerCase().includes(label.toLowerCase());
}
