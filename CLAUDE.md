# CardTracker

A TCG price-history app. It pulls daily prices from a third-party mirror
into Postgres, append-only, to build a history it controls: what's up,
what's down, what's dead stock, what a set is worth. Built for Pokemon
(category 3) and One Piece (68); which games are tracked is data in
`tracked_categories`, not code (a fresh database tracks Pokemon).

Some things are sized for one local user (connection per request,
localhost-only CORS, no sign-in, `LOCAL_USER`). They are right for a local
app and are to be revisited together if it is ever hosted.

**This file is rules and traps.** Each rule exists because something went
wrong without it; the comments next to the code say what.

## Hard constraints — do not violate, do not "improve"

People who use a tool like this often sell on TCGplayer and eBay. A price
tool that scrapes or automates a marketplace puts their seller accounts at
risk, so the app never touches either.

**Outbound traffic goes only to these hosts:**

| Host | For |
|---|---|
| `tcgcsv.com` | all price and catalog data |
| `api.pokemontcg.io` | Pokemon card image lookup |
| `images.pokemontcg.io` | the image files themselves |

Any other host (a marketplace, a CDN, an image API, anything) needs explicit
permission from the maintainer, **asked before any code is written or any
request made**. Do not reason from "this request seems harmless" to "so it's
allowed". `SourceGuardsTest` fails the build on an unlisted host.

- **Never contact eBay.** No API calls, page fetches, hotlinked images,
  scraping or automation, not even "just to check".
- **Never scrape tcgplayer.com.** No HTML fetches, headless browser or
  "public search API", not for one card, not as a fallback, not behind a flag.
  If a task seems to need TCGplayer directly, stop and ask.
- **Never automate a login** to TCGplayer or eBay. Anything a user owns is
  entered by hand. What stays closed is any path where the app authenticates
  as them.
- **No images from any TCGplayer host, in any form.** A CDN subdomain is
  still TCGplayer. Not lazy-loaded, not on click, not proxied through our own
  API. This shipped once as search thumbnails and was reverted.
  `ItemSearchResult` in `web/src/app/api.models.ts` omits `imageUrl` so it
  can't be bound to an `<img>`. Outbound product *links* are fine and a core
  feature: an `href` fetches nothing until the user clicks it.
- If asked to add scraping, say what it risks before writing it.

## tcgcsv and pokemontcg.io etiquette

tcgcsv is a free, no-SLA mirror run by one person. Its published limits
([docs](https://tcgcsv.com/docs), [FAQ](https://tcgcsv.com/faq)):

- **More than 10,000 requests in 24h may be banned.** `Throttle.MAX_REQUESTS_PER_RUN`
  (9000) is a whole-process circuit breaker, shared across games. If a sync
  needs more, it is doing redundant work: fix that, don't raise the ceiling.
  A Pokemon-sized game is ~436 requests a day.
- **At least 100ms between requests.** `Throttle.politeDefault()` uses 750ms.
- **A custom User-Agent** (`TcgCsvPriceSource.USER_AGENT`). Bump its version
  when request behaviour changes.
- **Server-side only**; the browser never calls it.
- **Data refreshes once a day, around 20:00 UTC, and the live endpoints
  serve only the current day.** tcgcsv withdrew its daily archive in
  September 2026, so a day nobody pulls before the next refresh is gone for
  good. `BackfillArchive` is dormant; re-enabling it if the archive returns
  is a decision to make then.

pokemontcg.io is a free community project and gets the same etiquette: its
catalog is pulled once and matched offline, each image is fetched once and
served from our disk, and the browser never contacts it. That permission is
for Pokemon images only; One Piece has no image source yet.

## Data invariants

- **Prices key on `(product_id, sub_type_name, snapshot_date)`.** Normal,
  Holofoil and Reverse Holofoil are different markets. Never collapse or
  aggregate across sub-types, and never put one "price" on a product.
- **`price_snapshots` is append-only.** Inserts use `ON CONFLICT DO NOTHING`,
  never `DO UPDATE`; nothing updates or deletes it (`SourceGuardsTest`
  enforces this). A re-run must be safe and a bad run must never overwrite a
  good observation. "It looked wrong" is not a reason to delete a row.
- **Never compute on `high_price`** (sellers price-park). Store it, ignore
  it; no API response carries it (`PricePoint` has no field for it). Every
  number and chart uses `market_price` alone. Mid and low are listing
  prices: filling a day with no sale from them put parked listings on charts
  and all-time highs, so a missing day is skipped, never filled in.
- **The live pull is dated by the mirror, not a clock**:
  `PriceSource.dataAsOf()`, from tcgcsv's `last-updated.txt`. The live
  endpoints carry no date, and dating from our own clock once wrote a
  duplicated "phantom" day. Don't "simplify" this to any `LocalDate.now()`.
  A parse failure refuses the run; run it again later.
- **"Current" means the newest day the database holds**, `MAX(snapshot_date)`,
  never `CURRENT_DATE`.
- **Where a clock is wanted, use `LocalDate.now(ZoneOffset.UTC)`**, never a
  bare `LocalDate.now()` (guarded).
- **All-time high is computed on demand, never stored**, so a backfill that
  turns up an older, higher price can't leave it stale.
- **Set aggregates are singles only.** `set_return()`, `set_value()` and
  `game_set_overview()` hardcode `is_sealed = FALSE`; it is not a parameter.
- **Smoothing is a median, never a mean**, at odd window sizes.
- **One-day errors are despiked at read time** (a swing that reverts the
  next day is an error). Keyed on persistence, not magnitude; a $10 gate;
  `price_snapshots` never written; a visible toggle that marks every
  affected row. Widening to median-5 was tried and rejected (it erased a real
  two-day move); don't reopen without new evidence.
- **A set's percentage is shown with its biggest single contributor**, as a
  share of gross movement.
- **Percent change is null on a zero or negative start**, never 0.
- **`product_id` rides on every row**, so every row can link to TCGplayer.
- **Code cards are excluded at ingest**, Pokemon only, and recorded in
  `excluded_products`, which is where any future exclusion goes.
  `is_sealed` is set at ingest: a single carries a `Number` in extendedData.

## Architecture

| Where | What |
|---|---|
| `App` | the daily pull (`java -jar target/cardtracker-0.1.0.jar`) |
| `com.cardtracker.api.ApiServer` | Javalin API on :7070 |
| `ImportCardImages` | fetches Pokemon card images (resumable) |
| `web/` | Angular 22: Search, Movers, Sets, Disclosures, Settings |
| `sql/schema.sql`, `sql/migrations/` | the schema (see below) |
| `sql/functions.sql` | every query the API runs; idempotent, re-applied by `migrate.sh` |
| `sql/checks/` | invariants checked against the live data |

- **Data sources sit behind `PriceSource`**, so tcgcsv can be replaced (it
  has no per-condition prices and no uptime promise). Provider details stay
  inside the implementations.
- **Query logic lives in `sql/functions.sql`.** `QueryRepository` methods are
  thin calls into it, so every number can be checked in psql and the API
  can't drift from hand-written analysis. New endpoint, new SQL function.
- **A bulk function that restates per-item ones is cross-checked**, not
  assumed equal: `sql/checks/set_overview_matches_primitives.sql` compares
  `game_set_overview()` with `set_value()`/`set_return()`. Re-run it after
  editing either.
- **Window bounds go in scalar `DATE` variables, never a CTE sub-select**
  (`item_movers()`, `game_set_overview()`). From a sub-select the planner
  misses `idx_snapshots_date`; measured ~5x slower and ~6GB spilled.
- **`schema.sql` runs once, on an empty database.** Every schema change is
  also a numbered file in `sql/migrations/`, applied with `./sql/migrate.sh`.
  Write both.
- **`PriceRepository` only writes prices; `QueryRepository` only reads.**
- **The API is read-only over prices.** It writes only the user's own data:
  preferences, and each game's rules.
- **One database connection per request** (`withConn()` in `ApiServer`),
  because a JDBC connection isn't thread-safe. Postgres allows 100; at 300
  concurrent requests some fail. A pool (HikariCP, a new dependency, so ask
  first) goes in `withConn()` if this is hosted, together with CORS.
- **CORS allows only `localhost:4200`.**

### Behaviour that is easy to "simplify" into a bug

- **Search** returns products, not variants, with `card_number` and `rarity`
  from extendedData (one set reprints a card at several rarities). It
  matches on the name only. Paging is sound only because `item_search()`
  orders down to `product_id`; `onlyOnePlaceReplacesTheSearchResultList`
  guards the "Load more" race.
- **A variant label shows only when it adds to the name**
  (`variantLabelAddsInfo()`). `is_variant_word()` has a Java twin,
  `ImportCardImages.isVariantWord()`: change both or neither, then run
  `sql/checks/is_variant_word_matches_its_java_port.sql`.
- **Thumbnails cost no database connection**: files are
  `<category_id>/<product_id>.jpg` and `ImageFiles` finds them on disk.
  Every `<img>` in the front end loads from our API's `imageUrl()` (guarded);
  list rows use the `card-thumb` component.
- **Movers opens on biggest gains over 7 days**, across every tracked game;
  the defaults live in `item_movers()`, so psql agrees.
- **Freshness is read from the data**: `data_freshness()` is the one
  definition, used by `/api/health`, the stale banner and
  `sql/checks/data_freshness.sql`. Stale is 2 or more days old
  (`DataFreshness.STALE_AFTER_DAYS`), the same for the banner and the check.

### Multi-game, modern, and what trends leave out

- **A quirk found in one game is not assumed in another.** Code-card
  exclusion checks `categoryId == TcgCsvUrls.POKEMON`; a new game's quirk
  gets its own condition. `ingest_runs.source` is per game (`tcgcsv-3`), so
  one game failing never hides another.
- **"Modern" is per game**: `tracked_categories.modern_since`, applied only
  through `modern_group_ids()`. Pokemon starts at Sword & Shield
  (2019-11-15). NULL means every set; undated sets are never modern. Nothing
  else compares dates to decide it, including the front end.
- **Launch pricing is left out of trends by default** ("mostly noise").
  Prices before a card's release, and in its first `early_days` (56, per
  game) after it, are dropped from Movers and a set's change, never from
  values. `card_release()`, `price_phase()` and `launch_noise()` are the
  only definitions. `card_first_priced` is derived, kept by a trigger that
  only ever moves a date earlier and never writes `price_snapshots`. Why,
  with numbers: migration 004. Both boxes travel together as
  `LaunchPricing` in Java and `withLaunchPricing()` in the front end, each
  shown on Movers, Sets and Settings as one saved preference.
- **The Disclosures screen is a promise about the code.** It tells users what
  every number includes and how it is worked out. Change
  `web/src/app/disclosures.html` in the same commit as any rule it describes.
  Rules that are data (modern cutoffs, early weeks, the stale threshold, the
  user's boxes) are read live there; fixed thresholds are written in both
  places, and each names the other.
- **Settings are stored in two places on purpose.** Preferences are per user
  in `user_settings`, updated in place and loaded before the app draws
  (`UserPrefs`). Game rules are per game in `tracked_categories`, shared,
  because they define what the data means.

### Card images (Pokemon, from pokemontcg.io)

- **Run `ImportCardImages --dry-run` before changing any matching rule.** A
  `no_match` is never re-queried, so a wrong rule writes a permanent miss.
  `--group <id>` scopes a real run to one set.
- **Before diagnosing misses, sort them by match rate per set.** A set at 2%
  is absent upstream; a set at 94% with a residue is a rule defect.
  "Upstream doesn't have it" has been wrong three times, and each time the
  cause was normalization.
- **Coverage is about 95% and the rest isn't worth chasing**: Trainer Kits
  and Battle Academy 2024 aren't on pokemontcg.io, and 61 `failed` rows
  (mostly McDonald's promos) are listed there without images. Don't retry
  them; `--skip-failed` exists for that.
- **`δ`, `α`, `β` and `γ` are deliberately not folded** by `fold()`: they are
  card identity.
- **Whose artwork a card shows is computed, never stored**
  (`card_image_provenance()`), and only `base_art` is shown to the user: a
  band on the card's artwork and a chip on its search row. Search asks once
  per page, through `item_products()`. Qualifier classes in
  `image_qualifier_class` are domain calls: explicit word lists, stored
  already normalized; an unclassified qualifier falls back to the structural
  test. Invariants: `sql/checks/card_image_provenance_invariants.sql`.

## Running it

Java 21, Maven, Node, and Postgres 16 in Docker (container
`tcg-workspace-db`, on localhost:5433). Setup is in README. Everything runs
the jar, so rebuild with `mvn package` after a source change (see the first
trap below).

```
docker compose up -d                                                  # Postgres
java -cp target/cardtracker-0.1.0.jar com.cardtracker.api.ApiServer    # API :7070
cd web && npx ng serve                                                 # UI :4200
```

The pull is not scheduled here. `App` is idempotent per day, so run it by
hand or from any scheduler, once a day after the mirror's ~20:00 UTC refresh.

**Tests:**

```
mvn test                    # Java (also run by mvn package)
cd web && npm test           # front end (vitest)
./sql/checks/run-all.sh     # invariants on the live data; needs Postgres up
```

Tests needing Postgres skip, not fail, without it. Four rules:
- **No test contacts tcgcsv.com or pokemontcg.io.**
- **No test touches the working database.** `TestDatabase` builds a
  throwaway one per class from the real `schema.sql` and `functions.sql`.
- **A test must call the real decision, not reimplement it.** That is why
  `ImageDir.resolve`, `parseLastUpdated` and `ApiServer.createApp` take
  their inputs as parameters.
- **`SourceGuardsTest` enforces this file's hard rules**, and that every
  backticked identifier here still exists. A name cited as history goes in
  `DELETED_AND_CITED_AS_HISTORY`.

## Traps that have actually bitten

- **Never `mvn package` while anything runs from the jar**, the API server
  included. It overwrites the jar in place and Java loads classes lazily, so
  a running process dies partway (`ClassNotFoundException`). Check with
  `pgrep -fl 'java .*cardtracker'`, and restart the API after a build.
- **`CREATE OR REPLACE FUNCTION` doesn't replace across signatures.** It adds
  an overload, and calls become ambiguous. Changing arguments or the return
  type needs `DROP FUNCTION IF EXISTS name(old, arg, types);` first. Inspect
  with `SELECT oid::regprocedure FROM pg_proc WHERE proname = '...';`
- **Run tools from the project root.** `ImageDir.resolve()` refuses to start
  elsewhere; `CARDTRACKER_IMAGE_DIR` overrides it.
- **Anchor `.gitignore` directories** (`/images/`). A bare `images/` once
  excluded the Java package of the same name.
- **Don't pipe a long Java process through `head`.** It ignores SIGPIPE and
  looks exactly like a hang.
- **pokemontcg.io is flaky**: 5-11 of 174 sets fail per catalog attempt, deep
  pagination breaks past ~12k rows, and image downloads fail in bursts that
  recover alone. The catalog is cached in `images/.catalog-cache` (7 days).
  Check `card_images` progress in the database before killing a quiet import.

## Commit messages

Tight bodies scoped to this change: what changed and why, enough for
`git log` and a bisect. Keep concrete numbers ("810,451 rows deleted");
those are what a diff can't show.

## Open questions

- **155 unclassified image qualifiers**, one INSERT each. `shiny` is the one
  worth a decision: different artwork in the Platinum `SH` and Call of
  Legends `SL` subsets (identity), a foil treatment elsewhere. That split
  can't be derived.
- **One Piece images**: no permitted source yet.
- **Hosting**: sign-in, a connection pool and CORS, decided together.

Settled, so don't re-investigate: tcgcsv's field names were checked
against a live response (a real price-parked row, `marketPrice` ~70 against
`highPrice` 9999, is what justifies the `high_price` rule), and `product_id`
is stable across time, so long-range trends are safe.
