import { describe, expect, it } from 'vitest';
import { GameSet } from './api.models';
import { cutoffYears, isListedCutoff } from './modern-cutoff';

const set = (name: string, publishedOn: string | null): GameSet => ({
  groupId: 0,
  name,
  publishedOn,
  productCount: 1,
  modern: false,
});

const sets = [
  set('SWSH01: Sword & Shield Base Set', '2020-02-07'),
  set('SWSH: Sword & Shield Promo Cards', '2019-11-15'),
  set('SV: Black Bolt', '2025-07-18'),
  set('SV: White Flare', '2025-07-18'),
  set('SV10: Destined Rivals', '2025-05-30'),
  set('Miscellaneous Cards & Products', null),
];

/** "Modern starts with" is picked by set, saved as that set's date. */
describe('cutoffYears', () => {
  it('lists release dates newest first, grouped by year', () => {
    expect(cutoffYears(sets).map((y) => [y.year, y.choices.map((c) => c.date)])).toEqual([
      ['2025', ['2025-07-18', '2025-05-30']],
      ['2020', ['2020-02-07']],
      ['2019', ['2019-11-15']],
    ]);
  });

  /** Two sets out the same day are one choice, since they are one date. */
  it('names every set released on a date', () => {
    expect(cutoffYears(sets)[0].choices[0].label).toBe(
      'SV: Black Bolt / SV: White Flare (2025-07-18)',
    );
  });

  it('leaves out sets with no release date, which cannot start anything', () => {
    const labels = cutoffYears(sets).flatMap((y) => y.choices.map((c) => c.label));
    expect(labels.some((l) => l.includes('Miscellaneous'))).toBe(false);
  });
});

describe('isListedCutoff', () => {
  it('knows the listed dates and "no cutoff"', () => {
    const years = cutoffYears(sets);
    expect(isListedCutoff(years, '2019-11-15')).toBe(true);
    expect(isListedCutoff(years, null)).toBe(true);
  });

  /** A date set by hand in SQL is shown as itself, not swapped for a neighbour. */
  it('flags a date no set was released on', () => {
    expect(isListedCutoff(cutoffYears(sets), '2021-01-01')).toBe(false);
  });
});
