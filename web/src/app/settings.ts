import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CardTrackerApi } from './api';
import { Game } from './api.models';
import { PRE_RELEASE_TITLE, earlyPricingTitle } from './launch-pricing';
import { CutoffYear, cutoffYears, isListedCutoff } from './modern-cutoff';
import { UserPrefs } from './user-prefs';

/** One game's rules as edited on screen, before Save. */
interface RulesDraft {
  modernSince: string | null;
  earlyWeeks: number;
}

/** Per-game state: the set list behind the cutoff choices, and the save. */
interface GamePanel {
  game: Game;
  years: CutoffYear[];
  draft: RulesDraft;
  saving: boolean;
  saved: boolean;
  error: string | null;
}

/**
 * Settings: the owner's preferences, and each game's rules.
 *
 * Two kinds of setting, saved differently on purpose:
 *
 * - Preferences (the launch-pricing boxes) are the
 *   user's own and save the moment they change, through UserPrefs, the same
 *   signals the checkboxes on Movers and Sets set.
 * - Game rules (where "modern" starts, how long early pricing lasts) change
 *   what every trend screen counts, so they take an explicit Save, and the
 *   answer says how many sets the new cutoff takes in.
 *
 * Early pricing is edited in weeks, the unit every label in the app uses, and
 * stored in days.
 */
@Component({
  selector: 'app-settings',
  imports: [FormsModule],
  templateUrl: './settings.html',
  styleUrl: './settings.css',
  host: { class: 'page' },
})
export class Settings {
  private readonly api = inject(CardTrackerApi);
  protected readonly prefs = inject(UserPrefs);
  protected readonly preReleaseTitle = PRE_RELEASE_TITLE;

  protected readonly panels = signal<GamePanel[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.api.games().subscribe({
      next: (games) => {
        this.panels.set(
          games.map((game) => ({
            game,
            years: [],
            draft: draftOf(game),
            saving: false,
            saved: false,
            error: null,
          })),
        );
        for (const g of games) {
          this.api.sets(g.categoryId).subscribe({
            next: (sets) => this.patch(g.categoryId, { years: cutoffYears(sets) }),
            error: () => this.patch(g.categoryId, { error: 'Could not load the sets.' }),
          });
        }
      },
      error: () => this.error.set('Could not reach the API. Is ApiServer running on :7070?'),
    });
  }

  protected earlyTitle(p: GamePanel): string {
    return earlyPricingTitle(Math.round(p.game.earlyDays / 7));
  }

  /** A saved cutoff no set was released on, offered as itself rather than lost. */
  protected unlistedCutoff(p: GamePanel): string | null {
    return p.years.length && !isListedCutoff(p.years, p.draft.modernSince)
      ? p.draft.modernSince
      : null;
  }

  protected setCutoff(p: GamePanel, modernSince: string | null): void {
    this.patch(p.game.categoryId, { draft: { ...p.draft, modernSince }, saved: false });
  }

  protected setEarlyWeeks(p: GamePanel, weeks: number): void {
    this.patch(p.game.categoryId, { draft: { ...p.draft, earlyWeeks: weeks }, saved: false });
  }

  /** Unsaved edits, so Save only lights up when it would do something. */
  protected changed(p: GamePanel): boolean {
    const saved = draftOf(p.game);
    return saved.modernSince !== p.draft.modernSince || saved.earlyWeeks !== p.draft.earlyWeeks;
  }

  protected validWeeks(p: GamePanel): boolean {
    const w = p.draft.earlyWeeks;
    return Number.isInteger(w) && w >= 0 && w <= 52;
  }

  protected save(p: GamePanel): void {
    const id = p.game.categoryId;
    this.patch(id, { saving: true, error: null });
    this.api
      .saveGameRules(id, { modernSince: p.draft.modernSince, earlyDays: p.draft.earlyWeeks * 7 })
      .subscribe({
        next: (game) => this.patch(id, { game, draft: draftOf(game), saving: false, saved: true }),
        error: () => this.patch(id, { saving: false, error: 'Could not save. Is the API running?' }),
      });
  }

  private patch(categoryId: number, change: Partial<GamePanel>): void {
    this.panels.update((all) =>
      all.map((p) => (p.game.categoryId === categoryId ? { ...p, ...change } : p)),
    );
  }
}

function draftOf(game: Game): RulesDraft {
  return { modernSince: game.modernSince, earlyWeeks: Math.round(game.earlyDays / 7) };
}
