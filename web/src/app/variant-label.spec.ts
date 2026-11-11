import { describe, expect, it } from 'vitest';
import { variantLabelAddsInfo } from './variant-label';

/**
 * The containment rule that decides whether a variant chip is shown at all.
 *
 * This replaced "label every variant loudly", because the label
 * is derived FROM the product name and so repeated words already on screen
 * 78.7% of the time, while wearing --warn styling that implied doubt about
 * artwork which was perfectly correct.
 */
describe('variantLabelAddsInfo', () => {
  it('says nothing when there is no label', () => {
    expect(variantLabelAddsInfo('Charizard', null)).toBe(false);
    expect(variantLabelAddsInfo('Charizard', '')).toBe(false);
  });

  it('hides a label that just repeats the name beside it', () => {
    expect(
      variantLabelAddsInfo(
        "Professor's Research [Professor Magnolia]",
        'Professor Magnolia',
      ),
    ).toBe(false);
    expect(
      variantLabelAddsInfo('Blastoise - 13/95 (Cracked Ice Holo)', 'Cracked Ice Holo'),
    ).toBe(false);
  });

  /**
   * The ~21% that earns its place: the label reformats rather than repeats.
   * "2025" and "(Riley McKay)" are both in the name, but "2025 World
   * Championship" is not, since it tells the reader what the card actually is.
   */
  it('shows a label that reformats rather than repeats', () => {
    expect(
      variantLabelAddsInfo('Night Stretcher - 2025 (Riley McKay)', '2025 World Championship'),
    ).toBe(true);
    expect(
      variantLabelAddsInfo(
        "Aaron's Collection - 2009 (Tsubasa Nakamura)",
        '2009 World Championship',
      ),
    ).toBe(true);
  });

  it('ignores case, because the catalog is not consistent about it', () => {
    expect(variantLabelAddsInfo('BLASTOISE (CRACKED ICE HOLO)', 'Cracked Ice Holo')).toBe(false);
    expect(variantLabelAddsInfo('Blastoise (Cracked Ice Holo)', 'CRACKED ICE HOLO')).toBe(false);
  });

  /**
   * The property that makes this safe: the label always comes from the very
   * name it is tested against, so it cannot mis-fire across two cards.
   */
  it('is decided only against the name it was extracted from', () => {
    expect(variantLabelAddsInfo('Charizard', 'Prerelease')).toBe(true);
  });
});
