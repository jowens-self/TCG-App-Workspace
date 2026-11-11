import { Component, computed, inject, input, linkedSignal } from '@angular/core';
import { CardTrackerApi } from './api';

/**
 * A card's thumbnail, served off our own disk and never from a third party
 * (a TCGplayer CDN thumbnail is what broke the rule once; see CLAUDE.md).
 *
 * A missing image is ordinary, not an error: about 5% of Pokemon singles and
 * every One Piece card have none. The picture stays invisible until it has
 * loaded, and a 404 drops it, so the browser never paints its broken-image
 * glyph and the sized box stays as the placeholder.
 *
 * The host is the box. A screen sizes it with its own CSS, like any element.
 */
@Component({
  selector: 'card-thumb',
  templateUrl: './card-thumb.html',
  styleUrl: './card-thumb.css',
  host: { 'aria-hidden': 'true' },
})
export class CardThumb {
  private readonly api = inject(CardTrackerApi);

  readonly productId = input.required<number>();

  protected readonly imageUrl = computed(() => this.api.imageUrl(this.productId()));
  /** Reset whenever the card changes, so one card's miss never hides the next. */
  protected readonly loaded = linkedSignal({ source: this.productId, computation: () => false });
  protected readonly failed = linkedSignal({ source: this.productId, computation: () => false });
}
