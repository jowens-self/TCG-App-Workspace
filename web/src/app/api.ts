import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Game,
  GameRules,
  GameSet,
  Health,
  ImageProvenance,
  ItemSearchResult,
  ItemVariant,
  Mover,
  PricePoint,
  SetOverview,
  UserSettings,
} from './api.models';

/**
 * The only thing in this app that knows a server exists.
 *
 * Every method is one endpoint of our own API. Nothing here talks to
 * Postgres or to tcgcsv: the mirror has a restrictive CORS policy and asks
 * that data be ingested server-side, so the browser never touches it.
 */
@Injectable({ providedIn: 'root' })
export class CardTrackerApi {
  private readonly http = inject(HttpClient);

  /**
   * The API's own dev address. CORS on the server is scoped to
   * localhost:4200 (the Angular dev server), so this works in `ng serve`
   * and would need revisiting alongside that rule if either ever moves.
   */
  private readonly base = 'http://localhost:7070/api';

  /**
   * Is price data still arriving? Reads the data, not the process: see
   * Health. Used for the staleness banner, which is the only thing standing
   * between a silent ingest failure and finding out days later.
   */
  health(): Observable<Health> {
    return this.http.get<Health>(`${this.base}/health`);
  }

  games(): Observable<Game[]> {
    return this.http.get<Game[]>(`${this.base}/games`);
  }

  search(
    query: string,
    opts: { game?: number | null; includeSealed?: boolean; limit?: number; offset?: number } = {},
  ): Observable<ItemSearchResult[]> {
    let params = new HttpParams().set('q', query);
    if (opts.game != null) params = params.set('game', opts.game);
    // Only sent when hiding sealed; the server defaults to including it.
    if (opts.includeSealed === false) params = params.set('sealed', 'false');
    if (opts.limit != null) params = params.set('limit', opts.limit);
    if (opts.offset != null) params = params.set('offset', opts.offset);
    return this.http.get<ItemSearchResult[]>(`${this.base}/items/search`, { params });
  }

  sets(categoryId: number): Observable<GameSet[]> {
    return this.http.get<GameSet[]>(`${this.base}/games/${categoryId}/sets`);
  }

  /**
   * Every set with its current value and windowed return. game omitted =
   * all tracked games. days is left unset unless changed so the server's
   * own default (30) stays the single definition of the default view.
   *
   * despike defaults on server-side: readings that swung hard and reverted
   * the next day are replaced with the median of their neighbours. Pass
   * false for the raw two-point comparison.
   */
  setOverview(
    opts: {
      game?: number | null;
      days?: number;
      despike?: boolean;
      includePreRelease?: boolean;
      includeEarly?: boolean;
    } = {},
  ): Observable<SetOverview[]> {
    let params = new HttpParams();
    if (opts.game != null) params = params.set('game', opts.game);
    if (opts.days != null) params = params.set('days', opts.days);
    // Only sent when turning it off; the server defaults it on.
    if (opts.despike === false) params = params.set('despike', 'false');
    params = withLaunchPricing(params, opts);
    return this.http.get<SetOverview[]>(`${this.base}/sets/overview`, { params });
  }

  /**
   * Biggest movers. Every option is omitted unless set, so the server's own
   * defaults (7 days, gains, singles, $1 floor, 3-day median) apply -
   * they live in item_movers() rather than being restated here.
   */
  movers(opts: {
    days?: number;
    game?: number | null;
    set?: number | null;
    includeSealed?: boolean;
    modernOnly?: boolean;
    includePreRelease?: boolean;
    includeEarly?: boolean;
    minPrice?: number;
    direction?: 'up' | 'down';
    limit?: number;
  } = {}): Observable<Mover[]> {
    let params = new HttpParams();
    if (opts.days != null) params = params.set('days', opts.days);
    if (opts.game != null) params = params.set('game', opts.game);
    if (opts.set != null) params = params.set('set', opts.set);
    if (opts.includeSealed) params = params.set('sealed', 'true');
    if (opts.modernOnly) params = params.set('modern', 'true');
    if (opts.minPrice != null) params = params.set('minPrice', opts.minPrice);
    if (opts.direction) params = params.set('direction', opts.direction);
    if (opts.limit != null) params = params.set('limit', opts.limit);
    params = withLaunchPricing(params, opts);
    return this.http.get<Mover[]>(`${this.base}/movers`, { params });
  }

  /** The user's preferences; the defaults if they have never saved any. */
  settings(): Observable<UserSettings> {
    return this.http.get<UserSettings>(`${this.base}/settings`);
  }

  saveSettings(settings: UserSettings): Observable<UserSettings> {
    return this.http.put<UserSettings>(`${this.base}/settings`, settings);
  }

  /** Sets a game's rules and answers with the game as /games describes it. */
  saveGameRules(categoryId: number, rules: GameRules): Observable<Game> {
    return this.http.put<Game>(`${this.base}/games/${categoryId}/rules`, rules);
  }

  /**
   * URL of a card's thumbnail on our own server, never a third party. The
   * browser must not fetch images from anyone else (see CLAUDE.md). Returns
   * 404 when we have no image, which the caller renders as a placeholder.
   */
  imageUrl(productId: number): string {
    return `${this.base}/items/${productId}/image`;
  }

  /**
   * Whose artwork this card's thumbnail shows. Its own call rather than part
   * of item(), because only the detail view needs it.
   * 404s when we hold no image, which is ordinary, so callers swallow it.
   */
  imageProvenance(productId: number): Observable<ImageProvenance> {
    return this.http.get<ImageProvenance>(
      `${this.base}/items/${productId}/image/provenance`,
    );
  }

  item(productId: number): Observable<ItemSearchResult> {
    return this.http.get<ItemSearchResult>(`${this.base}/items/${productId}`);
  }

  variants(productId: number): Observable<ItemVariant[]> {
    return this.http.get<ItemVariant[]>(`${this.base}/items/${productId}/variants`);
  }

  /**
   * subType is required by the server and has no default, since picking one for
   * the caller would mean guessing which market they meant. Get valid
   * values from variants().
   */
  history(
    productId: number,
    subType: string,
    from?: string,
    to?: string,
  ): Observable<PricePoint[]> {
    let params = new HttpParams().set('subType', subType);
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    return this.http.get<PricePoint[]>(`${this.base}/items/${productId}/history`, { params });
  }
}

/**
 * Launch pricing is left out server-side unless asked for, so each flag is
 * sent only when it turns something ON, the same way sealed is.
 */
function withLaunchPricing(
  params: HttpParams,
  opts: { includePreRelease?: boolean; includeEarly?: boolean },
): HttpParams {
  if (opts.includePreRelease) params = params.set('prerelease', 'true');
  if (opts.includeEarly) params = params.set('early', 'true');
  return params;
}
