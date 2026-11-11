import { Component, computed, inject, signal } from '@angular/core';
import { CardTrackerApi } from './api';
import { GameFreshness } from './api.models';
import { Disclosures } from './disclosures';
import { Movers } from './movers';
import { Search } from './search';
import { Sets } from './sets';
import { Settings } from './settings';

type Tab = 'search' | 'movers' | 'sets' | 'disclosures' | 'settings';

/**
 * Five screens, switched by a signal rather than the router. Nothing needs a
 * deep link yet, so wiring up @angular/router would be ceremony. Revisit when
 * a screen does need its own URL, for sharing a link or for browser
 * back/forward.
 */
@Component({
  selector: 'app-root',
  imports: [Search, Movers, Sets, Disclosures, Settings],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly api = inject(CardTrackerApi);

  protected readonly tab = signal<Tab>('search');
  protected readonly tabs: readonly { id: Tab; label: string }[] = [
    { id: 'search', label: 'Search' },
    { id: 'movers', label: 'Movers' },
    { id: 'sets', label: 'Sets' },
    { id: 'disclosures', label: 'Disclosures' },
    { id: 'settings', label: 'Settings' },
  ];

  /**
   * Games whose price data has stopped arriving.
   *
   * Once, the scheduled pull failed silently for five days. Nothing was
   * broken on screen - the app served the last data it had, perfectly happily
   * - so the only symptom was numbers that quietly stopped moving. A banner
   * on every screen is the cheapest thing that makes that impossible to miss,
   * and it belongs at the root rather than on one screen for the same reason:
   * whichever screen you happen to open should be the one that tells you.
   *
   * Checked once at startup rather than polled. The pull runs once a day,
   * so anything more frequent would be asking a question whose answer cannot
   * have changed.
   */
  protected readonly staleGames = signal<GameFreshness[]>([]);

  protected readonly staleNote = computed(() => {
    const games = this.staleGames();
    if (!games.length) return null;
    const names = games
      .map((g) => `${g.game} (${g.daysStale} day${g.daysStale === 1 ? '' : 's'})`)
      .join(', ');
    return games.length === 1
      ? `Price data has stopped arriving for ${names}.`
      : `Price data has stopped arriving for: ${names}.`;
  });

  constructor() {
    this.api.health().subscribe({
      // A failure here is not worth a banner of its own: every screen already
      // reports its own inability to reach the API, and two alarms for one
      // cause is how people learn to ignore alarms.
      error: () => {},
      next: (h) => this.staleGames.set(
        h.games.filter((g) => g.daysStale >= h.staleAfterDays),
      ),
    });
  }
}
