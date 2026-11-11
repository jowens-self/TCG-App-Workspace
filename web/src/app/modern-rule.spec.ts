import { describe, expect, it } from 'vitest';
import { Game } from './api.models';
import { modernRule } from './modern-rule';

const pokemon: Game = {
  categoryId: 3,
  name: 'Pokemon',
  productCount: 31_581,
  modernSince: '2019-11-15',
  earlyDays: 56,
  setCount: 0,
  modernSetCount: 0,
};
const onePiece: Game = {
  categoryId: 68,
  name: 'One Piece Card Game',
  productCount: 7_676,
  modernSince: null,
  earlyDays: 56,
  setCount: 0,
  modernSetCount: 0,
};

/** The sentence under the "Modern only" box, which has to match the server's rule. */
describe('modernRule', () => {
  it('names the cutoff for a game that has one', () => {
    expect(modernRule([onePiece, pokemon], 3)).toBe(
      'Modern means Pokemon sets released on or after 2019-11-15. ' +
        'Promo and catch-all groups with no release date are left out.',
    );
  });

  /** Nothing can be left out of a game with no cutoff, so it doesn't claim anything is. */
  it('says every set counts for a game with no cutoff', () => {
    expect(modernRule([onePiece, pokemon], 68)).toBe('Modern means every One Piece Card Game set.');
  });

  it('describes each game when showing all of them', () => {
    expect(modernRule([onePiece, pokemon], null)).toBe(
      'Modern means every One Piece Card Game set and Pokemon sets released on or after ' +
        '2019-11-15. Promo and catch-all groups with no release date are left out.',
    );
  });

  it('follows the cutoff wherever it moves', () => {
    const later = { ...pokemon, modernSince: '2023-03-31' };
    expect(modernRule([later], 3)).toContain('on or after 2023-03-31');
  });

  it('says nothing before the games have loaded', () => {
    expect(modernRule([], null)).toBe('');
  });
});
