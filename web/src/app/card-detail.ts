import { CurrencyPipe, DecimalPipe } from '@angular/common';
import {
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CardTrackerApi } from './api';
import { borrowedArtNote } from './borrowed-art';
import { ChartDatum, Game, ImageProvenance, ItemVariant, PricePoint } from './api.models';
import {
  PRE_RELEASE_TITLE,
  chartablePoints,
  earlyPricingTitle,
  earlyWeeks,
  hasLaunchPricing,
} from './launch-pricing';
import { UserPrefs } from './user-prefs';
import { PriceChart } from './price-chart';
import { variantLabelAddsInfo } from './variant-label';

/** Who a card is: enough to title the panel. Everything else is fetched. */
export interface CardIdentity {
  productId: number;
  name: string;
  setName: string;
  game: string;
  cardNumber: string | null;
  rarity: string | null;
  url: string | null;
  isSealed?: boolean;
  variantLabel?: string | null;
}

/** The move a Movers row reported, restated at the top of its preview. */
export interface CardMove {
  subTypeName: string;
  thenPrice: number;
  nowPrice: number;
  pctChange: number;
  days: number;
}

/**
 * Everything about one card: its picture, every variant's price and
 * all-time high, and a chart of the chosen variant's history.
 *
 * One component for both Search's side panel and the preview Movers opens
 * under a row, so the two can never drift into showing a card differently.
 * It lays itself out by its own width rather than the screen's: stacked in
 * Search's narrow column, picture beside the numbers in Movers' full-width
 * row (a container query in card-detail.css).
 */
@Component({
  selector: 'card-detail',
  imports: [FormsModule, CurrencyPipe, DecimalPipe, PriceChart],
  templateUrl: './card-detail.html',
  styleUrl: './card-detail.css',
})
export class CardDetail {
  private readonly api = inject(CardTrackerApi);

  readonly card = input.required<CardIdentity>();
  /** Variant to open on. Null opens on the most valuable one. */
  readonly variant = input<string | null>(null);
  /** Set by Movers: the move this card was listed for. */
  readonly move = input<CardMove | null>(null);
  /** For the early-pricing hover text, which depends on the card's game. */
  readonly games = input<readonly Game[]>([]);
  /**
   * 'always', or 'narrow' for a panel that is only dismissable when it
   * covers something (Search's side column on a wide screen covers nothing).
   */
  readonly closeButton = input<'always' | 'narrow'>('always');
  readonly closed = output<void>();

  protected readonly variantLabelAddsInfo = variantLabelAddsInfo;
  protected readonly launch = inject(UserPrefs);
  protected readonly preReleaseTitle = PRE_RELEASE_TITLE;

  protected readonly variants = signal<ItemVariant[]>([]);
  protected readonly activeVariant = signal<string | null>(null);
  protected readonly history = signal<PricePoint[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * Provenance of the picture, or null when we hold no image or have not
   * heard back. Only 'base_art' renders anything: that is the one verdict
   * that says the picture is provably another printing's.
   */
  protected readonly provenance = signal<ImageProvenance | null>(null);
  protected readonly imageMissing = signal(false);
  protected readonly imageLoaded = signal(false);

  /** The price view of the history, less any launch pricing that's unticked. */
  private readonly chartView = computed(() =>
    chartablePoints<ChartDatum>(
      this.history().map((p) => ({
        date: p.snapshotDate,
        value: p.marketPrice,
        phase: p.phase,
      })),
      this.launch.includePreRelease(),
      this.launch.includeEarly(),
    ),
  );
  protected readonly chartSeries = computed(() => this.chartView().points);
  protected readonly chartOnlyLaunch = computed(() => this.chartView().onlyLaunch);

  /** The boxes only appear for a card that has launch pricing to show or hide. */
  protected readonly hasLaunch = computed(() => hasLaunchPricing(this.history()));

  constructor() {
    // A new card (or a new starting variant) reloads everything. untracked
    // so the requests fired inside don't make this effect depend on the
    // signals they write.
    effect(() => {
      const card = this.card();
      const variant = this.variant();
      untracked(() => this.load(card, variant));
    });
  }

  private load(card: CardIdentity, variant: string | null): void {
    const id = card.productId;
    this.variants.set([]);
    this.history.set([]);
    this.activeVariant.set(null);
    this.provenance.set(null);
    this.imageMissing.set(false);
    this.imageLoaded.set(false);
    this.error.set(null);
    this.loading.set(true);

    // Alongside the variants, never ahead of them: a missing verdict must not
    // hold up the prices, which are what this is for. A 404 is the ordinary
    // "we have no image" case and stays silent.
    this.api.imageProvenance(id).subscribe({
      next: (p) => {
        if (this.card().productId === p.productId) this.provenance.set(p);
      },
      error: () => this.provenance.set(null),
    });

    this.api.variants(id).subscribe({
      next: (v) => {
        if (this.card().productId !== id) return; // another card was picked meanwhile
        this.variants.set(v);
        this.loading.set(false);
        // The asked-for variant if it exists, else the most valuable: for a
        // card with a $5 Normal and a $77 Reverse Holofoil, that's
        // overwhelmingly the one being looked up.
        const wanted =
          variant && v.some((x) => x.subTypeName === variant)
            ? variant
            : [...v].sort((a, b) => (b.latestMarket ?? 0) - (a.latestMarket ?? 0))[0]?.subTypeName;
        if (wanted) this.showVariant(wanted);
      },
      error: () => {
        if (this.card().productId !== id) return;
        this.loading.set(false);
        this.error.set('Could not load variants for this card.');
      },
    });
  }

  protected showVariant(subType: string): void {
    const id = this.card().productId;
    this.activeVariant.set(subType);
    this.history.set([]);
    this.api.history(id, subType).subscribe({
      next: (h) => {
        // Answers can land out of order when variants are clicked quickly.
        if (this.card().productId === id && this.activeVariant() === subType) this.history.set(h);
      },
      error: () => this.error.set(`No history for ${subType}.`),
    });
  }

  protected imageUrl(): string {
    return this.api.imageUrl(this.card().productId);
  }

  /**
   * Spells out what the base-art band means. Built here rather than in the
   * template because it is three conditional clauses, and a title attribute
   * assembled inline is the kind of thing that quietly loses a space.
   */
  protected provenanceTooltip(): string {
    const p = this.provenance();
    if (!p || p.provenance !== 'base_art') return '';
    return borrowedArtNote(p.strippedQualifier, p.ownerName);
  }

  /**
   * Percent change across what the chart draws, or null if unusable. The
   * drawn series, not the loaded one, so an unticked preorder price can't
   * set the starting point of the number shown right above the chart. None
   * for a card that hasn't settled: its chart is drawn anyway, but a change
   * measured from a preorder price is exactly what the boxes keep out.
   */
  protected totalReturn(): number | null {
    if (this.chartOnlyLaunch()) return null;
    const pts = this.chartSeries().filter((p) => p.value != null);
    if (pts.length < 2) return null;
    const start = pts[0].value!;
    const end = pts[pts.length - 1].value!;
    // Matches the server rule: no percent change off a zero/negative start.
    if (start <= 0) return null;
    return ((end - start) / start) * 100;
  }

  /** First day the chart draws, for the "Since" label over it. */
  protected shownFrom(): string | null {
    return this.chartSeries()[0]?.date ?? null;
  }

  /** Early pricing's length for this card's own game. */
  protected earlyTitle(): string {
    const games = this.games();
    const game = games.find((g) => g.name === this.card().game);
    return earlyPricingTitle(earlyWeeks(games, game?.categoryId ?? null));
  }
}
