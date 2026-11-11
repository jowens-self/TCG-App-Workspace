import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CardTrackerApi } from './api';
import { Game, SetOverview } from './api.models';
import {
  PRE_RELEASE_TITLE,
  earlyPricingTitle,
  earlyWeeks,
  launchNote,
} from './launch-pricing';
import { UserPrefs } from './user-prefs';
import { modernRule } from './modern-rule';

/**
 * Set overview: "what is each set worth, and how did it move."
 *
 * The screen the set_value()/set_return() primitives were built for. The
 * empty-row and modern filters are client-side on purpose: ~300 sets arrive
 * in one response, newest first, so filtering is free and costs no round
 * trip.
 *
 * Note which state is a signal and which is a plain field. Anything `visible`
 * reads must be a signal or the computed won't recompute when it changes;
 * `game` and `days` are plain fields because changing them refetches, which
 * updates `rows` anyway.
 *
 * On CONCENTRATION, which is why this screen shows more than a percentage.
 * Measured 2026-08-18 against real data: among Pokemon sets with a >=2%
 * 30-day move, the biggest single contributing row is routinely 50-87% of
 * all movement in the set. Base Set (Shadowless) read +59.96% and $9,750 of
 * its $10,718 move was one card whose market price toggles between ~$250
 * and exactly $10,000 for weeks at a time. Showing the percentage alone
 * would let a one-card artifact read as a set-wide verdict, so the biggest
 * contributor is shown beside it and flagged past a threshold.
 */
@Component({
  selector: 'app-sets',
  imports: [FormsModule, CurrencyPipe, DecimalPipe],
  templateUrl: './sets.html',
  styleUrl: './sets.css',
  host: { class: 'page' },
})
export class Sets {
  private readonly api = inject(CardTrackerApi);

  /**
   * Past this share of a set's gross movement, one row is doing enough of
   * the work that the set-level number shouldn't be read as broad. 50% is a
   * deliberate round number, not a fitted one: it's the point where a
   * single card outweighs every other card in the set combined.
   */
  private static readonly CONCENTRATION_FLAG = 50;

  protected readonly games = signal<Game[]>([]);
  protected readonly rows = signal<SetOverview[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Refetch on change, so these need not be signals. */
  protected game: number | null = null;
  protected days = 30;

  /**
   * Sets with no priced basket have a value but no return. Hidden by
   * default because a column of blanks buries the sets that do have an
   * answer, but they are real sets, so it is a toggle rather than a filter
   * baked into the query.
   */
  protected readonly hideEmpty = signal(true);

  /**
   * Replace readings that swung hard and reverted the next day with the
   * median of their neighbours. On by default (the server's default too).
   * Exposed as a toggle rather than hidden because it CHANGES REPORTED
   * NUMBERS. On 2026-08-04 it moved EX Dragon Frontiers from -20.91% to
   * +8.43%, and a correction you cannot switch off is a correction you
   * cannot check.
   */
  protected readonly despike = signal(true);

  /**
   * Client-side like hideEmpty: every row already says whether its set is
   * modern, so switching costs no round trip. Off by default, so the table
   * a user already knows is where they start.
   */
  protected readonly modernOnly = signal(false);

  /**
   * Whether launch pricing counts toward a set's change. Server-side, unlike
   * modern, because it changes which cards are in the comparison. Shared
   * with the other screens.
   */
  protected readonly launch = inject(UserPrefs);
  protected readonly preReleaseTitle = PRE_RELEASE_TITLE;

  /**
   * New sets with nothing settled to compare yet, hidden by "Hide sets with
   * no return". Counted so the screen can say they're hidden, not gone.
   */
  protected readonly hiddenNewCount = computed(() => {
    if (!this.hideEmpty()) return 0;
    const modernOnly = this.modernOnly();
    return this.rows().filter(
      (r) => r.pctReturn === null && r.settlesOn !== null && (!modernOnly || r.modern),
    ).length;
  });

  protected readonly visible = computed(() => {
    const hideEmpty = this.hideEmpty();
    const modernOnly = this.modernOnly();
    return this.rows().filter(
      (r) => (!hideEmpty || r.pctReturn !== null) && (!modernOnly || r.modern),
    );
  });

  /** The window the server actually resolved, echoed back on every row. */
  protected readonly window = computed(() => {
    const first = this.rows()[0];
    return first ? { from: first.startDate, to: first.endDate } : null;
  });

  /** How many of the visible sets are carried by a single card. */
  protected readonly flaggedCount = computed(
    () => this.visible().filter((r) => this.concentrated(r)).length,
  );

  /** How many visible sets had at least one reading replaced. */
  protected readonly despikedCount = computed(
    () => this.visible().filter((r) => r.nDespiked > 0).length,
  );

  constructor() {
    this.api.games().subscribe({
      next: (g) => {
        this.games.set(g);
        this.load();
      },
      error: () => this.error.set('Could not reach the API. Is ApiServer running on :7070?'),
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .setOverview({
        game: this.game,
        days: this.days,
        despike: this.despike(),
        includePreRelease: this.launch.includePreRelease(),
        includeEarly: this.launch.includeEarly(),
      })
      .subscribe({
        next: (r) => {
          this.rows.set(r);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Could not load the set overview.');
        },
      });
  }

  /**
   * Why a row's numbers were adjusted, spelled out per row rather than left
   * to a legend, since the tooltip is where someone checks a number they don't
   * believe.
   */
  protected despikeTitle(r: SetOverview): string {
    const n = r.nDespiked;
    const noun = n === 1 ? 'reading' : 'readings';
    return (
      `${n} ${noun} in this set swung hard and returned to the previous level ` +
      `the next day, so the median of the neighbouring days was used instead ` +
      `(total adjustment $${r.despikeUsd ?? 0}). Stored prices are unchanged. ` +
      `Untick "Correct one-day price errors" to see the raw comparison.`
    );
  }

  /** What "Modern only" means for the game on screen. */
  protected modernNote(): string {
    return modernRule(this.games(), this.game);
  }

  protected earlyTitle(): string {
    return earlyPricingTitle(earlyWeeks(this.games(), this.game));
  }

  /** What the launch-pricing boxes are leaving out of each set's change, or ''. */
  protected launchNote(): string {
    return launchNote(
      this.launch.includePreRelease(),
      this.launch.includeEarly(),
      earlyWeeks(this.games(), this.game),
      "a set's change",
    );
  }

  /** A new set's change cell: still settling, or settled after the window began. */
  protected settlingLabel(r: SetOverview): string {
    return r.settlesOn! > r.endDate ? `New · settles ${r.settlesOn}` : `Settled ${r.settlesOn}`;
  }

  protected settlingTitle(r: SetOverview): string {
    const released = r.publishedOn ? `Released ${r.publishedOn}. ` : '';
    return r.settlesOn! > r.endDate
      ? `${released}Its prices are still settling, and pre-release and early pricing ` +
          `aren't counted, so there's no change to show until ${r.settlesOn}. ` +
          `Tick the boxes above to include them.`
      : `${released}On ${r.startDate}, the start of this window, its prices were still ` +
          `settling, so there's nothing settled to compare. A shorter window will show a change.`;
  }

  /** True when one row drives enough of the move to distrust the total. */
  protected concentrated(r: SetOverview): boolean {
    return r.topShare !== null && r.topShare >= Sets.CONCENTRATION_FLAG;
  }

  /**
   * Cards priced on the end date but not at the window's start. Their value
   * counts toward totalValue and NOT toward the return, which is exactly
   * why both numbers are shown rather than one.
   */
  protected notInBasket(r: SetOverview): number {
    return r.nPriced - r.nCommon;
  }
}
