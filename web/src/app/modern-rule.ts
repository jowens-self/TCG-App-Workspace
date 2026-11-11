import { Game } from './api.models';

/**
 * What "Modern only" means for the game(s) on screen, in words.
 *
 * Built from each game's modernSince rather than written into a template, so
 * moving the cutoff (one UPDATE, see sql/migrations/003_modern_sets.sql)
 * moves this sentence with it. Which sets actually qualify is never decided
 * here; that is the server's modern_group_ids().
 *
 * selected null means "All games".
 */
export function modernRule(games: readonly Game[], selected: number | null): string {
  const shown = selected === null ? games : games.filter((g) => g.categoryId === selected);
  if (shown.length === 0) return '';

  const parts = shown.map((g) =>
    g.modernSince ? `${g.name} sets released on or after ${g.modernSince}` : `every ${g.name} set`,
  );
  const rule =
    parts.length === 1 ? parts[0] : `${parts.slice(0, -1).join(', ')} and ${parts.at(-1)}`;

  // Only a game with a cutoff can leave anything out.
  const undated = shown.some((g) => g.modernSince)
    ? ' Promo and catch-all groups with no release date are left out.'
    : '';
  return `Modern means ${rule}.${undated}`;
}
