import { describe, expect, it } from 'vitest';
import { borrowedArtNote } from './borrowed-art';

describe('borrowedArtNote', () => {
  it('names the printing and the picture shown', () => {
    expect(borrowedArtNote('National Championships', 'Charizard')).toBe(
      'Our image source has no photo of this printing (National Championships), ' +
        'so "Charizard" is shown instead. The prices are this card\'s; ' +
        'the picture may not be.',
    );
  });

  /** A stamp with no plain printing listed has a borrowed picture but no owner to name. */
  it('says "another printing" when no owner is listed', () => {
    expect(borrowedArtNote('Prerelease', null)).toContain(
      'so another printing is shown instead',
    );
  });

  it('leaves out the printing when it is not known', () => {
    expect(borrowedArtNote(null, 'Charizard')).toMatch(/^Our image source has no photo of this printing, /);
  });
});
