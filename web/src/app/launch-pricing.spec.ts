import { describe, expect, it } from 'vitest';
import { Game, PricePhase } from './api.models';
import { chartablePoints, earlyWeeks, launchNote, phaseRuns } from './launch-pricing';

const at = (date: string, phase: PricePhase | null) => ({ date, value: 1, phase });

/** A new card's life: two preorder days, two early days, two settled days. */
const life = [
  at('2026-09-01', 'pre_release'),
  at('2026-09-02', 'pre_release'),
  at('2026-09-03', 'early'),
  at('2026-09-04', 'early'),
  at('2026-09-05', null),
  at('2026-09-06', null),
];

describe('chartablePoints', () => {
  it('draws only settled prices by default', () => {
    const { points, onlyLaunch } = chartablePoints(life, false, false);
    expect(points.map((p) => p.date)).toEqual(['2026-09-05', '2026-09-06']);
    expect(onlyLaunch).toBe(false);
  });

  /** Each box brings back its own part and nothing else. */
  it('brings back each part on its own', () => {
    expect(chartablePoints(life, true, false).points.map((p) => p.phase)).toEqual([
      'pre_release',
      'pre_release',
      null,
      null,
    ]);
    expect(chartablePoints(life, false, true).points.map((p) => p.phase)).toEqual([
      'early',
      'early',
      null,
      null,
    ]);
    expect(chartablePoints(life, true, true).points).toHaveLength(6);
  });

  /** A card still settling would otherwise open on an empty chart. */
  it('shows a brand-new card anyway, and says so', () => {
    const young = life.slice(0, 4);
    const { points, onlyLaunch } = chartablePoints(young, false, false);
    expect(points).toHaveLength(4);
    expect(onlyLaunch).toBe(true);
  });

  it('leaves an ordinary card alone', () => {
    const settled = [at('2026-09-05', null), at('2026-09-06', null)];
    expect(chartablePoints(settled, false, false)).toEqual({ points: settled, onlyLaunch: false });
  });
});

describe('phaseRuns', () => {
  it('groups consecutive days in the same phase', () => {
    expect(phaseRuns(life.map((p) => p.phase))).toEqual([
      { from: 0, to: 1, phase: 'pre_release' },
      { from: 2, to: 3, phase: 'early' },
    ]);
  });

  it('closes a run that reaches the last point', () => {
    expect(phaseRuns([null, 'early', 'early'])).toEqual([{ from: 1, to: 2, phase: 'early' }]);
  });

  it('has nothing to shade on a settled card', () => {
    expect(phaseRuns([null, null, undefined])).toEqual([]);
  });
});

describe('earlyWeeks', () => {
  const game = (categoryId: number, earlyDays: number) => ({ categoryId, earlyDays }) as Game;

  it("uses the selected game's length", () => {
    expect(earlyWeeks([game(3, 56), game(68, 70)], 3)).toBe(8);
  });

  /** Showing all games, the label must not understate the longest one. */
  it('uses the longest across all games', () => {
    expect(earlyWeeks([game(3, 56), game(68, 70)], null)).toBe(10);
  });
});

describe('launchNote', () => {
  it('says nothing when both are ticked', () => {
    expect(launchNote(true, true, 8, 'the board')).toBe('');
  });

  it('names what each unticked box leaves out', () => {
    expect(launchNote(false, false, 8, 'the board')).toContain('first 8 weeks');
    expect(launchNote(false, true, 8, 'the board')).toBe(
      "Pre-release prices don't count toward the board.",
    );
    expect(launchNote(true, false, 8, 'the board')).toBe(
      "Early pricing (the first 8 weeks after release) doesn't count toward the board.",
    );
  });
});
