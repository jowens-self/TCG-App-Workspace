# CardTracker

A TCG price-history app. It pulls daily price data from the TCGCSV mirror
into Postgres, append-only, so you build a price history you own, and puts
an API and an Angular front end over it: card search, the week's biggest
movers, and what every set is worth.

It was built against Pokemon and One Piece. Which games are tracked is
controlled by the `tracked_categories` table, not hardcoded: a fresh
database tracks Pokemon, and adding a game is one INSERT. See "Tracking
another game" below.

Released under the [MIT License](LICENSE).

## What it deliberately does not do

- **No requests to tcgplayer.com at all.** Not the site, not its image
  CDN, not from the server, not from the browser. Card images come from
  pokemontcg.io instead (see "Card images"), cached and served locally.
  Outbound *links* are fine: an `href` fetches nothing until you click it
  yourself.
- **No requests to eBay, ever.** Same rule, same reason: people who use a
  tool like this often have seller accounts there.
- **No new outbound hosts without explicit permission.** Three are allowed:
  `tcgcsv.com` for prices and the catalog, and `api.pokemontcg.io` and
  `images.pokemontcg.io` for Pokemon card images. Anything else gets asked
  about first, before any code. `SourceGuardsTest` fails the build on an
  unlisted host.
- **No automated login, ever.** Nothing here signs into a marketplace as you.
- **Local-only.** One person, no auth, no hosting. That's where it is today,
  not a rule about where it can go.

Traffic goes only to those hosts, a third-party price mirror and a
community card-image project, each on its own infrastructure, and only from
the server, never the browser.

## Requirements

Java 21, Maven, Node 20+, and Docker (for Postgres 16).

## Setup

```bash
docker compose up -d          # Postgres on localhost:5433, schema loaded on first boot
mvn package                   # builds target/cardtracker-0.1.0.jar and runs the Java tests
java -jar target/cardtracker-0.1.0.jar    # one day's pull from tcgcsv
```

Override the defaults with `CARDTRACKER_DB_URL`, `CARDTRACKER_DB_USER`,
`CARDTRACKER_DB_PASS` if you want something other than the local dev
database in `docker-compose.yml`.

## Pulling prices

`App` pulls the current day's prices for every tracked game. tcgcsv
refreshes once a day, around 20:00 UTC, and its live endpoints only serve
the current day, so **a day nobody pulls is lost**: tcgcsv took its archive
down in September 2026 (see "Backfill" below).

Run it once a day after the refresh, by hand or from any scheduler (cron,
launchd, a CI job). It is idempotent per day: a game that already holds the
mirror's day is skipped without fetching anything.

**The pull runs the built jar.** Rebuild after any code change, but not with
`mvn package` while the API server or a pull is using the jar: it overwrites
the jar in place (see CLAUDE.md).

## Tests

```bash
mvn test                    # Java
cd web && npm test          # front end (vitest)
./sql/checks/run-all.sh     # invariants on the live data; needs Postgres up
```

The SQL and API tests build a throwaway database per test class from the
real `schema.sql` and `functions.sql`, on the Postgres from
`docker-compose.yml` (override with `CARDTRACKER_TEST_DB_URL`). Without
Postgres they skip rather than fail. No test contacts tcgcsv.com or
pokemontcg.io.

## Design decisions worth not undoing

**Prices key on (product_id, sub_type_name, snapshot_date).** Pokemon cards
exist as Normal, Holofoil and Reverse Holofoil of the same product, trading at
genuinely different prices. Collapse them and every percentage you compute is
an average of unrelated markets.

**`high_price` is stored but never computed on.** Sellers "price park": list a
card at thousands of dollars so it doesn't sell, apparently to avoid re-picking
set/printing/condition when relisting. The top of the range is noise.

**Inserts are `ON CONFLICT DO NOTHING`.** Re-running a pull is safe, and a bad
run can't overwrite a good observation. History only accumulates.

**`product_id` is carried on every row**, so a link back to the card's page is
always constructible.

**Price source sits behind an interface.** TCGCSV is one person's free project
with no SLA and no per-condition pricing. If it disappears or you outgrow it,
you write one new class.

## Query functions

`sql/functions.sql` (loaded automatically alongside `schema.sql`) holds every
query the app runs, so any number on screen can be reproduced in psql. The
four below are the analytical building blocks, each verified against
hand-computed numbers before being trusted.

`item_return`/`item_all_time_high` work on any individual product, card
or sealed, since the math is the same either way, and deliberately return
`name`/`is_sealed` alongside the numbers so the category is always visible
in the result, not something you have to already know.

```sql
-- Return for one item between two exact dates.
SELECT * FROM item_return(534919, 'Holofoil', '2024-02-08', '2026-08-13');
-- name              | is_sealed | start_price | end_price | pct_return
-- Mew ex - 232/091  | f         |      137.00 |   1013.78 |     639.99

-- All-time high for one item: its highest market price.
SELECT * FROM item_all_time_high(534919, 'Holofoil');
-- name              | is_sealed | all_time_high
-- Mew ex - 232/091  | f         |       1089.18

-- Portfolio-style set return: singles only, always.
SELECT * FROM set_return(23353, '2024-02-08', '2026-08-13');
-- n_variants | total_start | total_end | pct_return
--        325 |     1176.92 |   2790.15 |     137.07

-- Current (or as-of) total value of a set. Singles only, same rule.
-- "Current" is the newest day the database holds, not today. The day's pull
-- lands after tcgcsv's refresh, so the newest snapshot is often yesterday
-- with nothing wrong.
SELECT * FROM set_value(23353);
-- n_variants | total_value
--        326 |     2748.94   (as of 2026-08-21, read 2026-08-22)
```

Sealed product is still fully trackable, just as an individual item, not
folded into a set total: `SELECT * FROM item_return(528040, 'Normal',
'2024-02-08', '2026-08-13')` (the Paldean Fates Elite Trainer Box) works
the same as any single card.

## Read API

A JSON API over the query functions above, so a front end never talks to
Postgres (or to tcgcsv) directly.

Read-only over PRICE data, and that part is not negotiable: nothing here
writes `price_snapshots`, and ingestion stays in `App`/`BackfillArchive`
where the append-only guarantees live. What it does write is your own data:
your preferences, and each game's rules.

`/api/health` reports whether price data is still ARRIVING, per game, rather
than whether the process is up. A liveness check that answers "ok" while the
history has quietly stopped is the exact failure that once went unnoticed
for five days.

```bash
docker compose up -d
mvn package
java -cp target/cardtracker-0.1.0.jar com.cardtracker.api.ApiServer
# http://localhost:7070  (override with CARDTRACKER_API_PORT)
```

| Endpoint | Purpose |
|---|---|
| `GET /api/health` | Whether price data is still arriving, per game - not just whether the process is up |
| `GET /api/games` | Tracked games, with each one's rules and how many sets count as modern |
| `GET /api/items/search?q=` | Card / product search by name |
| `GET /api/items/{id}` | One product's identity |
| `GET /api/items/{id}/variants` | Its variants, with current price + all-time high |
| `GET /api/items/{id}/history?subType=` | Price time series for one variant |
| `GET /api/items/{id}/image` | Cached card thumbnail (404 = no image) |
| `GET /api/items/{id}/image/provenance` | Whose artwork the thumbnail shows (404 = no image) |
| `GET /api/movers` | Biggest price moves over a window |
| `GET /api/games/{id}/sets` | Sets in a game, for the movers filter |
| `GET /api/sets/overview` | Every set's value and windowed return |
| `GET /api/settings` | Your preferences (the defaults until you save any) |
| `PUT /api/settings` | Save them; every field required |
| `PUT /api/games/{id}/rules` | A game's modern cutoff and early-pricing days; answers with the new set counts |

Search takes `q` (required), plus optional `game` (category id),
`sealed=false` to hide sealed product, `limit` (default 25, capped at 200)
and `offset`. It is a case-insensitive name match, ranked exact name first,
then prefix, then anywhere in the name, with shorter names first among
ties.

```bash
curl "localhost:7070/api/items/search?q=charizard&sealed=false&limit=3"
# [{"productId":42382,"name":"Charizard","cardNumber":"004/102",
#   "rarity":"Holo Rare","setName":"Base Set","game":"Pokemon",
#   "isSealed":false,"variantCount":1,"latestDate":"2026-08-14",
#   "url":"..."}, ...]
```

**`cardNumber` and `rarity` are identity, not decoration.** A set routinely
reprints the same card at several rarities, and those copies are different
markets. All four of these are "Charizard ex" in SV03: Obsidian Flames:

| # | Rarity | Market |
|---|---|---|
| 125/197 | Double Rare | $5.65 |
| 215/197 | Ultra Rare | $22.35 |
| 223/197 | Special Illustration Rare | $108.58 |
| 228/197 | Hyper Rare | $37.37 |

Both fields come from `extendedData`, not from the name, since TCGplayer only
sometimes appends the number to the name (5,276 of 34,435 singles), so
relying on the name would leave Base Set Charizard with nothing to identify
it. Null for sealed product, which has neither.

**Search returns products, not variants.** One row per product with a
`variantCount`, never one row per (product, sub-type). A card existing as
Normal / Holofoil / Reverse Holofoil is one hit, because there is no
single honest "price" to put on it (see "Design decisions worth not
undoing" above). Ask `/variants` for the real per-variant numbers:

```bash
curl "localhost:7070/api/items/83514/variants"
# [{"subTypeName":"Holofoil","firstDate":"2024-02-08","latestDate":"2026-08-14",
#   "latestMarket":64.72,"allTimeHigh":68.29}, ...]
```

`subType` is required on `/history` and has no default, since picking one for
the caller would mean guessing which market they meant. `/variants` is
how you find the valid values.

```bash
curl "localhost:7070/api/items/534919/history?subType=Holofoil&from=2026-08-11"
# [{"snapshotDate":"2026-08-11","marketPrice":1013.78,"lowPrice":829.34,
#   "midPrice":1030.0,"directLowPrice":829.34,"phase":null}, ...]
```

`high_price` is deliberately absent from every API response. See the
price-parking note above. Charts use `marketPrice` alone: `lowPrice` and
`midPrice` are listing prices, returned to read and never charted.

Each point also carries a `phase`: `"pre_release"`, `"early"` or `null`.
This is **launch pricing**, and it's mostly noise. Measured across 14 Pokemon
and 15 One Piece main sets released since spring 2024:
- **Before release:** preorder prices sit at about 3x where a card settles.
- **Release day:** still 1.5–2x.
- **After release:** Pokemon loses about a quarter in week one and 4–7% a week
  for the rest of the month.
- **By week 8:** both games are flat.

`"early"` is a card's first `earlyDays` (per game, 56 by default) after
release. A card's release is its set's date. The exception is a card first
priced more than 30 days after that, like a new promo filed under a
long-running promo group; its release is then its own first price. The rules
live in `card_release()` and `price_phase()`. Each card's first price is kept
in `card_first_priced` by a trigger, because looking it up per request cost
4.3s.

CORS is scoped to `http://localhost:4200` (the Angular dev server). The
API binds locally and is meant to stay there; if it ever moves, revisit
both that rule and the connection-per-request in `ApiServer.withRepo`.

## Front end (`web/`)

Angular 22 app over the API. Five screens: card / product search (find an
item, see its variants and their prices, chart one's history), Movers, Sets,
Disclosures, and Settings.

**Disclosures** tells a user what every number includes, what it leaves out
and how it is worked out. It reads the rules that are data (modern cutoffs,
early-pricing weeks, the staleness threshold, the user's own boxes) live, so
those can't drift; the fixed thresholds are written there, and it changes in
the same commit as any rule it describes.

A card opens in the same view everywhere: Search's side panel, and on Movers
right under the row you clicked, with the move that put it on the board.

**Settings** holds two kinds of thing, saved differently on purpose.
- **Your preferences** save the moment they change and live on the server
  per user (`user_settings`), so they survive cleared browser data. Today
  they are the launch-pricing boxes, which are the same settings as the
  boxes on Movers and Sets.
- **Game rules** take an explicit Save, because they change what every
  trend screen counts:
  - where "modern" starts, picked from the game's sets by release date;
  - how many weeks early pricing lasts.
  The answer says how many sets the new cutoff takes in, counted by the
  server's own rule.

A banner appears on every screen if price data stops arriving.

```bash
docker compose up -d                                              # Postgres
java -cp target/cardtracker-0.1.0.jar com.cardtracker.api.ApiServer  # API :7070
cd web && npm install && npx ng serve                             # UI :4200
```

All three are needed: the UI talks only to the API, and the API is the
only thing that talks to Postgres.

In IntelliJ, the **CardTracker** run configuration (in `.run/`) starts the
API and the UI together; the stop button stops both. Start Postgres first
with `docker compose up -d`. The API runs from IntelliJ's compiled classes,
so it is always the current code and never holds the jar.

Structure, all standalone components with signals:

| File | Role |
|---|---|
| `api.models.ts` | TypeScript mirrors of the Java records (no `imageUrl`, see below) |
| `api.ts` | The only file that knows a server exists |
| `search.ts/.html/.css` | Search screen: query, filters, results, detail |
| `movers.ts/.html/.css` | Movers screen: window/game/set/modern filters, table |
| `sets.ts/.html/.css` | Sets screen: per-set value, return, concentration, modern filter |
| `modern-rule.ts` | The sentence explaining what "Modern only" means for the game on screen |
| `launch-pricing.ts` | The words for the launch-pricing boxes, and which chart points they keep |
| `price-chart.ts` | Hand-rolled SVG line chart, no charting library |
| `card-detail.ts/.html/.css` | The one card view: picture, variants, chart; used by Search and Movers |
| `settings.ts/.html/.css` | Settings screen: preferences and game rules |
| `disclosures.ts/.html/.css` | Disclosures screen: what each number includes and how it's worked out |
| `user-prefs.ts` | Your preferences as signals, loaded before the app draws, saved on change |
| `modern-cutoff.ts` | A game's release dates as "modern starts with" choices |

Layout is two columns: results left, detail right and sticky, so the
chart stays in view while you scroll the list. The results list scrolls
within its own 70vh box rather than growing the page. Below 800px the
columns stack.

The chart plots `marketPrice` alone and drops days with no market price
rather than plotting them as zero or filling them from listing prices. A
missing observation isn't a price of $0, or a price someone asked for.

**Launch pricing** has two checkboxes: "Include pre-release" and "Include
early pricing". Both start unticked, because both are mostly noise.
- **Where they appear:** on Movers and Sets, and on a Search chart when that
  card has launch pricing.
- **One setting for the whole app,** saved with your settings on the server,
  and also on the Settings screen.
- **Unticked:** that part is left out of the Movers board, of a set's change,
  and of charts.
- **Ticked:** it comes back, drawn over a shaded band so it still reads as
  launch pricing.
- **A card that hasn't settled** is charted anyway, with a note saying
  everything shown is launch pricing, rather than showing an empty chart.

### Movers

`GET /api/movers`. All parameters optional, because the defaults that
define the screen's default view live in `item_movers()` so psql callers
get them too: 7 days, biggest gains, singles only, $1 minimum, 3-day
smoothing, 50 rows.

```bash
curl "localhost:7070/api/movers?days=7&game=3&direction=down"
curl "localhost:7070/api/movers?game=3&set=23353&direction=up"
```

Takes `days`, `game`, `set`, `sealed=true`, `modern=true`, `prerelease=true`,
`early=true`, `minPrice`, `direction` (`up`/`down`), `smoothing`, `limit`.

**Launch pricing is left off the board unless `prerelease=true` /
`early=true` is passed.** A card whose price at the "then" end was a preorder
or still settling is not on the board. On 2026-09-29, 28 of the 50 biggest
modern drops were cards that didn't exist ten weeks earlier, sliding the way
every new set does. Filtered before the top-50 cut, like modern.

**`modern=true` keeps only modern sets**, filtered before the top-50 cut so
the board stays full. Each game's cutoff is `tracked_categories.modern_since`:
Pokemon from 2019-11-15, the start of Sword & Shield (the SWSH promos came
three months before the first set), and every One Piece set. A set with no
release date never counts, since it can't be placed in a block. Moving the
cutoff is one UPDATE; the rule itself is `modern_group_ids()`.
`/api/games` carries each game's `modernSince`, and the set lists
(`/api/games/{id}/sets`, `/api/sets/overview`) mark each set `modern` so the
screens filter on the server's answer instead of repeating the rule.

**Rows are per (product, variant)**, unlike search which is per product.
A mover is one specific market's move, and the sub-types of a card move
independently.

**Prices at each end are the MEDIAN over the smoothing window, not single
days and not a mean.** Thinly-traded cards lurch on single sales: Charizard
Star (84198) sat at $4,000 for weeks, printed $980 for exactly one day,
snapped back, then settled near $990. Point-to-point that reads as -75% one
day and +308% the next.

A median is a correctness choice, not a preference, measured on live data:

| case | mean-3 | median-3 | truth |
|---|---|---|---|
| Charizard Star, one-day artifact | -25.17% | **0.00%** | no move |
| Charizard Star, real sustained drop | -75.10% | **-75.03%** | -75% |
| Lillie 125a/156, real step $41.10→$123.32 | +80.0% | **+200.05%** | +200% |

A mean damps everything including the signal, and worse, it leaks prices
across a step: the +80% above is one day of post-step price contaminating
the "then" window. It also reported Flaaffy (85475) as $34.45, a price
that card has never traded at. A median annihilates single-sample impulses
outright while leaving steps and trends untouched.

Prefer an **odd** `smoothing`: at odd sizes the median is a price the card
was genuinely at on some day, while even sizes interpolate between the two
middle days and can report a price never observed, the exact failure the
mean was replaced for.

A median reacts faster than a mean, so genuine one-day noise on thin cards
shows at full size rather than damped; `minPrice` is what keeps that off
the board.

**`minPrice` defaults to $1** because percent change flatters cheap cards.
Without it the week's top decliner was Shuckle at $0.06 → $0.01 (-83%),
and four of the top twelve were sub-$1 cards moving pennies.

### Sets

`GET /api/sets/overview`. Every set of a game with what it is worth now
and how it moved.

```bash
curl "localhost:7070/api/sets/overview?game=3&days=30"
curl "localhost:7070/api/sets/overview?game=3&days=30&despike=false"   # raw
```

Takes `game` (omit for all tracked games), `days` (default 30), `asOf`,
`despike` (default on), and `prerelease=true` / `early=true`.

**Launch pricing is left out of a set's change by default**, though never
out of its value, since what a set is worth today is real either way.
- **What happens:** a card whose price at the window's start was a preorder
  or still settling is dropped from the comparison.
- **Why:** ME: 30th Celebration read -97.47% over 30 days on a basket of 5
  cards, all of them preorder prices.
- **`settlesOn`** is set when the set itself was still new at the window
  start. It lets the screen say "New · settles 2026-11-11" instead of showing
  a blank.

Backed by `game_set_overview()`, which computes `set_value()` and
`set_return()` set-wide in one pass. There are 302 tracked groups, so
calling the per-set primitives would be 604 round trips. Its numbers are
cross-checked against those primitives rather than assumed equal:

```bash
docker compose exec -T db psql -U cardtracker -d cardtracker \
  -f - < sql/checks/set_overview_matches_primitives.sql
```

Every column must read `t`. Re-run it after editing either side.

**Singles only.** Sealed product never counts toward a set aggregate. The
same hard rule as `set_return()`.

**Value and change are computed over different baskets, on purpose.**
Value is every single priced on the end date. Change compares only cards
priced on *both* dates, so a set gaining or losing cards mid-window can't
masquerade as a price move. The row shows `+N new` when they differ.

**Every set's percentage is shown with the single card that drove it**, and
flagged when that card is over half the set's gross movement. This is not
decoration. Measured 2026-08-18: **44 of 283 sets** have over half their
30-day movement from one row. Base Set (Shadowless) reads **+59.96%**, and
$9,750 of that $10,718 move is one card: Charizard 1st Edition Holofoil,
whose `market_price` toggles between ~$250 and exactly $10,000 for weeks at
a time while low/mid/high sit parked at $100,000.

Smoothing does not help here and was tried first: the regimes last weeks,
so `item_movers()`-style 3-day averaging returns $250 and $10,000
unchanged. Disclosure is the honest response.

Share is measured against **gross** movement (sum of absolute deltas), not
net, because net passes through zero, and ranking by share-of-net gave four-digit
percentages for sets whose gains and losses nearly cancel.

**One-day price errors are corrected, and the row says so.** A reading
whose two neighbours agree with each other while it disagrees with both is
almost certainly an upstream error rather than a move. The test is
*persistence*, not magnitude, and it is replaced with the median of the
three days. Rows where that happened show `⤾ adjusted`; the checkbox turns
it off. Stored prices are never modified.

Median, not mean, and that is the point. On Charizard Star (84198), which
has a real step (970→4000) and a one-day artifact ($980 between $4,000
either side): median-3 removes the artifact entirely and keeps the step at
full size, while mean-3 leaves a phantom −25% *and* invents a +108% move on
the day before by smearing the step across three days.

It is gated at $10 of distortion because the effect is heavy-tailed: 4,700
events over 90 days, of which 15 carry 99.4% of the money. On the default
window today it changes nothing ($0.81 of sub-gate noise), but when a spike
lands on an endpoint it is decisive: as of 2026-08-04 it moved EX Dragon
Frontiers from **−20.91% to +8.43%** on a single substitution.

Two structural limits: the newest date can never be despiked (its next day
hasn't been observed yet), and a 3-day median only catches one-day
impulses. 20 two-day runs were missed over 90 days, including the Base Set
Charizard.

## Card images

Thumbnails come from [pokemontcg.io](https://pokemontcg.io), a free
community API, **never from TCGplayer**. They're downloaded once,
downscaled, and served off our own disk, so the browser never contacts a
third party for them.

```bash
java -cp target/cardtracker-0.1.0.jar com.cardtracker.images.ImportCardImages
# or a single set first:
java -cp target/cardtracker-0.1.0.jar com.cardtracker.images.ImportCardImages --group 604
```

Resumable and safe to re-run: products with an `ok` or `no_match` row are
skipped, `failed` ones are retried. Images land in `images/` (override with
`CARDTRACKER_IMAGE_DIR`), gitignored since they're regenerable.

**About 95% of the Pokemon singles checked have an image**: 26,618 of
27,807 (95.72%). The rest isn't worth chasing:

| Without an image | Cards | Why |
|---|---|---|
| Name not upstream | 275 | pokemontcg.io doesn't list the card |
| Name upstream, number not | 753 | mostly Trainer Kits and Battle Academy 2024 |
| Ambiguous | 100 | several catalog cards fit, and the matcher won't guess |
| `failed` | 61 | listed upstream with no image (mostly McDonald's promos) |
| One Piece | all | no permitted source |

The importer isn't scheduled. Cards added since its last run have never
been checked, so they show the placeholder until it runs again, and the
newest sets can still miss because the upstream catalog lags releases.
Cards with no image get a placeholder, which is a normal state rather than
an error.

Matching is on name + card number + set total, with a name+number fallback
accepted **only when unambiguous**, since a wrong image is worse than none.
~14 KB per thumbnail, so the full catalog is roughly 200 MB rather than the
~2.6 GB the originals would cost.

Their API is flaky: expect transient 500s, and note deep pagination is
broken past ~12,000 rows, which is why the importer fetches per set. If any
set fails, the run records **no** `no_match` rows that pass, because an incomplete
catalog would otherwise permanently mark cards as imageless.

## Example queries

Biggest 30-day movers, sealed product only. "Now" is the newest day the
database holds, never `CURRENT_DATE`: the day's pull lands after tcgcsv's
refresh, so before then today has no rows. A variant with no market price
on either day is skipped, since a missing price isn't a move:

```sql
WITH now_p AS (
  SELECT product_id, sub_type_name, market_price
    FROM price_snapshots
   WHERE snapshot_date = (SELECT MAX(snapshot_date) FROM price_snapshots)),
then_p AS (
  SELECT product_id, sub_type_name, market_price
    FROM price_snapshots
   WHERE snapshot_date = (SELECT MAX(snapshot_date) FROM price_snapshots) - 30)
SELECT p.name, n.sub_type_name, t.market_price AS then_price,
       n.market_price AS now_price,
       ROUND((n.market_price - t.market_price) / t.market_price * 100, 2) AS pct
  FROM now_p n
  JOIN then_p t USING (product_id, sub_type_name)
  JOIN products p USING (product_id)
 WHERE t.market_price > 0 AND n.market_price IS NOT NULL AND p.is_sealed
 ORDER BY pct DESC
 LIMIT 25;
```

For the screen's own numbers (median smoothing, a $1 floor, launch pricing
left out), use `item_movers()` instead.

Did the pull actually run?

```sql
SELECT snapshot_date, status, request_count, rows_written, notes
  FROM ingest_runs ORDER BY started_at DESC LIMIT 7;
```

## Tracking another game

`tracked_categories` (see `sql/schema.sql`) controls which tcgcsv
categories `App`/`BackfillArchive` actually pull, seeded with just Pokemon
(3). Adding a game is a data change, not a code change:

```sql
INSERT INTO tracked_categories (category_id, name) VALUES (68, 'One Piece Card Game');
```

The next `App` run starts pulling it from that day forward, no rebuild
needed. Its history before that can't be backfilled while tcgcsv's archive
is down (see "Backfill"). See tcgcsv's `/categories` endpoint for the full
category list, and CLAUDE.md for caveats: Pokemon-specific quirks like
code-card exclusion don't automatically apply to a new game; verify before
assuming.

## Backfill

> **Unavailable since September 2026.** tcgcsv took the price archive down
> ("temporarily", citing server costs and moderation burden) and every
> archive URL now returns 403 with that notice as the body. It asks clients
> to use the live category/group/price endpoints and not to fetch the same
> price file more than once in 24 hours. What follows documents the tool as
> it worked, for if the archive returns — check its terms first.

The mirror published daily archives from 2024-02-08 onward at
`https://tcgcsv.com/archive/tcgplayer/prices-YYYY-MM-DD.ppmd.7z`. Each one
bundles every TCG category for that day, and `BackfillArchive` processes every
category in `tracked_categories` from the same download, so tracking a
second game costs zero extra downloads for backfill. Needs `7z` on PATH
(`brew install p7zip`).

```bash
mvn package
java -cp target/cardtracker-0.1.0.jar com.cardtracker.BackfillArchive
# or a specific range:
java -cp target/cardtracker-0.1.0.jar com.cardtracker.BackfillArchive 2024-02-08 2024-12-31
```

Idempotent by (category, day) via `ingest_runs`
(`source = 'tcgcsv-archive-' || category_id`). A day already recorded as
`ok` for a given category is skipped without downloading anything, so
re-running after an interruption, months later to catch up, or right after
adding a new tracked category, only processes what's missing.

## Verified against live data

Field names in `TcgCsvPriceSource` matched the live mirror exactly when
checked on 2026-08-12 (`productId`, `subTypeName`, `marketPrice`, `lowPrice`,
`midPrice`, `highPrice`, `directLowPrice`. Categories, groups, and products
endpoints too). Confirmed again at scale by a full backfill: ~97% of the
~37.7M backfilled rows have a populated `market_price`. The remaining ~3%
being null reflects the mirror's own data, not a mapping error. See the
null-safe parsing note in `TcgCsvPriceSource.decimal`.
