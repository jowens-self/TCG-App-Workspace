import { Component, computed, inject, signal } from '@angular/core';
import { CardTrackerApi } from './api';
import { Game } from './api.models';
import { modernRule } from './modern-rule';
import { UserPrefs } from './user-prefs';

/**
 * Disclosures: what every number on the other screens includes, what it
 * leaves out, and how it is worked out, in plain words for the people
 * reading them.
 *
 * Owner's request, 2026-09-30. This page is a promise about the code, so it
 * changes whenever a rule it describes changes (CLAUDE.md says so). The rules
 * that are data (each game's modern cutoff and early-pricing length, the
 * staleness threshold) are read live rather than written in, and the user's
 * own boxes are shown as they are set, so those parts can't drift. The fixed
 * thresholds (3-day medians, $10 despiking) are written
 * here and in sql/functions.sql, and each side names the other.
 */
@Component({
  selector: 'app-disclosures',
  templateUrl: './disclosures.html',
  styleUrl: './disclosures.css',
  host: { class: 'page' },
})
export class Disclosures {
  private readonly api = inject(CardTrackerApi);
  protected readonly prefs = inject(UserPrefs);

  protected readonly games = signal<Game[]>([]);
  protected readonly staleAfterDays = signal<number | null>(null);

  protected readonly modernRule = computed(() => modernRule(this.games(), null));

  /** "8 weeks for Pokemon and 8 weeks for One Piece Card Game". */
  protected readonly earlyWeeks = computed(() => {
    const parts = this.games().map((g) => `${Math.round(g.earlyDays / 7)} weeks for ${g.name}`);
    return parts.length <= 1
      ? (parts[0] ?? 'a few weeks')
      : `${parts.slice(0, -1).join(', ')} and ${parts.at(-1)}`;
  });

  constructor() {
    this.api.games().subscribe({ next: (g) => this.games.set(g), error: () => {} });
    this.api.health().subscribe({
      next: (h) => this.staleAfterDays.set(h.staleAfterDays),
      error: () => {},
    });
  }

  /** How one of the user's trend boxes is set right now, in words. */
  protected shown(on: boolean): string {
    return on ? 'You have this ticked, so it currently counts.' : 'Left out for you right now.';
  }
}
