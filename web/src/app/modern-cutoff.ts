import { GameSet } from './api.models';

/** One choice for "modern starts with": a release date and the sets out that day. */
export interface CutoffChoice {
  /** The date saved as modern_since. */
  readonly date: string;
  readonly label: string;
}

/** Choices grouped by year, newest year first, for <optgroup>s. */
export interface CutoffYear {
  readonly year: string;
  readonly choices: readonly CutoffChoice[];
}

/**
 * The game's release dates, as "modern starts with" choices.
 *
 * A cutoff is a date, but people think in sets ("Sword & Shield on"), so each
 * date is labelled with the sets released that day. Picking a set picks its
 * date, and the server's rule then counts every set on or after it. That rule
 * is never re-run here: this only lists dates.
 *
 * Undated sets aren't choices, since they can't start anything.
 */
export function cutoffYears(sets: readonly GameSet[]): CutoffYear[] {
  const byDate = new Map<string, string[]>();
  for (const s of sets) {
    if (!s.publishedOn) continue;
    const names = byDate.get(s.publishedOn) ?? [];
    names.push(s.name);
    byDate.set(s.publishedOn, names);
  }

  const years = new Map<string, CutoffChoice[]>();
  for (const date of [...byDate.keys()].sort().reverse()) {
    const names = byDate.get(date)!;
    const choice = { date, label: `${shorten(names.join(' / '), 70)} (${date})` };
    const year = date.slice(0, 4);
    years.set(year, [...(years.get(year) ?? []), choice]);
  }
  return [...years].map(([year, choices]) => ({ year, choices }));
}

/**
 * Whether a saved cutoff is one of the listed dates. A cutoff set by hand in
 * SQL may not be, and the screen then offers it as its own choice rather than
 * silently showing a different one.
 */
export function isListedCutoff(years: readonly CutoffYear[], date: string | null): boolean {
  return date === null || years.some((y) => y.choices.some((c) => c.date === date));
}

function shorten(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}
