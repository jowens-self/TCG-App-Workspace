import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  Observable,
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  of,
  switchMap,
} from 'rxjs';
import { CardTrackerApi } from './api';
import { Game, ItemSearchResult } from './api.models';
import { borrowedArtNote } from './borrowed-art';
import { CardDetail } from './card-detail';
import { CardThumb } from './card-thumb';
import { variantLabelAddsInfo } from './variant-label';

/**
 * Card / Product Search, the first screen.
 *
 * Deliberately named "item" throughout, matching the API and the SQL
 * functions: sealed product is looked up exactly the same way a single is,
 * so nothing here says "card" when it means "any product."
 *
 * The flow mirrors how the data is actually shaped, rather than flattening
 * it: search finds a product, the product exposes its variants (which
 * genuinely differ in price), and a chosen variant has a history. Skipping
 * the variant step would mean picking a market for the user.
 */
@Component({
  selector: 'app-search',
  imports: [FormsModule, CardDetail, CardThumb],
  templateUrl: './search.html',
  styleUrl: './search.css',
  host: { class: 'page' },
})
export class Search {
  private readonly api = inject(CardTrackerApi);

  /**
   * Rows per request, and the page size "Load more" advances by.
   *
   * 50 rather than the API's 200 ceiling: a longer page is more thumbnails
   * to fetch at once for rows nobody has scrolled to yet.
   */
  private static readonly PAGE = 50;

  /**
   * Templates can only call class members, so the shared rule is re-exposed
   * here rather than reimplemented, so search and any future screen showing
   * labels must agree on when one is worth showing.
   */
  protected readonly variantLabelAddsInfo = variantLabelAddsInfo;
  protected readonly borrowedArtNote = borrowedArtNote;

  protected readonly games = signal<Game[]>([]);
  protected readonly results = signal<ItemSearchResult[]>([]);
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * Whether the last page came back full, which is the only evidence we have
   * that more exist, since the endpoint returns rows rather than a total.
   * A short page ends the list and the button disappears. A page that is
   * exactly full but happens to be the last costs one extra request to find
   * that out, which beats claiming a count the server never gave us.
   */
  protected readonly moreAvailable = signal(false);
  protected readonly loadingMore = signal(false);

  /**
   * Bumped every time a new result list is established. "Load more"
   * captures it and drops its answer if it has moved. Otherwise a page
   * still in flight when a new search lands would append the old query's
   * rows to the new query's list.
   */
  private generation = 0;

  /** The card open in the side panel, shown by card-detail. */
  protected readonly selected = signal<ItemSearchResult | null>(null);

  protected query = '';
  protected game: number | null = null;
  protected hideSealed = false;

  /**
   * Typing pushes into this subject rather than firing a request per
   * keystroke. debounceTime waits for a pause; switchMap cancels an
   * in-flight request when a newer one starts, which is what stops slow
   * early results from arriving after (and overwriting) fast later ones.
   */
  private readonly typed = new Subject<string>();

  constructor() {
    this.api.games().subscribe({
      next: (g) => this.games.set(g),
      error: () => this.error.set('Could not reach the API. Is ApiServer running on :7070?'),
    });

    this.typed
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap((q) => this.firstPage(q)),
      )
      .subscribe((rows) => this.showFirstPage(rows));
  }

  /**
   * The first page for a query under the current filters, or null if the
   * request failed. The failure is caught here rather than in subscribe():
   * an error reaching the typing stream would end it, and the box would
   * ignore every keystroke after one failed search.
   */
  private firstPage(q: string): Observable<ItemSearchResult[] | null> {
    this.searching.set(true);
    this.error.set(null);
    return this.api
      .search(q, { game: this.game, includeSealed: !this.hideSealed, limit: Search.PAGE })
      .pipe(
        catchError(() => {
          this.error.set('Search failed. Is ApiServer running on :7070?');
          return of(null);
        }),
      );
  }

  private showFirstPage(rows: ItemSearchResult[] | null): void {
    this.searching.set(false);
    this.searched.set(true);
    if (rows) this.setResults(rows);
  }

  protected onQueryChange(value: string): void {
    this.query = value;
    const trimmed = value.trim();
    if (!trimmed) {
      // The API rejects an empty q with 400, so don't ask.
      this.setResults([]);
      this.searched.set(false);
      this.clearSelection();
      return;
    }
    this.typed.next(trimmed);
  }

  /** Filters change what the current query means, so re-run it. */
  protected onFilterChange(): void {
    const trimmed = this.query.trim();
    if (trimmed) {
      this.setResults([]);
      // distinctUntilChanged would swallow an identical query string, so
      // go straight to the API instead of pushing through the subject.
      this.firstPage(trimmed).subscribe((rows) => this.showFirstPage(rows));
    }
    this.clearSelection();
  }

  /**
   * Replaces the result list and starts a new page generation. Every path
   * that establishes a fresh list goes through here so none of them can
   * forget to invalidate a page request already in flight.
   */
  private setResults(rows: ItemSearchResult[]): void {
    this.generation++;
    this.results.set(rows);
    this.moreAvailable.set(rows.length === Search.PAGE);
  }

  /**
   * Appends the next page.
   *
   * Deliberately not routed through the debounced subject: switchMap would
   * cancel it against the next keystroke, and its whole job is to add to the
   * current list rather than replace it. The offset is the list's own length
   * rather than a counter, so the two cannot drift apart.
   *
   * Paging is only sound because item_search() orders by
   * (rank, length(name), name, product_id), which is deterministic down to
   * the tiebreaker. Verified against the live API with three consecutive
   * pages of "Charizard": zero overlap, identical on a repeat call. An
   * ORDER BY that ties would silently duplicate and skip rows here.
   */
  protected loadMore(): void {
    const trimmed = this.query.trim();
    if (!trimmed || this.loadingMore()) return;

    const issuedUnder = this.generation;
    this.loadingMore.set(true);
    this.api
      .search(trimmed, {
        game: this.game,
        includeSealed: !this.hideSealed,
        limit: Search.PAGE,
        offset: this.results().length,
      })
      .subscribe({
        next: (rows) => {
          this.loadingMore.set(false);
          if (issuedUnder !== this.generation) return; // a newer search won
          this.results.update((cur) => [...cur, ...rows]);
          this.moreAvailable.set(rows.length === Search.PAGE);
        },
        error: () => {
          this.loadingMore.set(false);
          this.error.set('Could not load more results.');
        },
      });
  }

  /** Opens a result in the side panel; card-detail loads everything else. */
  protected select(item: ItemSearchResult): void {
    this.selected.set(item);
  }

  protected clearSelection(): void {
    this.selected.set(null);
  }
}
