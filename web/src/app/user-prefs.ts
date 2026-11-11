import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { CardTrackerApi } from './api';
import { UserSettings } from './api.models';

/** What a user who has never saved anything gets; matches the table's defaults. */
export const DEFAULT_SETTINGS: UserSettings = {
  includePreRelease: false,
  includeEarly: false,
};

export function sameSettings(a: UserSettings, b: UserSettings): boolean {
  return (
    a.includePreRelease === b.includePreRelease &&
    a.includeEarly === b.includeEarly
  );
}

/**
 * The user's preferences, one signal each, shared by every screen.
 *
 * Loaded from the server before the app draws anything (see app.config.ts),
 * so no screen fetches data under a default and then has to redo it. Any
 * change, from the Settings screen or from the same checkbox on Movers or
 * Sets, is saved back on its own; callers just set the signal.
 *
 * On the server rather than in localStorage: browser storage vanishes with
 * the site data and differs per browser, and preferences are per user.
 */
@Injectable({ providedIn: 'root' })
export class UserPrefs {
  private readonly api = inject(CardTrackerApi);

  readonly includePreRelease = signal(DEFAULT_SETTINGS.includePreRelease);
  readonly includeEarly = signal(DEFAULT_SETTINGS.includeEarly);

  /** Set when the last save failed, so the Settings screen can say so. */
  readonly saveFailed = signal(false);

  /**
   * What the server last confirmed. Null until loaded, which is what stops the
   * effect below from saving the defaults over real settings at startup.
   */
  private saved: UserSettings | null = null;

  constructor() {
    effect(() => {
      const next: UserSettings = {
        includePreRelease: this.includePreRelease(),
        includeEarly: this.includeEarly(),
      };
      if (this.saved === null || sameSettings(next, this.saved)) return;
      this.saved = next;
      this.api.saveSettings(next).subscribe({
        next: () => this.saveFailed.set(false),
        error: () => this.saveFailed.set(true),
      });
    });
  }

  /**
   * Resolves once the settings are in, or once it is clear they won't be. An
   * unreachable API still lets the app start, on the defaults: every screen
   * already says when it can't reach the API.
   */
  async load(): Promise<void> {
    let settings = DEFAULT_SETTINGS;
    try {
      settings = await firstValueFrom(this.api.settings());
    } catch {
      // Defaults, as above.
    }
    this.saved = settings;
    this.includePreRelease.set(settings.includePreRelease);
    this.includeEarly.set(settings.includeEarly);
  }
}
