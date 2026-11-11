import { Game, PricePhase } from './api.models';

/**
 * The points a chart should draw, given which launch pricing is ticked.
 *
 * A card still in its launch has nothing settled yet. Rather than an empty
 * chart, it gets everything it has with `onlyLaunch` set, so the screen can say
 * that what's drawn is all launch pricing (the chart shades it either way).
 */
export function chartablePoints<T extends { phase?: PricePhase | null }>(
  points: readonly T[],
  includePreRelease: boolean,
  includeEarly: boolean,
): { points: readonly T[]; onlyLaunch: boolean } {
  const kept = points.filter(
    (p) =>
      (p.phase !== 'pre_release' || includePreRelease) && (p.phase !== 'early' || includeEarly),
  );
  if (kept.length < 2 && points.length >= 2) return { points, onlyLaunch: true };
  return { points: kept, onlyLaunch: false };
}

/** A stretch of consecutive points in the same launch phase, by index. */
export interface PhaseRun {
  readonly from: number;
  readonly to: number;
  readonly phase: PricePhase;
}

/** Consecutive points sharing a phase, so a chart can shade each stretch once. */
export function phaseRuns(phases: readonly (PricePhase | null | undefined)[]): PhaseRun[] {
  const runs: PhaseRun[] = [];
  let start = -1;
  for (let i = 0; i <= phases.length; i++) {
    const phase = phases[i] ?? null;
    const open = start >= 0 ? phases[start] : null;
    if (phase !== open) {
      if (open) runs.push({ from: start, to: i - 1, phase: open });
      start = phase ? i : -1;
    }
  }
  return runs;
}

/** Whether a series has any launch pricing at all, so a chart's toggles only show when they'd do something. */
export function hasLaunchPricing(points: readonly { phase?: PricePhase | null }[]): boolean {
  return points.some((p) => p.phase != null);
}

/**
 * How many weeks "early pricing" lasts for the game(s) on screen, for labels.
 * The longest when showing all games, so the label never understates it.
 */
export function earlyWeeks(games: readonly Game[], selected: number | null): number | null {
  const shown = selected === null ? games : games.filter((g) => g.categoryId === selected);
  if (shown.length === 0) return null;
  return Math.round(Math.max(...shown.map((g) => g.earlyDays)) / 7);
}

/** Hover text for the "Include early pricing" box. */
export function earlyPricingTitle(weeks: number | null): string {
  const span = weeks === null ? 'first weeks' : `first ${weeks} weeks`;
  return (
    `A card's ${span} after release, while its price settles. ` +
    `New sets usually drop hard in week one and keep sliding for about a month.`
  );
}

/** Hover text for the "Include pre-release" box. */
export const PRE_RELEASE_TITLE =
  'Prices from before a card comes out: preorders, thinly traded and usually ' +
  'well above where the card settles.';

/**
 * What a trend screen is leaving out, in one sentence, or '' when nothing is.
 * `counts` is what the launch pricing would otherwise count toward, e.g.
 * "the board" or "a set's change".
 */
export function launchNote(
  includePreRelease: boolean,
  includeEarly: boolean,
  weeks: number | null,
  counts: string,
): string {
  const span = weeks === null ? 'the first weeks' : `the first ${weeks} weeks`;
  if (!includePreRelease && !includeEarly) {
    return (
      `New cards don't count toward ${counts} until they've settled: no pre-release ` +
      `prices, and nothing from ${span} after release.`
    );
  }
  if (!includePreRelease) return `Pre-release prices don't count toward ${counts}.`;
  if (!includeEarly) return `Early pricing (${span} after release) doesn't count toward ${counts}.`;
  return '';
}
