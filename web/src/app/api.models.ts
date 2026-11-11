/**
 * Mirrors the Java records returned by the read API (see
 * com.cardtracker.model.* and README "Read API"). Field names match the
 * JSON exactly, so if a record changes on the server, change it here too.
 */

/** A tracked game, from GET /api/games. */
export interface Game {
  categoryId: number;
  name: string;
  productCount: number;
  /**
   * Sets released on or after this date count as modern. Null when every
   * set of the game does. Only used to say what "modern" means; which sets
   * qualify always comes from the server.
   */
  modernSince: string | null;
  /** How many days after release a card's prices count as early pricing. */
  earlyDays: number;
  /** Sets in the game, and how many the modern rule takes in (counted server-side). */
  setCount: number;
  modernSetCount: number;
}

/**
 * One user's preferences, from GET /api/settings. Saved on the server rather
 * than in the browser, so they survive cleared site data and follow the user.
 */
export interface UserSettings {
  /** The launch-pricing boxes on Movers, Sets and card charts. */
  includePreRelease: boolean;
  includeEarly: boolean;
}

/** Body of PUT /api/games/{id}/rules. modernSince null means every set is modern. */
export interface GameRules {
  modernSince: string | null;
  earlyDays: number;
}

/**
 * Which part of a card's life a price comes from: before release, or its
 * first weeks while it settles. A settled price has no phase (null). Decided
 * server-side by price_phase(); never worked out from dates here.
 */
export type PricePhase = 'pre_release' | 'early';

/**
 * One search hit. This is a product, not a (product, variant) pair: a card
 * that exists as Normal / Holofoil / Reverse Holofoil is one result with
 * variantCount = 3, since those trade at different prices and there is no
 * single price worth showing. Call variants() for the real ones.
 *
 * cardNumber and rarity are part of the card's identity. A set often
 * reprints one card at several rarities across a wide price range (SV03
 * Obsidian Flames has four "Charizard ex" from $5.65 to $108.58). Both are
 * null for sealed product, which has neither.
 *
 * There is no `imageUrl` field here even though the API returns one. Those
 * URLs point at tcgplayer-cdn.tcgplayer.com, so rendering them would make
 * the browser fetch from TCGplayer on every row, and this project sends no
 * traffic there at all (CLAUDE.md, "Hard constraints"). Leaving the field
 * off means it can't be bound to an <img> by accident. Don't add it back.
 */
export interface ItemSearchResult {
  productId: number;
  name: string;
  cardNumber: string | null;
  rarity: string | null;
  /**
   * Printing qualifier pulled out of the name by variant_label(), such as
   * "Cracked Ice Holo", "Poke Ball Pattern" or "2016 World Championship".
   * Null for an ordinary printing.
   *
   * Like cardNumber it is part of the card's identity, since a variant is
   * a separate product at a separate price. Whether its picture is really
   * this printing's is artBorrowed's job, not this label's.
   */
  variantLabel: string | null;
  setName: string;
  game: string;
  isSealed: boolean;
  variantCount: number;
  latestDate: string | null;
  /**
   * Link to the product page. An href makes no request on its own, so
   * nothing is fetched unless the user clicks it, which is them browsing
   * TCGplayer themselves as they would anyway.
   */
  url: string | null;
  /**
   * True when our picture is provably another printing's (the image source
   * has no photo of this one), from card_image_provenance(). The row says so.
   */
  artBorrowed: boolean;
  /** The product whose picture is shown instead, when one is listed. */
  artOwnerName: string | null;
}

/** One variant of a product, from GET /api/items/{id}/variants. */
export interface ItemVariant {
  subTypeName: string;
  firstDate: string;
  latestDate: string;
  /** Raw market price. Null means none was recorded that day. */
  latestMarket: number | null;
  allTimeHigh: number | null;
}

/**
 * One day of price history. There is no highPrice field on purpose, since
 * sellers "price park" (list absurdly high so nothing sells) and the API
 * never returns it. Chart marketPrice alone: lowPrice and midPrice are
 * listing prices, and a day with no sale is skipped rather than filled in
 * from them (owner, 2026-10-29).
 */
export interface PricePoint {
  snapshotDate: string;
  marketPrice: number | null;
  lowPrice: number | null;
  midPrice: number | null;
  directLowPrice: number | null;
  phase: PricePhase | null;
}

/** A set within a game, for the movers set filter. */
export interface GameSet {
  groupId: number;
  name: string;
  publishedOn: string | null;
  productCount: number;
  /** Counts as modern for its game (server-side rule, modern_group_ids()). */
  modern: boolean;
}

/**
 * One row of the movers board.
 *
 * Rows are per (product, variant), unlike ItemSearchResult which is per
 * product. A mover is one specific market's move, and Holofoil / Reverse
 * Holofoil of the same card move independently.
 *
 * thenPrice and nowPrice are the median of the smoothing window at each
 * end, not single-day prices and not an average. Thinly-traded cards lurch
 * on single sales, so a raw two-point comparison puts artifacts at the top
 * of the board. An average was worse than no smoothing in one respect: it
 * leaked prices across a step, reporting a real $41.10 -> $123.32 move as
 * +80% instead of +200%. A median removes one-day spikes and leaves genuine
 * steps at full size. See item_movers().
 */
export interface Mover {
  productId: number;
  name: string;
  cardNumber: string | null;
  rarity: string | null;
  setName: string;
  game: string;
  subTypeName: string;
  isSealed: boolean;
  thenPrice: number;
  nowPrice: number;
  pctChange: number;
  url: string | null;
}

/**
 * One set's current value and windowed return, from
 * GET /api/sets/overview.
 *
 * Singles only. Sealed product never contributes to a set aggregate (see
 * CLAUDE.md), though sealed items stay individually trackable via search.
 *
 * There are two count/total pairs and they are not redundant.
 * nPriced/totalValue cover every single priced on endDate, while
 * nCommon/totalStart/totalEnd cover only rows priced on both dates, which
 * is the basket pctReturn is computed over. They diverge when a card was
 * priced today but not at the window's start, and the UI says so rather
 * than showing two numbers that look like they should match and don't.
 *
 * The top* fields exist because a set-wide percentage is often one card.
 * Measured 2026-08-18, among Pokemon sets with a 2% or larger 30-day move,
 * the single biggest contributing row is routinely 50-87% of all movement
 * in the set. Show them wherever pctReturn is shown.
 *
 * topShare is a share of gross movement (the sum of absolute deltas), so it
 * stays meaningful for sets whose gains and losses nearly cancel.
 */
export interface SetOverview {
  groupId: number;
  setName: string;
  game: string;
  publishedOn: string | null;
  nPriced: number;
  totalValue: number | null;
  nCommon: number;
  totalStart: number | null;
  totalEnd: number | null;
  /** Null, never 0, when the starting basket was 0 or less. */
  pctReturn: number | null;
  topProductId: number | null;
  topName: string | null;
  topSubType: string | null;
  topDelta: number | null;
  topShare: number | null;
  /** An href fetches nothing until the user clicks it. */
  topUrl: string | null;
  /**
   * Readings replaced by the median of their two neighbours because the
   * price swung hard and came straight back, which is an upstream error
   * rather than a move. Usually 0, and that is expected: the effect is rare
   * but heavy-tailed. When it does fire it can be decisive (on 2026-08-04 a
   * single substitution moved EX Dragon Frontiers from -20.91% to +8.43%),
   * so a row with nDespiked > 0 needs to say so in the UI.
   */
  nDespiked: number;
  /** Total absolute adjustment those substitutions made, in dollars. */
  despikeUsd: number | null;
  startDate: string;
  endDate: string;
  /** Counts as modern for its game (server-side rule, modern_group_ids()). */
  modern: boolean;
  /**
   * When the set itself was still new at the window start: its release plus
   * its game's early days. With launch pricing left out, such a set usually
   * has no change yet, and this is why. Null for settled or undated sets.
   */
  settlesOn: string | null;
}

/**
 * Whose artwork a card's thumbnail actually shows, from
 * GET /api/items/{id}/image/provenance. A 404 means we hold no image at
 * all, which callers treat as "nothing to say" rather than an error.
 *
 * One catalog card is one picture, so a printing with a stamp or a foil
 * treatment has no photo of its own and matches the base printing's
 * instead. Only `base_art` is worth showing the user, since that is the one
 * case where the picture on screen is provably not this card. `unknown`
 * stays silent, because an unclassified qualifier is an open question
 * rather than a warning.
 */
export interface ImageProvenance {
  productId: number;
  name: string;
  sourceId: string;
  /** The word dropped to find a picture, i.e. what this printing has that the photo lacks. */
  strippedQualifier: string | null;
  /** 'treatment', 'identity', or null when not yet classified. */
  qualifierClass: string | null;
  claimants: number;
  provenance: 'own_art' | 'base_art' | 'unknown';
  /** The printing the picture belongs to. Null unless borrowed. */
  ownerProductId: number | null;
  ownerName: string | null;
}

/** One tracked game's data currency, from GET /api/health. */
export interface GameFreshness {
  categoryId: number;
  game: string;
  newestSnapshot: string | null;
  daysStale: number;
}

/**
 * From GET /api/health. Health here means "is price data still arriving",
 * not "is the server process up" - a liveness check that answers ok while
 * the history quietly stops is the exact failure that went unnoticed for
 * five days.
 */
export interface Health {
  status: 'ok' | 'stale';
  staleAfterDays: number;
  games: GameFreshness[];
}

/**
 * The minimum a line chart needs: a labelled point that may be missing.
 *
 * Deliberately not PricePoint. A chart needs a date and a value; PricePoint
 * carries market, low, mid and direct-low, and a chart that took it would
 * have to pick one inside itself. The caller picks (market only), so the
 * chart can't quietly start drawing a listing price.
 */
export interface ChartDatum {
  date: string;
  value: number | null;
  /** Price history only: drawn over a shaded band so launch pricing reads as such. */
  phase?: PricePhase | null;
}
