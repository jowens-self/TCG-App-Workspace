import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { UserPrefs } from './user-prefs';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Required for CardTrackerApi's HttpClient injection.
    provideHttpClient(),
    // Preferences arrive before the first screen draws, so no screen loads
    // data under the defaults and then has to redo it.
    provideAppInitializer(() => inject(UserPrefs).load()),
  ],
};
