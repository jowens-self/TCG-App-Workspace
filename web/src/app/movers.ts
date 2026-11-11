import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CardTrackerApi } from './api';
import { Game, GameSet, Mover } from './api.models';
import { CardDetail, CardMove } from './card-detail';
import { CardThumb } from './card-thumb';
import {
  PRE_RELEASE_TITLE,
  earlyPricingTitle,
  earlyWeeks,
  launchNote,
} from './launch-pricing';
import { UserPrefs } from './user-prefs';
import { modernRule } from './modern-rule';

/**
 * Movers: "what moved this week".
 *
 * Opens on the biggest GAINS over 7 days across every tracked game (owner's
 * call, 2026-09-30; it opened on decliners before). Picking a game narrows
 * it, and the set filter narrows it further once you know where you're
 * looking.
 *
 * The opening values mirror item_movers()'s own defaults (7 days, gains,
 * singles, $1 floor), so the screen and a bare psql call show the same board.
 *
 * Clicking a row opens the card right under it, with the same card view
 * Search uses. It used to open below all fifty rows, where nobody saw it.
 */
@Component({
  selector: 'app-movers',
  imports: [FormsModule, CurrencyPipe, DecimalPipe, CardDetail, CardThumb],
  templateUrl: './movers.html',
  styleUrl: './movers.css',
  host: { class: 'page' },
})
export class Movers {
  private readonly api = inject(CardTrackerApi);

  protected readonly games = signal<Game[]>([]);
  protected readonly sets = signal<GameSet[]>([]);
  protected readonly rows = signal<Mover[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  /** The row whose card is open under it, or null. */
  protected readonly selected = signal<Mover | null>(null);

  /** Whether launch pricing counts. Shared with every screen and the open card. */
  protected readonly launch = inject(UserPrefs);
  protected readonly preReleaseTitle = PRE_RELEASE_TITLE;

  protected game: number | null = null;
  protected set: number | null = null;
  protected days = 7;
  protected direction: 'up' | 'down' = 'up';
  protected minPrice = 1;
  protected includeSealed = false;

  /**
   * A signal, unlike the fields above, because the set dropdown follows it
   * too. Filtered server-side for the board (it has to happen before the
   * top-50 cut) and client-side for the dropdown, both from the server's
   * answer, never a date comparison here.
   */
  protected readonly modernOnly = signal(false);

  /** The open preview, so a card opened near the bottom can be scrolled into view. */
  private readonly preview = viewChild<ElementRef<HTMLElement>>('preview');

  constructor() {
    this.api.games().subscribe({
      next: (g) => {
        this.games.set(g);
        this.load();
      },
      error: () => this.error.set('Could not reach the API. Is ApiServer running on :7070?'),
    });

    // Runs once the preview row exists. "nearest" only scrolls when the
    // preview would otherwise hang off the bottom of the screen.
    effect(() => {
      this.preview()?.nativeElement.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.clearSelection();
    this.api
      .movers({
        days: this.days,
        game: this.game,
        set: this.set,
        includeSealed: this.includeSealed,
        modernOnly: this.modernOnly(),
        includePreRelease: this.launch.includePreRelease(),
        includeEarly: this.launch.includeEarly(),
        minPrice: this.minPrice,
        direction: this.direction,
        limit: 50,
      })
      .subscribe({
        next: (r) => {
          this.rows.set(r);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Could not load movers.');
        },
      });
  }

  /** Changing game invalidates the set filter, since a set belongs to one game. */
  protected onGameChange(): void {
    this.set = null;
    this.sets.set([]);
    if (this.game != null) {
      this.api.sets(this.game).subscribe({
        next: (s) => this.sets.set(s),
        error: () => this.error.set('Could not load sets for that game.'),
      });
    }
    this.load();
  }

  /** The set dropdown's options: only modern sets while that filter is on. */
  protected setOptions(): GameSet[] {
    return this.modernOnly() ? this.sets().filter((s) => s.modern) : this.sets();
  }

  /** What "Modern only" means for the game on screen. */
  protected modernNote(): string {
    return modernRule(this.games(), this.game);
  }

  protected earlyTitle(): string {
    return earlyPricingTitle(earlyWeeks(this.games(), this.game));
  }

  /** What the launch-pricing boxes are leaving off the board, or ''. */
  protected launchNote(): string {
    return launchNote(
      this.launch.includePreRelease(),
      this.launch.includeEarly(),
      earlyWeeks(this.games(), this.game),
      'the board',
    );
  }

  /**
   * Turning modern on drops a picked set that isn't modern. Keeping it would
   * leave an empty board for a reason nothing on screen explains.
   */
  protected onModernChange(on: boolean): void {
    this.modernOnly.set(on);
    if (on && this.set !== null && !this.setOptions().some((s) => s.groupId === this.set)) {
      this.set = null;
    }
    this.load();
  }

  protected isSelected(row: Mover): boolean {
    const sel = this.selected();
    return sel !== null && sel.productId === row.productId && sel.subTypeName === row.subTypeName;
  }

  /** Clicking the open row again closes it. */
  protected toggle(row: Mover): void {
    this.selected.set(this.isSelected(row) ? null : row);
  }

  /**
   * The move the open row was listed for, restated at the top of its card.
   * Computed once per selection rather than built in the template, which
   * would hand the card a new object on every change-detection pass. Any
   * change to the window reloads the board and closes the card first, so
   * `days` can't go stale here.
   */
  protected readonly selectedMove = computed<CardMove | null>(() => {
    const row = this.selected();
    return row
      ? {
          subTypeName: row.subTypeName,
          thenPrice: row.thenPrice,
          nowPrice: row.nowPrice,
          pctChange: row.pctChange,
          days: this.days,
        }
      : null;
  });

  protected clearSelection(): void {
    this.selected.set(null);
  }
}
