-- CardTracker query functions (v1)
--
-- The analytical building blocks, first worked out by hand in psql, as
-- real, reusable, parameterized SQL rather than hand-typed queries repeated
-- per question.
--
-- Hard rule baked in here, not left as caller-adjustable: a set's price or
-- return is defined ONLY over its singles (is_sealed = FALSE). Sealed
-- product is a fully separate category, and it's trackable individually via
-- item_return()/item_all_time_high() same as any product, but it never
-- contributes to a set-level aggregate. See CLAUDE.md.

-- Return for one specific product+variant between two exact dates. A
-- card OR a sealed item, deliberately, since the math is identical either
-- way and a product_id already has a fixed category the moment you pick
-- it. Named item_*, not card_*, and returns name/is_sealed alongside the
-- numbers specifically so the category is always visible in the result
-- rather than something the caller has to already know or look up
-- separately. Empty result (no row) if either date has no recorded price
-- for that item, deliberately not "nearest available date," so a caller
-- never silently gets a different day than the one they asked for.
CREATE OR REPLACE FUNCTION item_return(
    p_product_id INTEGER,
    p_sub_type_name TEXT,
    p_start_date DATE,
    p_end_date DATE
) RETURNS TABLE (
    name TEXT,
    is_sealed BOOLEAN,
    start_price NUMERIC,
    end_price NUMERIC,
    pct_return NUMERIC
) AS $$
    SELECT
        p.name,
        p.is_sealed,
        s.market_price,
        e.market_price,
        CASE WHEN s.market_price > 0
             THEN ROUND(((e.market_price - s.market_price) / s.market_price) * 100, 2)
             ELSE NULL END
    FROM products p,
        (SELECT market_price FROM price_snapshots
          WHERE product_id = p_product_id AND sub_type_name = p_sub_type_name
            AND snapshot_date = p_start_date AND market_price IS NOT NULL) s,
        (SELECT market_price FROM price_snapshots
          WHERE product_id = p_product_id AND sub_type_name = p_sub_type_name
            AND snapshot_date = p_end_date AND market_price IS NOT NULL) e
    WHERE p.product_id = p_product_id;
$$ LANGUAGE sql STABLE;

-- All-time high for one product+variant, card or sealed, with the same naming and
-- visibility reasoning as item_return above. Market price only (owner,
-- 2026-10-29): mid and low are listing prices, and a MAX over them picks up
-- parked listings, e.g. a $100,000 high for a Charizard nobody paid that for.
-- NULL when the printing has never had a market price.
CREATE OR REPLACE FUNCTION item_all_time_high(
    p_product_id INTEGER,
    p_sub_type_name TEXT
) RETURNS TABLE (
    name TEXT,
    is_sealed BOOLEAN,
    all_time_high NUMERIC
) AS $$
    SELECT p.name, p.is_sealed, MAX(ps.market_price)
    FROM price_snapshots ps
    JOIN products p ON p.product_id = ps.product_id
    WHERE ps.product_id = p_product_id AND ps.sub_type_name = p_sub_type_name
    GROUP BY p.name, p.is_sealed;
$$ LANGUAGE sql STABLE;

-- Portfolio-style set return: sum every single card's price on start_date,
-- sum every single card's price on end_date, return the % change between
-- the two totals. Restricted to the (product, variant) basket present with
-- a real market_price on BOTH dates, so a coverage change between the two
-- days (tcgcsv tracking more/fewer products) never masquerades as a price
-- move.
CREATE OR REPLACE FUNCTION set_return(
    p_group_id INTEGER,
    p_start_date DATE,
    p_end_date DATE
) RETURNS TABLE (
    n_variants INTEGER,
    total_start NUMERIC,
    total_end NUMERIC,
    pct_return NUMERIC
) AS $$
    WITH d1 AS (
        SELECT ps.product_id, ps.sub_type_name, ps.market_price AS price
        FROM price_snapshots ps
        JOIN products p ON p.product_id = ps.product_id
        WHERE p.group_id = p_group_id AND p.is_sealed = FALSE
          AND ps.snapshot_date = p_start_date AND ps.market_price IS NOT NULL
    ),
    d2 AS (
        SELECT ps.product_id, ps.sub_type_name, ps.market_price AS price
        FROM price_snapshots ps
        JOIN products p ON p.product_id = ps.product_id
        WHERE p.group_id = p_group_id AND p.is_sealed = FALSE
          AND ps.snapshot_date = p_end_date AND ps.market_price IS NOT NULL
    ),
    common AS (
        SELECT d1.product_id, d1.sub_type_name, d1.price AS start_price, d2.price AS end_price
        FROM d1 JOIN d2 USING (product_id, sub_type_name)
    )
    SELECT
        COUNT(*)::INTEGER,
        ROUND(SUM(start_price), 2),
        ROUND(SUM(end_price), 2),
        CASE WHEN SUM(start_price) > 0
             THEN ROUND(((SUM(end_price) - SUM(start_price)) / SUM(start_price)) * 100, 2)
             ELSE NULL END
    FROM common;
$$ LANGUAGE sql STABLE;

-- Current (or as-of) total value of a set: sum of every single card's
-- market_price on one day. Singles only, same hard rule as set_return.
--
-- **"Current" means the newest day we HOLD, not today.** This defaulted to
-- CURRENT_DATE until 2026-08-22, and that made the no-argument call (the
-- one README documents and the one a person reaches for in psql) return
-- NULL for most of every day. The day's pull lands after the mirror's
-- ~20:00 UTC refresh, so the newest snapshot is routinely yesterday with
-- nothing wrong; CURRENT_DATE in the container is also UTC while the host
-- may not be, so the two can disagree for hours each evening even on a day
-- that did pull. Measured the day it was fixed:
-- set_value(2848) gave 0 variants / NULL, while the same call anchored on
-- MAX(snapshot_date) gave 369 variants / $8,430.52. Silent, and wrong in the
-- direction that looks like "this set has no data."
--
-- item_movers() and game_set_overview() have always anchored this way. The
-- anchor is deliberately the GLOBAL max rather than this group's own: one
-- as-of date across every set is what makes their values comparable, and a
-- per-group anchor would quietly price Pokemon on one day against One Piece
-- on another. It also keeps this agreeing with game_set_overview(), which
-- sql/checks/set_overview_matches_primitives.sql asserts column by column.
--
-- The scalar sub-select is NOT the pattern CLAUDE.md warns about ("window
-- bounds go in scalar DATE variables, never a CTE sub-select"). That warning
-- is about a bound the planner cannot fold, used in a BETWEEN over a large
-- aggregate. This one is uncorrelated and compared with =, so it becomes an
-- InitPlan parameter: checked with EXPLAIN ANALYZE on 2026-08-22, the plan
-- still reads "Index Cond: (snapshot_date = $1)" on idx_snapshots_date.
CREATE OR REPLACE FUNCTION set_value(
    p_group_id INTEGER,
    p_as_of_date DATE DEFAULT NULL
) RETURNS TABLE (
    n_variants INTEGER,
    total_value NUMERIC
) AS $$
    SELECT COUNT(*)::INTEGER, ROUND(SUM(ps.market_price), 2)
    FROM price_snapshots ps
    JOIN products p ON p.product_id = ps.product_id
    WHERE p.group_id = p_group_id AND p.is_sealed = FALSE
      AND ps.snapshot_date = COALESCE(
            p_as_of_date, (SELECT MAX(s.snapshot_date) FROM price_snapshots s))
      AND ps.market_price IS NOT NULL;
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------------
-- Lookup primitives (v2, added 2026-08-14)
--
-- The three functions below are the read path every future component needs
-- before it can ask anything interesting: find an item, see which variants
-- it actually has, then pull that variant's history. They're deliberately
-- "item", not "card", since sealed product is looked up exactly the same way,
-- the same reasoning that renamed card_return -> item_return.
--
-- Note the split: search returns products, NOT (product, variant) rows.
-- A product with Normal/Holofoil/Reverse Holofoil is ONE search hit with
-- variant_count = 3, not three hits. Collapsing variants into a single
-- "price" would violate the never-collapse-sub-types invariant, so search
-- reports how many exist and item_variants() gives their real prices.
-- ---------------------------------------------------------------------------

-- Pull one named field out of a product's extendedData blob.
--
-- tcgcsv stores card metadata as an array of {name, value} objects rather
-- than as columns, so every read of it is this same unwrap. Factored out
-- so callers don't hand-roll the jsonb_array_elements each time and quietly
-- disagree about it.
--
-- Note this is the same blob the is_sealed classifier keys on (presence of
-- a 'Number' field); see CLAUDE.md. Verified 2026-08-14: 'Number' and
-- 'Rarity' are both populated for all 34,435 singles, Pokemon and One
-- Piece alike, so they're safe to rely on rather than treat as optional.
CREATE OR REPLACE FUNCTION extended_field(p_extended_data JSONB, p_field TEXT)
RETURNS TEXT AS $$
    SELECT ed->>'value'
    FROM jsonb_array_elements(p_extended_data) ed
    WHERE ed->>'name' = p_field
    LIMIT 1;
$$ LANGUAGE sql IMMUTABLE;

-- Is this captured token a real variant descriptor, or just punctuation the
-- name happens to end with? Shared by both halves of variant_label() so a
-- parenthetical and a bracket are judged identically.
--
--   * must contain a letter    - rejects "(2)", "(147)"
--   * must not be a card code  - rejects "(H1)", "(XY72)", "(SWSH014)":
--     up to four letters, then digits, then at most one letter
--   * must be 3+ characters    - rejects "Unown [A]" .. "[Z]"
CREATE OR REPLACE FUNCTION is_variant_word(p_token TEXT)
RETURNS BOOLEAN AS $$
    -- Trims here, not just in the callers. Its Java port
    -- (ImportCardImages.isVariantWord) trims internally, so without this the
    -- two agree only for as long as every caller remembers to btrim first -
    -- and both of the bugs these rules exist to stop come straight back when
    -- one doesn't: ' Z ' clears the length floor (all 26 "Unown [A]".."[Z]")
    -- and ' XY73 ' misses the card-code test ("Latios EX (XY72)"). Measured
    -- 2026-08-20: 81 of 2,547 real tokens flip verdict when padded.
    --
    -- The character set is explicit because bare btrim(x) strips SPACES ONLY,
    -- while Java's String.trim() also removes tabs and newlines, so the
    -- obvious one-argument btrim() left the two still disagreeing on E'\tZ\n'.
    -- Java additionally strips every control char below U+0020; those do not
    -- occur in this catalog, and the check file pins the whitespace cases.
    --
    -- No effect on current callers, which already btrim; verified by
    -- comparing variant_label() across all 38,587 products, unchanged.
    SELECT btrim(p_token, E' \t\n\r\f\v') IS NOT NULL
       AND btrim(p_token, E' \t\n\r\f\v') ~ '[A-Za-z]'
       AND btrim(p_token, E' \t\n\r\f\v') !~ '^[A-Za-z]{0,4}[0-9]+[A-Za-z]?$'
       AND length(btrim(p_token, E' \t\n\r\f\v')) >= 3;
$$ LANGUAGE sql IMMUTABLE;

-- Human-readable variant qualifier from a product name, or NULL when the
-- product is an ordinary printing.
--
-- This exists because TCGplayer encodes the variant in the NAME, not in
-- extendedData, unlike Number and Rarity, which have their own fields.
-- "Blastoise - 13/95 (Cracked Ice Holo)" and plain "Blastoise" are
-- different products at different prices, and the parenthetical is the only
-- thing that says so.
--
-- Derived from the name alone, deliberately: that makes it correct for
-- every product today, independent of whether the image matcher ever finds
-- a picture for it.
--
-- Three rules, each earning its place against real data:
--
--  1. A trailing "- YYYY (Person)" is a World Championship deck, and the
--     parenthetical is the DECK PILOT, not a variant, so rendering it
--     as-is would put a stranger's name on the card. Verified 2026-08-18:
--     all 1,947 Pokemon products matching this shape are in World
--     Championship Decks and nothing else, so the year is safe to promote.
--  2. A parenthetical that is really a card number is not a variant.
--     "Alakazam (2)", "Crobat (147)" and "Latios EX (XY72)" all carry the
--     number in the name; the code shape (up to 4 letters then digits) is
--     the card-code test in is_variant_word().
--  3. A trailing [Staff] / [Bracket] token is a genuine variant of its own
--     ("... (Prerelease) [Staff]"), so it is kept and appended rather than
--     stripped with the rest of the punctuation. It faces the SAME validity
--     test as the parenthetical, which is not optional: Unown is catalogued
--     as "Unown [A]" ... "Unown [Z]", and without the length floor every
--     one of those 26 cards gets a one-letter "variant" label. The real
--     bracket vocabulary is words: Staff, Winner, Set of 4, 1st Place.
CREATE OR REPLACE FUNCTION variant_label(p_name TEXT)
RETURNS TEXT AS $$
DECLARE
    v_name  TEXT := btrim(COALESCE(p_name, ''));
    v_brack TEXT;
    v_paren TEXT;
    v_out   TEXT;
BEGIN
    IF v_name = '' THEN
        RETURN NULL;
    END IF;

    -- TCGplayer sometimes appends the card number AFTER the qualifier
    -- ("Professor's Research [Professor Magnolia] - 223"), which would hide
    -- the bracket from the trailing match below. Drop a trailing number
    -- first. Safe for the two shapes that must survive it: a World
    -- Championship name ends in ")" and "Blastoise - 13/95 (Cracked Ice
    -- Holo)" ends in ")", so neither matches.
    v_name := btrim(regexp_replace(v_name, '\s*-\s*[0-9A-Za-z]+(/[0-9A-Za-z]+)?\s*$', ''));

    -- Rule 3: lift a trailing [Staff]-style token before anything else, so
    -- the parenthetical behind it is still the last thing on the string.
    v_brack := btrim(COALESCE(substring(v_name FROM '\[([^\]]+)\]\s*$'), ''));
    v_name  := btrim(regexp_replace(v_name, '\s*\[[^\]]+\]\s*$', ''));
    IF NOT is_variant_word(v_brack) THEN
        v_brack := NULL;
    END IF;

    IF v_name ~ '- [0-9]{4} \([^)]+\)$' THEN
        -- Rule 1: World Championship deck.
        v_out := substring(v_name FROM '- ([0-9]{4}) \([^)]+\)$') || ' World Championship';
    ELSE
        v_paren := btrim(COALESCE(substring(v_name FROM '\(([^)]*)\)\s*$'), ''));
        IF is_variant_word(v_paren) THEN
            v_out := v_paren;
        END IF;
    END IF;

    IF v_brack IS NOT NULL THEN
        v_out := CASE WHEN v_out IS NULL THEN btrim(v_brack)
                      ELSE v_out || ' · ' || btrim(v_brack) END;
    END IF;

    RETURN v_out;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- normalize_qualifier() and card_image_provenance() sit here, ahead of
-- item_products(), because it calls them and a SQL function's body is
-- checked when it is created: defined later in this file, a fresh database
-- would fail to load it.
--
-- One spelling for a printing qualifier that TCGplayer writes several ways.
--
-- ImportCardImages records stripped_qualifier verbatim, which is right, since it
-- is a fact about the product's name, and "Semi-Finalist" and "Semi
-- Finalist" really are both out there. But the classification vocabulary in
-- image_qualifier_class should not need a row per spelling, so the two sides
-- are normalised at comparison time rather than the raw record being
-- rewritten. Four such pairs exist today: quarter finalist, semi finalist,
-- and two set names that reach the qualifier slot with and without a dash.
--
-- Deliberately conservative: case, hyphens/underscores and repeated spaces
-- only. It does NOT stem or drop words, because "regional championship" and
-- "regional championships" are different rows on purpose until someone says
-- otherwise.
--
-- image_qualifier_class must store qualifiers already normalised, or one
-- image row could match two classification rows and duplicate itself in the
-- output. Guarded in sql/checks/card_image_provenance_invariants.sql.
CREATE OR REPLACE FUNCTION normalize_qualifier(p_raw TEXT)
RETURNS TEXT AS $$
    SELECT NULLIF(btrim(regexp_replace(
        regexp_replace(lower(COALESCE(p_raw, '')), '[-_]', ' ', 'g'),
        '\s+', ' ', 'g')), '');
$$ LANGUAGE sql IMMUTABLE;

-- Whose artwork is a card actually showing?
--
-- Background: ImportCardImages records stripped_qualifier, the printing
-- qualifier it had to drop before a product matched a pokemontcg.io card.
-- That column was deliberately a fact
-- ("the word dropped") rather than a verdict ("the picture is wrong"),
-- because no mechanical test could tell the two apart at the time. The
-- obvious one, "did the printed set total agree?", was tried and rejected
-- it, since a foil reprint keeps the original's numbering.
--
-- TWO signals decide it, and they answer different halves.
--
-- 1. Structural: who else claims the same catalog card? One pokemontcg.io
--    card is one picture, so when several of our products resolve to the
--    same source_id they are all displaying that one picture, whatever
--    their names promise. Verified on disk: plain Blastoise
--    13/95 (Unleashed) and both the Cracked Ice Holo and Prerelease
--    listings of it are three byte-identical files (md5 4cc2be6f...),
--    while Acerola 112/147 and Acerola (Full Art) 142/147 hash differently
--    and are visibly different cards: bordered art against full-bleed.
--
-- 2. Vocabulary: image_qualifier_class, the owner's domain calls. The
--    structural test has a blind spot it cannot see on its own: a sole
--    claimant is only proof that no OTHER product took the picture, not
--    that the picture matches the physical card. "Bronzong - SWSH091
--    (Prerelease)" is a sole claimant and its file is the ordinary Battle
--    Styles promo with no prerelease stamp. Since prerelease cards almost
--    always carry a stamp (owner, 2026-08-19), sole-claimancy gave 18 such
--    cards a false all-clear until the vocabulary table was consulted.
--    Conversely Delta Species is card identity, not a treatment, so its
--    159 sole claimants are genuinely correct, the same structural fact
--    reads oppositely depending on the word, which is exactly why the word
--    has to come from someone who knows Pokemon printings.
--
-- Verdicts:
--   'own_art'  sole claimant, or the unqualified claimant of a shared
--              card, or an 'identity' qualifier among qualified claimants
--   'base_art' the picture is not this printing: either an unqualified
--              product claims the same card, or the qualifier is a
--              'treatment' whose stamp/foil the catalog art never carries.
--              owner_product_id names the holder when one exists; a
--              treatment with no plain sibling listed has none.
--   'unknown'  shared, every claimant qualified, and no classification
--              settles it. Genuinely open, not a guess.
--
-- Measured 2026-08-19 over 26,166 ok rows, with two qualifiers classified.
--
-- Two things this deliberately does NOT do. It does not write a verdict
-- column: a later import that finally matches the missing plain printing,
-- or one more row in image_qualifier_class, would silently invalidate a
-- stored value, the staleness argument that keeps all-time-high computed
-- on demand (CLAUDE.md, Data invariants). And it does not treat 'unknown'
-- as a synonym for base_art.
--
-- Note it also catches rows stripped_qualifier never flagged at all: a
-- product can match on its own name and still share a picture. Provenance
-- is about the image, not about how the name matched.
--
-- p_product_ids asks about some products only: one for the detail view, a
-- page of them for the search rows, which is one call per page rather than
-- one per row (~5ms against ~250ms for 50 rows).
DROP FUNCTION IF EXISTS card_image_provenance(INTEGER, INTEGER);
DROP FUNCTION IF EXISTS card_image_provenance(INTEGER, INTEGER[]);
CREATE OR REPLACE FUNCTION card_image_provenance(
    p_category_id INTEGER   DEFAULT NULL,
    p_product_ids INTEGER[] DEFAULT NULL
) RETURNS TABLE (
    product_id INTEGER,
    name TEXT,
    source_id TEXT,
    stripped_qualifier TEXT,
    qualifier_class TEXT,
    claimants INTEGER,
    provenance TEXT,
    owner_product_id INTEGER,
    owner_name TEXT
) AS $$
    WITH ok AS (
        SELECT ci.product_id, ci.source_id, ci.stripped_qualifier, p.name,
               p.category_id, q.class AS qclass
        FROM card_images ci
        JOIN products p ON p.product_id = ci.product_id
        LEFT JOIN image_qualifier_class q ON q.qualifier = normalize_qualifier(ci.stripped_qualifier)
        WHERE ci.status = 'ok' AND ci.source_id IS NOT NULL
          -- Narrow to the asked-for cards' pictures BEFORE grouping. Every
          -- verdict depends only on who else claims the same source_id, so
          -- restricting to those groups changes no answer, but without it a
          -- lookup builds all ~21,000 groups and then throws them away,
          -- which is why the detail view cost ~96ms. Verified by comparing
          -- every row before and after, for one product (2026-08-20) and for
          -- all of them at once (2026-10-29): byte-identical.
          AND (p_product_ids IS NULL OR ci.source_id IN (
                SELECT c2.source_id FROM card_images c2
                WHERE c2.product_id = ANY (p_product_ids) AND c2.status = 'ok'))
    ),
    grp AS (
        SELECT source_id,
               COUNT(*)::INTEGER AS claimants,
               COUNT(*) FILTER (WHERE stripped_qualifier IS NULL) AS unqualified,
               COUNT(*) FILTER (WHERE qclass = 'identity')        AS identities
        FROM ok GROUP BY source_id
    ),
    -- Who holds the printing the catalog art actually depicts? An
    -- unqualified claimant first, and several may exist (duplicate TCGplayer
    -- listings, or the same card with and without its number in the name,
    -- "Houndoom" / "Houndoom - 008/064"), so the shortest name is picked
    -- purely to name one of them. Failing that, an 'identity' claimant:
    -- ex15-28 has no plain listing, but "Dragonair (Delta Species)" holds
    -- the art its "(Prerelease)" reprint is borrowing.
    owner AS (
        SELECT DISTINCT ON (source_id) source_id, product_id, name
        FROM ok
        WHERE stripped_qualifier IS NULL OR qclass = 'identity'
        ORDER BY source_id,
                 (stripped_qualifier IS NOT NULL),  -- unqualified wins
                 length(name), name, product_id
    )
    SELECT o.product_id, o.name, o.source_id, o.stripped_qualifier, o.qclass,
           g.claimants,
           CASE
               -- A stamp or foil the catalog art never carries: the picture
               -- is the plain printing however few products claim it.
               WHEN o.qclass = 'treatment'             THEN 'base_art'
               WHEN g.claimants = 1                    THEN 'own_art'
               WHEN o.stripped_qualifier IS NULL       THEN 'own_art'
               WHEN g.unqualified > 0                  THEN 'base_art'
               WHEN o.qclass = 'identity'              THEN 'own_art'
               ELSE 'unknown'
           END,
           CASE WHEN o.qclass = 'treatment'
                      OR (o.stripped_qualifier IS NOT NULL
                          AND (g.unqualified > 0 OR g.identities > 0))
                THEN w.product_id END,
           CASE WHEN o.qclass = 'treatment'
                      OR (o.stripped_qualifier IS NOT NULL
                          AND (g.unqualified > 0 OR g.identities > 0))
                THEN w.name END
    FROM ok o
    JOIN grp g ON g.source_id = o.source_id
    LEFT JOIN owner w ON w.source_id = o.source_id AND w.product_id <> o.product_id
    WHERE (p_category_id IS NULL OR o.category_id = p_category_id)
      AND (p_product_ids IS NULL OR o.product_id = ANY (p_product_ids))
    ORDER BY o.name, o.product_id;
$$ LANGUAGE sql STABLE;

-- Products' identity rows, in the shape search returns them: what a card
-- is, which set and game, how many variants it trades as and how recent its
-- newest price is. Search runs this over the page of hits it keeps, and
-- /items/{id} (item_product, below) over a single id, so a product can't be
-- described two ways.
--
-- art_borrowed is TRUE when our picture is provably another printing's
-- (card_image_provenance() says 'base_art'), with art_owner_name naming that
-- printing when one is listed. Search rows show it, as the detail view
-- already did (owner, 2026-10-29). It takes the whole page in one
-- provenance call: per row it cost ~250ms a page, against ~60ms for the
-- search itself.
--
-- card_number and rarity come from extendedData, NOT from the name, and
-- they are not decoration: they are frequently the only thing separating
-- two hits. The same set routinely reprints one card at several rarities:
-- SV03 Obsidian Flames has three "Charizard ex" (125/197 Double Rare,
-- 215/197 Ultra Rare, 223/197 Special Illustration Rare) that trade at
-- very different prices. TCGplayer does append the number to the name in
-- those cases, but only sometimes: just 5,276 of 34,435 singles have a
-- number in the name, so Base Set Charizard would come back with nothing
-- to identify it. extendedData carries Number and Rarity for 100% of
-- singles in both tracked games, so that's the source used here.
DROP FUNCTION IF EXISTS item_products(INTEGER[]);
CREATE OR REPLACE FUNCTION item_products(p_product_ids INTEGER[])
RETURNS TABLE (
    product_id INTEGER,
    name TEXT,
    card_number TEXT,
    rarity TEXT,
    variant_label TEXT,
    set_name TEXT,
    game TEXT,
    is_sealed BOOLEAN,
    variant_count INTEGER,
    latest_date DATE,
    url TEXT,
    image_url TEXT,
    art_borrowed BOOLEAN,
    art_owner_name TEXT
) AS $$
    SELECT p.product_id, p.name,
           extended_field(p.extended_data, 'Number'),
           extended_field(p.extended_data, 'Rarity'),
           variant_label(p.name),
           g.name, c.name, p.is_sealed,
           v.variant_count, v.latest_date, p.url, p.image_url,
           COALESCE(pv.provenance = 'base_art', FALSE),
           CASE WHEN pv.provenance = 'base_art' THEN pv.owner_name END
    FROM products p
    JOIN groups g ON g.group_id = p.group_id
    JOIN categories c ON c.category_id = p.category_id
    CROSS JOIN LATERAL (
        SELECT COUNT(DISTINCT ps.sub_type_name)::INTEGER AS variant_count,
               MAX(ps.snapshot_date) AS latest_date
        FROM price_snapshots ps WHERE ps.product_id = p.product_id
    ) v
    LEFT JOIN card_image_provenance(NULL, p_product_ids) pv
           ON pv.product_id = p.product_id
    WHERE p.product_id = ANY (p_product_ids);
$$ LANGUAGE sql STABLE;

-- One product, for /items/{id}. Dropped first because art_* changed its
-- return type.
DROP FUNCTION IF EXISTS item_product(INTEGER);
CREATE OR REPLACE FUNCTION item_product(p_product_id INTEGER)
RETURNS TABLE (
    product_id INTEGER,
    name TEXT,
    card_number TEXT,
    rarity TEXT,
    variant_label TEXT,
    set_name TEXT,
    game TEXT,
    is_sealed BOOLEAN,
    variant_count INTEGER,
    latest_date DATE,
    url TEXT,
    image_url TEXT,
    art_borrowed BOOLEAN,
    art_owner_name TEXT
) AS $$
    SELECT * FROM item_products(ARRAY[p_product_id]);
$$ LANGUAGE sql STABLE;

-- Name search across products, newest-relevance first. Case-insensitive
-- substring match, backed by the pg_trgm GIN index on products.name (see
-- schema.sql). Without that index this is a full seq scan on every
-- keystroke.
--
-- Ranking is deliberately simple and explainable rather than a similarity
-- score: exact name match, then prefix match, then any substring; ties
-- broken by name length so "Charizard" outranks "Charizard G" and
-- "Bug Catcher - 189/236 (#34 Charizard Stamped)". Card names are short
-- and highly repeated across sets (266 Pokemon products match
-- 'charizard'), so set_name/game are returned on every row, since a bare name
-- list is genuinely ambiguous here, not just less pretty.
--
-- p_category_id filters to one game (NULL = all tracked games).
-- p_include_sealed = FALSE hides sealed product. It IS a parameter here,
-- unlike in set_return/set_value where sealed is hardcoded out. Looking
-- something up is not a set-level aggregate, and "find me that booster
-- box" is a legitimate search.
--
-- Each hit is described by item_products(), where card_number, rarity and
-- art_borrowed are explained.
--
-- CREATE OR REPLACE cannot change a function's return type ("cannot change
-- return type of existing function"), so adding a column to the RETURNS
-- TABLE below makes a plain re-run of this file fail against any database
-- that already has the older signature. Dropping first keeps this file
-- re-runnable, which is the whole point of it, since it's loaded on fresh boot
-- (where this is a no-op) and re-applied by hand after every edit. Signature
-- is spelled out because item_search is overload-free but Postgres still
-- needs the exact argument list to identify it.
DROP FUNCTION IF EXISTS item_search(TEXT, INTEGER, BOOLEAN, INTEGER, INTEGER);
CREATE OR REPLACE FUNCTION item_search(
    p_query TEXT,
    p_category_id INTEGER DEFAULT NULL,
    p_include_sealed BOOLEAN DEFAULT TRUE,
    p_limit INTEGER DEFAULT 25,
    p_offset INTEGER DEFAULT 0
) RETURNS TABLE (
    product_id INTEGER,
    name TEXT,
    card_number TEXT,
    rarity TEXT,
    variant_label TEXT,
    set_name TEXT,
    game TEXT,
    is_sealed BOOLEAN,
    variant_count INTEGER,
    latest_date DATE,
    url TEXT,
    image_url TEXT,
    art_borrowed BOOLEAN,
    art_owner_name TEXT
) AS $$
    -- The LIMIT has to be applied BEFORE the per-product variant lookup,
    -- not after: as one CTE with the lateral join inline, Postgres runs
    -- the variant scan for every match (266 for 'charizard') and only then
    -- takes 25, measured at 722ms vs 37ms for this shape.
    WITH hits AS (
        SELECT p.product_id, p.name,
               CASE WHEN lower(p.name) = lower(btrim(p_query)) THEN 0
                    WHEN lower(p.name) LIKE lower(btrim(p_query)) || '%' THEN 1
                    ELSE 2 END AS rank
        FROM products p
        WHERE p.name ILIKE '%' || btrim(COALESCE(p_query, '')) || '%'
          AND (p_category_id IS NULL OR p.category_id = p_category_id)
          AND (p_include_sealed OR p.is_sealed = FALSE)
        -- product_id is the final tiebreaker purely for determinism, and it
        -- is load-bearing for paging: many products share an identical name
        -- across sets (there are dozens of cards named exactly "Charizard"),
        -- so rank/length/name alone leaves them tied and Postgres free to
        -- order them differently per call, which makes OFFSET silently
        -- duplicate or skip rows between pages.
        ORDER BY rank, length(p.name), p.name, p.product_id
        LIMIT LEAST(COALESCE(p_limit, 25), 200) OFFSET GREATEST(COALESCE(p_offset, 0), 0)
    )
    -- Described after the LIMIT, same reasoning as the variant lookup: no
    -- point unwrapping JSON for 266 matches to return 25. One call for the
    -- whole page, so the picture check runs once rather than per row.
    SELECT d.*
    FROM hits h
    JOIN item_products(ARRAY(SELECT product_id FROM hits)) d
      ON d.product_id = h.product_id
    ORDER BY h.rank, length(h.name), h.name, h.product_id;
$$ LANGUAGE sql STABLE;

-- Every variant of one product, with its current price and all-time high.
-- This is the bridge between search and item_return/item_all_time_high:
-- both of those require a sub_type_name, and a caller has no way to guess
-- whether a given card exists as Normal, Holofoil, Reverse Holofoil, or
-- some combination. It varies per product and even changes over time
-- (Altaria 83514's Normal variant only starts 2025-03-28 while its
-- Holofoil goes back to 2024-02-08, which is why first_date is returned
-- rather than assumed).
--
-- all_time_high is market price only, as item_all_time_high() is, and
-- cross-checks against it exactly; latest_market is raw market_price too, so
-- "no market price recorded that day" reads as NULL instead of quietly
-- substituting a listing price.
CREATE OR REPLACE FUNCTION item_variants(
    p_product_id INTEGER
) RETURNS TABLE (
    sub_type_name TEXT,
    first_date DATE,
    latest_date DATE,
    latest_market NUMERIC,
    all_time_high NUMERIC
) AS $$
    WITH variants AS (
        SELECT ps.sub_type_name AS stn,
               MIN(ps.snapshot_date) AS first_date,
               MAX(ps.snapshot_date) AS latest_date,
               MAX(ps.market_price) AS ath
        FROM price_snapshots ps
        WHERE ps.product_id = p_product_id
        GROUP BY ps.sub_type_name
    )
    SELECT v.stn, v.first_date, v.latest_date, ps.market_price, v.ath
    FROM variants v
    JOIN price_snapshots ps
      ON ps.product_id = p_product_id
     AND ps.sub_type_name = v.stn
     AND ps.snapshot_date = v.latest_date
    ORDER BY v.stn;
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------------
-- Launch pricing (added 2026-09-29)
-- ---------------------------------------------------------------------------

-- A card's release date, for telling its pre-release and early prices from
-- settled ones. Normally its set's date, with two exceptions:
--
--   * First priced more than 30 days after its set came out: a card added to
--     a long-running group later, like a new promo in a promo group dated
--     when the line began (the 30th Celebration promos sit in the Mega
--     Evolution promo group, dated 2025). Its first price is the best release
--     date there is. The same goes for any card in an undated group.
--   * Priced since before our history begins: released long ago, nothing to
--     filter, so NULL.
--
-- Only lateness is bounded, never earliness: preorder prices can start months
-- ahead (some 30th Celebration cards were priced from June for a September
-- release), and they are still that set's launch.
CREATE OR REPLACE FUNCTION card_release(p_set_released DATE, p_first_priced DATE,
                                        p_history_start DATE)
RETURNS DATE AS $$
    SELECT CASE
        WHEN p_set_released IS NOT NULL AND p_first_priced <= p_set_released + 30
            THEN p_set_released
        WHEN p_first_priced > p_history_start
            THEN p_first_priced
    END;
$$ LANGUAGE sql IMMUTABLE;

-- Which part of a card's life a price on p_on comes from:
--   'pre_release'  before release: preorders, thin and hyped
--   'early'        the first p_early_days after release, while it settles
--   NULL           settled, or a card with no known release
-- What each looks like in the data, and why 56 days: migration 004.
CREATE OR REPLACE FUNCTION price_phase(p_on DATE, p_release DATE, p_early_days INTEGER)
RETURNS TEXT AS $$
    SELECT CASE
        WHEN p_release IS NULL THEN NULL
        WHEN p_on < p_release THEN 'pre_release'
        WHEN p_on < p_release + p_early_days THEN 'early'
    END;
$$ LANGUAGE sql IMMUTABLE;

-- Every card (product + variant) whose price on p_on is launch pricing the
-- caller asked to leave out. Movers and Sets both read this, so they can
-- never disagree about what counts.
--
-- Runs on every Movers and Sets request, so it narrows before it decides. A
-- card can only be launch pricing on p_on if its release is within
-- early_days of p_on or later, and its release is either its set's date or
-- its own first price. So the candidates are cards in a recent set, plus
-- cards first priced recently, and only those few thousand get a phase.
-- Working out a phase for all ~53k cards cost ~190ms a request, measured.
-- The candidates are MATERIALIZED so release is a plain column when
-- price_phase() reads it, which lets that inline too instead of running as a
-- function call per row.
CREATE OR REPLACE FUNCTION launch_noise(p_on DATE, p_include_prerelease BOOLEAN,
                                        p_include_early BOOLEAN)
RETURNS TABLE (product_id INTEGER, sub_type_name TEXT) AS $$
    WITH candidates AS (
        SELECT f.product_id, f.sub_type_name, f.first_date
        FROM groups g
        JOIN tracked_categories t ON t.category_id = g.category_id
        JOIN products p ON p.group_id = g.group_id
        JOIN card_first_priced f ON f.product_id = p.product_id
        WHERE g.published_on > p_on - t.early_days
        UNION
        SELECT f.product_id, f.sub_type_name, f.first_date
        FROM card_first_priced f
        WHERE f.first_date > p_on - (SELECT MAX(early_days) FROM tracked_categories)
    ),
    card AS MATERIALIZED (
        SELECT c.product_id, c.sub_type_name, t.early_days,
               card_release(g.published_on, c.first_date,
                            (SELECT MIN(x.snapshot_date) FROM price_snapshots x)) AS released
        FROM candidates c
        JOIN products p ON p.product_id = c.product_id
        JOIN groups g ON g.group_id = p.group_id
        JOIN tracked_categories t ON t.category_id = p.category_id
    )
    SELECT c.product_id, c.sub_type_name
    FROM card c
    WHERE (price_phase(p_on, c.released, c.early_days) = 'pre_release'
           AND NOT COALESCE(p_include_prerelease, FALSE))
       OR (price_phase(p_on, c.released, c.early_days) = 'early'
           AND NOT COALESCE(p_include_early, FALSE));
$$ LANGUAGE sql STABLE;

-- Price time series for one product+variant, for charting.
--
-- high_price is deliberately NOT returned. Sellers price-park (list at
-- absurd values so nothing sells), so it's noise, per CLAUDE.md's "store it,
-- ignore it" rule. Omitting it from the API entirely makes that rule
-- structural rather than a convention a future caller has to know about,
-- the same way set_return() hardcodes sealed product out instead of
-- offering a parameter.
--
-- A chart plots market_price alone (owner, 2026-10-29). mid and low are
-- returned for reading, never charted: they are listing prices, and a day
-- with no sale filled in from them put parked listings on the chart.
--
-- phase marks each day as pre-release, early or settled (price_phase()), so a
-- chart can shade or hide launch pricing. Nothing is filtered here; the
-- caller chooses.
--
-- NULL dates mean "no bound": both NULL gives the item's full history.
--
-- Dropped first because its return type changes (phase added, the
-- market->mid->low trendable_price removed), which CREATE OR REPLACE cannot do.
DROP FUNCTION IF EXISTS item_history(INTEGER, TEXT, DATE, DATE);
CREATE OR REPLACE FUNCTION item_history(
    p_product_id INTEGER,
    p_sub_type_name TEXT,
    p_start_date DATE DEFAULT NULL,
    p_end_date DATE DEFAULT NULL
) RETURNS TABLE (
    snapshot_date DATE,
    market_price NUMERIC,
    low_price NUMERIC,
    mid_price NUMERIC,
    direct_low_price NUMERIC,
    phase TEXT
) AS $$
    WITH card AS (
        SELECT card_release(g.published_on, f.first_date,
                            (SELECT MIN(x.snapshot_date) FROM price_snapshots x)) AS released,
               t.early_days
        FROM products p
        JOIN groups g ON g.group_id = p.group_id
        JOIN tracked_categories t ON t.category_id = p.category_id
        JOIN card_first_priced f ON f.product_id = p.product_id
                                AND f.sub_type_name = p_sub_type_name
        WHERE p.product_id = p_product_id
    )
    SELECT ps.snapshot_date, ps.market_price, ps.low_price, ps.mid_price,
           ps.direct_low_price,
           price_phase(ps.snapshot_date, card.released, card.early_days)
    FROM price_snapshots ps
    LEFT JOIN card ON TRUE
    WHERE ps.product_id = p_product_id
      AND ps.sub_type_name = p_sub_type_name
      AND (p_start_date IS NULL OR ps.snapshot_date >= p_start_date)
      AND (p_end_date IS NULL OR ps.snapshot_date <= p_end_date)
    ORDER BY ps.snapshot_date;
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------------
-- Modern sets (added 2026-09-29)
-- ---------------------------------------------------------------------------

-- The one definition of "modern", for every screen that filters on it: a set
-- released on or after its game's tracked_categories.modern_since, or any set
-- of a game that has no cutoff. A set with no release date is never modern,
-- because there is no way to tell which block it belongs to; the comparison
-- is NULL for it, and WHERE drops NULL.
--
-- Where the cutoff sits and why: migration 003.
CREATE OR REPLACE FUNCTION modern_group_ids()
RETURNS TABLE (group_id INTEGER) AS $$
    SELECT g.group_id
    FROM groups g
    JOIN tracked_categories t ON t.category_id = g.category_id
    WHERE t.modern_since IS NULL OR g.published_on >= t.modern_since;
$$ LANGUAGE sql STABLE;

-- The tracked games, for every screen's game filter and the Settings screen.
-- A game that is listed but has never been pulled still appears, named from
-- its tracked_categories row. modern_set_count of set_count is what the
-- modern rule currently takes in, counted by modern_group_ids() itself.
CREATE OR REPLACE FUNCTION tracked_games()
RETURNS TABLE (
    category_id INTEGER,
    name TEXT,
    product_count INTEGER,
    modern_since DATE,
    early_days INTEGER,
    set_count INTEGER,
    modern_set_count INTEGER
) AS $$
    SELECT tc.category_id, COALESCE(c.name, tc.name),
           (SELECT COUNT(*) FROM products p
             WHERE p.category_id = tc.category_id)::INTEGER,
           tc.modern_since, tc.early_days,
           (SELECT COUNT(*) FROM groups g
             WHERE g.category_id = tc.category_id)::INTEGER,
           (SELECT COUNT(*) FROM groups g
              JOIN modern_group_ids() m ON m.group_id = g.group_id
             WHERE g.category_id = tc.category_id)::INTEGER
      FROM tracked_categories tc
      LEFT JOIN categories c ON c.category_id = tc.category_id
     ORDER BY 2;
$$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------------
-- Movers (v3, added 2026-08-16)
-- ---------------------------------------------------------------------------

-- Biggest price moves over a window, for the "what moved this week" screen.
--
-- Rows are per (product, variant), NOT per product like item_search. A mover
-- is a specific variant's move, and Holofoil and Reverse Holofoil are different
-- markets that move independently, so collapsing them would be the sub-type
-- invariant violation, not the other way round.
--
-- **MEDIAN over a window at both ends, not a two-point comparison and not
-- a mean.** This is the whole reason the function looks more complicated
-- than "price then vs price now." Thinly-traded cards lurch on single
-- sales: Charizard Star (Delta Species, 84198) sat at $4,000 for weeks,
-- printed $980 for exactly one day, snapped back to $4,000, then settled
-- near $990. Point-to-point that reads as -75% one day and +308% the next,
-- and a decliners board would be topped by that kind of artifact daily.
--
-- This used a MEAN until 2026-08-18 and the change is a correctness fix,
-- not a preference. A mean damps everything, signal included, and worse, it
-- contaminates one window with prices from the other side of a step. Both
-- failures were measured on live data the day of the change:
--
--   * Lillie - 125a/156 (199681, Reverse Holofoil) stepped $41.10 ->
--     $123.32 and held it for eight days. A genuine +200.0% move. The mean
--     reported +80.0%, because one day of the post-step price leaked into
--     the "then" window. The median reports +200.0% exactly.
--   * Flaaffy (85475, Reverse Holofoil) sat at $1.69 for nine days then
--     moved to $50.83. The mean called the current price $34.45, a number
--     the card has never traded at. The median says $50.83.
--
-- A median leaves steps and monotone trends untouched while annihilating
-- single-sample impulses outright: the $980 artifact above becomes exactly
-- $4,000 rather than a damped $2,993 that still shows a phantom -25%. That
-- is a property of the filter, not a tuning choice. game_set_overview()'s
-- despiking rests on the same property.
--
-- Prefer an ODD p_smoothing. At odd sizes the median is a price the card
-- was genuinely at on some day; at even sizes percentile_cont interpolates
-- between the two middle days and can report a price never observed, which
-- is the exact failure the mean was replaced for. Default 3.
--
-- A median does react faster than the mean it replaced, so genuine one-day
-- noise on thin cards now shows at full size rather than damped. p_min_price
-- is what keeps that off the board.
--
-- Both windows must be FULLY covered (n = p_smoothing at each end).
-- A partial window would take the median of fewer days and quietly weight
-- them more.
--
-- p_min_price exists because percent change flatters cheap cards: without
-- it the top decliner for a week was Shuckle at $0.06 -> $0.01 (-83%), and
-- four of the top twelve were sub-$1 cards moving a few cents. Defaults to
-- $1, applied to the STARTING average.
--
-- market_price only, no mid/low fallback, same reasoning as item_return:
-- a fallback price on a two-anchor
-- comparison is where the -99% return bug came from.
--
-- Restricted to the basket present in both windows, like set_return, so
-- tcgcsv covering more or fewer products between the two dates can never
-- masquerade as a price move.
--
-- p_modern_only filters BEFORE the LIMIT, which is why it is a parameter and
-- not something a caller can do to the result: cutting 50 rows and then
-- discarding the old ones would leave a short board.
--
-- Launch pricing (p_include_prerelease, p_include_early) is left out by
-- default, for the same before-the-LIMIT reason. A card whose "then" price
-- was a preorder or still settling (launch_noise()) is not on the board:
-- on 2026-09-29, 28 of the 50 biggest modern drops were cards that did not
-- exist ten weeks earlier, sliding the way every new set does. Only the
-- "then" end is checked, because "now" is later and can't be earlier in a
-- card's life.
--
-- The 9- and 10-argument forms are signatures from before p_modern_only and
-- the launch flags. Dropped so an existing database does not keep them as
-- second, ambiguous overloads.
DROP FUNCTION IF EXISTS item_movers(INTEGER, INTEGER, INTEGER, BOOLEAN, NUMERIC, TEXT, INTEGER, INTEGER, DATE);
DROP FUNCTION IF EXISTS item_movers(INTEGER, INTEGER, INTEGER, BOOLEAN, NUMERIC, TEXT, INTEGER, INTEGER, DATE, BOOLEAN);
CREATE OR REPLACE FUNCTION item_movers(
    p_days INTEGER DEFAULT 7,
    p_category_id INTEGER DEFAULT NULL,
    p_group_id INTEGER DEFAULT NULL,
    p_include_sealed BOOLEAN DEFAULT FALSE,
    p_min_price NUMERIC DEFAULT 1,
    p_direction TEXT DEFAULT 'up',
    p_smoothing INTEGER DEFAULT 3,
    p_limit INTEGER DEFAULT 50,
    p_as_of DATE DEFAULT NULL,
    p_modern_only BOOLEAN DEFAULT FALSE,
    p_include_prerelease BOOLEAN DEFAULT FALSE,
    p_include_early BOOLEAN DEFAULT FALSE
) RETURNS TABLE (
    product_id INTEGER,
    name TEXT,
    card_number TEXT,
    rarity TEXT,
    set_name TEXT,
    game TEXT,
    sub_type_name TEXT,
    is_sealed BOOLEAN,
    then_price NUMERIC,
    now_price NUMERIC,
    pct_change NUMERIC,
    url TEXT
) AS $$
DECLARE
    -- Clamped to the window, because the two medians must not share days.
    -- 'recent' spans [end-(smooth-1), end] and 'prior' spans
    -- [end-days-(smooth-1), end-days], so they stay disjoint only while
    -- smooth <= days. At the default smoothing of 3 a 1-day window had the
    -- two windows sharing two of their three days, which damps every real
    -- move toward zero, the same failure mode as the mean this function
    -- stopped using, arriving by a different route. No effect on
    -- 7/30/90, where 3 <= days already.
    v_smooth      INTEGER := LEAST(GREATEST(COALESCE(p_smoothing, 3), 1),
                                   GREATEST(COALESCE(p_days, 7), 1));
    v_end         DATE;
    v_recent_from DATE;
    v_prior_to    DATE;
    v_prior_from  DATE;
BEGIN
    -- Window bounds are resolved to plain DATE variables BEFORE the query
    -- rather than computed in a CTE. That is a 5x difference, not a style
    -- preference: with the bounds coming from a sub-select, the planner
    -- cannot fold them into constants, fails to use idx_snapshots_date
    -- properly, and aggregates far more of the 41M-row table than needed -
    -- measured spilling ~6GB to temp files. As scalars the same query is an
    -- ordinary indexed range scan over six days.
    v_end := COALESCE(p_as_of, (SELECT MAX(snapshot_date) FROM price_snapshots));
    v_recent_from := v_end - (v_smooth - 1);
    v_prior_to    := v_end - p_days;
    v_prior_from  := v_end - p_days - (v_smooth - 1);

    RETURN QUERY
    WITH recent AS (
        SELECT ps.product_id, ps.sub_type_name,
               (percentile_cont(0.5) WITHIN GROUP (ORDER BY ps.market_price))::NUMERIC AS p,
               COUNT(*) AS n
        FROM price_snapshots ps
        WHERE ps.snapshot_date BETWEEN v_recent_from AND v_end
          AND ps.market_price IS NOT NULL
        GROUP BY 1, 2
    ),
    prior AS (
        SELECT ps.product_id, ps.sub_type_name,
               (percentile_cont(0.5) WITHIN GROUP (ORDER BY ps.market_price))::NUMERIC AS p,
               COUNT(*) AS n
        FROM price_snapshots ps
        WHERE ps.snapshot_date BETWEEN v_prior_from AND v_prior_to
          AND ps.market_price IS NOT NULL
        GROUP BY 1, 2
    )
    ,
    -- Rank and cut BEFORE joining the display tables. This is a 10x
    -- difference, not tidiness. The planner cannot estimate selectivity
    -- through the CTEs and guesses 2 rows out of this join when the truth is
    -- ~23,000; believing 2, it picks nested loops against groups (302 rows)
    -- and categories, materialises the real 23,000 and rescans them once per
    -- group: loops=302, about 7GB of temp file traffic, measured. Cutting to the
    -- LIMIT here means groups, categories and the extended_data unwrap only
    -- ever see 50 rows, so the bad estimate stops mattering.
    -- Only products is joined at this stage, and only because the filters
    -- need it; it is a primary-key lookup.
    moves AS (
        SELECT prior.product_id AS pid,
               prior.sub_type_name AS stn,
               prior.p AS then_p,
               recent.p AS now_p,
               (recent.p - prior.p) / prior.p AS chg
        FROM prior
        JOIN recent USING (product_id, sub_type_name)
        JOIN products pr ON pr.product_id = prior.product_id
        WHERE prior.n = v_smooth
          AND recent.n = v_smooth
          AND prior.p >= COALESCE(p_min_price, 0)
          AND prior.p > 0
          AND (p_category_id IS NULL OR pr.category_id = p_category_id)
          AND (p_group_id IS NULL OR pr.group_id = p_group_id)
          AND (p_include_sealed OR pr.is_sealed = FALSE)
          AND (NOT COALESCE(p_modern_only, FALSE)
               OR pr.group_id IN (SELECT mg.group_id FROM modern_group_ids() mg))
          AND (prior.product_id, prior.sub_type_name) NOT IN (
              SELECT ln.product_id, ln.sub_type_name
              FROM launch_noise(v_prior_from, p_include_prerelease, p_include_early) ln)
        ORDER BY
            CASE WHEN p_direction = 'up' THEN (recent.p - prior.p) / prior.p END DESC NULLS LAST,
            CASE WHEN p_direction <> 'up' THEN (recent.p - prior.p) / prior.p END ASC NULLS LAST,
            prior.product_id
        LIMIT LEAST(COALESCE(p_limit, 50), 200)
    )
    SELECT
        pr.product_id,
        pr.name,
        extended_field(pr.extended_data, 'Number'),
        extended_field(pr.extended_data, 'Rarity'),
        g.name,
        c.name,
        m.stn,
        pr.is_sealed,
        ROUND(m.then_p, 2),
        ROUND(m.now_p, 2),
        ROUND(m.chg * 100, 2),
        pr.url
    FROM moves m
    JOIN products pr ON pr.product_id = m.pid
    JOIN groups g ON g.group_id = pr.group_id
    JOIN categories c ON c.category_id = pr.category_id
    -- Repeated because a LIMIT in a CTE does not guarantee the outer order.
    ORDER BY
        CASE WHEN p_direction = 'up' THEN m.chg END DESC NULLS LAST,
        CASE WHEN p_direction <> 'up' THEN m.chg END ASC NULLS LAST,
        pr.product_id;
END;
$$ LANGUAGE plpgsql STABLE;

-- Sets for a game, newest first, for the movers screen's set filter.
-- Only groups that actually have tracked products, so the dropdown can't
-- offer a set that would return nothing. is_modern lets the dropdown follow
-- the screen's modern filter without restating the rule.
--
-- Dropped first because adding is_modern changed the return type, which
-- CREATE OR REPLACE cannot do.
DROP FUNCTION IF EXISTS game_sets(INTEGER);
CREATE OR REPLACE FUNCTION game_sets(p_category_id INTEGER)
RETURNS TABLE (
    group_id INTEGER,
    name TEXT,
    published_on DATE,
    product_count INTEGER,
    is_modern BOOLEAN
) AS $$
    SELECT g.group_id, g.name, g.published_on, COUNT(p.product_id)::INTEGER,
           g.group_id IN (SELECT mg.group_id FROM modern_group_ids() mg)
    FROM groups g
    JOIN products p ON p.group_id = g.group_id
    WHERE g.category_id = p_category_id
    GROUP BY g.group_id, g.name, g.published_on
    ORDER BY g.published_on DESC NULLS LAST, g.name;
$$ LANGUAGE sql STABLE;
-- ---------------------------------------------------------------------------
-- Set overview (added 2026-08-18, despiking added same day)
--
-- One row per set: what it's worth today and how it moved over a window.
-- The per-set primitives set_value()/set_return() already answer this for
-- one set, but the screen needs every set of a game at once, 302 tracked
-- groups, so calling them per set is 604 round trips. This is those two
-- functions' definitions computed set-wide in a single pass; the semantics
-- are deliberately identical (with p_despike = FALSE, see below), and
-- set_return()'s numbers are the reference these must match.
--
-- Three things carried over from the primitives, all load-bearing:
--
--   * Singles only (is_sealed = FALSE), hardcoded, not a parameter. Same
--     hard rule as set_return(); see this file's header and CLAUDE.md.
--   * The return uses only rows priced on BOTH days (an inner join on
--     product_id + sub_type_name), so a set's composition changing inside
--     the window can't masquerade as a price move.
--   * pct_return is NULL, never 0, when the starting basket is <= 0.
--
-- Hence n_priced vs n_common, and total_value vs total_end: value is every
-- single priced on the end date, the return is only the common basket.
--
-- WHY THE CONCENTRATION COLUMNS EXIST (top_*). Measured 2026-08-18: among
-- Pokemon sets with a >=2% 30-day move, the single largest contributing row
-- is routinely 50-87% of ALL movement in the set. Base Set (Shadowless)
-- read +59.96% on a basket of 202, and $9,750 of the $10,718 move was one
-- row, Charizard 1st Edition Holofoil, whose market_price sits at ~$250 or
-- at exactly $10,000 for weeks at a time. Report the biggest single
-- contributor and its share of gross movement, so a set carried by one row
-- is visible instead of quietly trusted. top_share is a share of GROSS
-- movement (SUM of |delta|), not of the net: net goes through zero, and
-- ratios against it explode to meaningless four-digit percentages.
--
-- DESPIKING (p_despike). A separate defect from the above, and the owner's
-- call on 2026-08-18: a price that swings hard and is back to its previous
-- level the very next day is almost certainly an upstream error, not a
-- move. The test keys on persistence, not magnitude: a real move leaves a
-- new level behind it, an error leaves the same level on both sides, so it
-- needs no opinion about how large a genuine move may be.
--
-- A reading is replaced with median(prev, cur, next) when all of:
--   * both neighbour days exist and are positive;
--   * the neighbours agree with each other within p_despike_tolerance;
--   * cur deviates from their midpoint by >= p_despike_deviation;
--   * and the distortion is at least p_despike_min_usd.
--
-- MEDIAN, not mean, and that is the whole point. Measured on Charizard
-- Star (84198), which has both a real step (970 -> 4000 on 2026-07-24) and
-- a one-day artifact ($980 on 2026-08-04 between $4,000 either side):
--   median-3: artifact -> 4000 (gone entirely), step preserved at full size
--             on the correct day.
--   mean-3:   artifact -> 2993 (a phantom -25% survives), AND the real step
--             smears across three days, inventing a +108% move on 07-23
--             that never happened.
-- A mean damps everything including real moves; a median annihilates
-- single-sample impulses and leaves steps and trends untouched.
--
-- The dollar gate exists because the effect is heavily tailed. Over the 90
-- days to 2026-08-18 there were 4,700 such spikes, but 4,652 were sub-$1
-- penny cards worth $138 in total, while 15 spikes carried $32,440, or 99.4%
-- of all distortion. Filtering everything would churn thousands of rows to
-- fix rounding noise; $10 catches 26 spikes and 99.6% of the money.
--
-- LIMITATION, and it is structural, not an implementation gap: the newest
-- snapshot_date has no next day, so its readings can NEVER be despiked -
-- the confirming evidence hasn't been observed yet. The "now" endpoint of
-- every screen is exactly that date. This cleans history well and today not
-- at all; today's reading gets its check tomorrow.
--
-- p_despike defaults TRUE, but sql/checks/set_overview_matches_primitives.sql
-- calls it FALSE: set_return() is a raw two-point comparison, so with
-- despiking on the two legitimately disagree wherever a spike was replaced.
-- Passing FALSE keeps that check testing the aggregation logic, which is
-- what it is for.
--
-- p_days defaults to 30 rather than movers' 7: a set of 200+ cards averages
-- out most of what a week shows.
-- Adding the despike parameters changed this function's signature, and
-- CREATE OR REPLACE does not replace across signatures. It creates a second
-- OVERLOAD, leaving the old 3-argument version behind. Two candidates then
-- make game_set_overview(NULL, 30) ambiguous and it fails to resolve at all.
-- Same guard as item_movers() above, for the same reason. The 7-argument
-- drop is for the return type: is_modern was added as the last column, and
-- CREATE OR REPLACE cannot change a return type.
--
-- is_modern is a column rather than a filter parameter because the screen
-- already receives every set in one response and filters client-side; the
-- rule itself stays in modern_group_ids().
--
-- LAUNCH PRICING (p_include_prerelease, p_include_early), left out of the
-- CHANGE by default: a card whose start-of-window price was a preorder or
-- still settling (launch_noise()) is dropped from the return basket. Value
-- is untouched, since what a set is worth today is a real number either way.
-- The case that prompted it: ME: 30th Celebration read -97.47% over 30 days
-- on a basket of 5 cards, every one of them a pre-release price.
--
-- settles_on is the set's own release + its game's early_days, when that is
-- after the window start, so the screen can say why a new set has no change
-- yet. NULL for settled and undated sets.
DROP FUNCTION IF EXISTS game_set_overview(INTEGER, INTEGER, DATE);
DROP FUNCTION IF EXISTS game_set_overview(INTEGER, INTEGER, DATE, BOOLEAN, NUMERIC, NUMERIC, NUMERIC);
CREATE OR REPLACE FUNCTION game_set_overview(
    p_category_id INTEGER DEFAULT NULL,
    p_days        INTEGER DEFAULT 30,
    p_as_of       DATE    DEFAULT NULL,
    p_despike     BOOLEAN DEFAULT TRUE,
    p_despike_min_usd   NUMERIC DEFAULT 10,
    p_despike_tolerance NUMERIC DEFAULT 1.05,
    p_despike_deviation NUMERIC DEFAULT 1.25,
    p_include_prerelease BOOLEAN DEFAULT FALSE,
    p_include_early      BOOLEAN DEFAULT FALSE
) RETURNS TABLE (
    group_id        INTEGER,
    set_name        TEXT,
    game            TEXT,
    published_on    DATE,
    n_priced        INTEGER,
    total_value     NUMERIC,
    n_common        INTEGER,
    total_start     NUMERIC,
    total_end       NUMERIC,
    pct_return      NUMERIC,
    top_product_id  INTEGER,
    top_name        TEXT,
    top_sub_type    TEXT,
    top_delta       NUMERIC,
    top_share       NUMERIC,
    top_url         TEXT,
    n_despiked      INTEGER,
    despike_usd     NUMERIC,
    start_date      DATE,
    end_date        DATE,
    is_modern       BOOLEAN,
    settles_on      DATE
) AS $$
DECLARE
    v_end   DATE;
    v_start DATE;
BEGIN
    -- Resolved to scalars before the query, not computed in a CTE. Same
    -- reason as item_movers(): from a sub-select the planner can't fold
    -- these into constants, misses idx_snapshots_date and aggregates far
    -- more of the 41M-row table than needed.
    v_end   := COALESCE(p_as_of, (SELECT MAX(ps.snapshot_date) FROM price_snapshots ps));
    v_start := v_end - GREATEST(COALESCE(p_days, 30), 1);

    RETURN QUERY
    WITH anchors AS (
        SELECT v_start AS d, 's'::TEXT AS which
        UNION ALL
        SELECT v_end, 'e'::TEXT
    ),
    -- Six days rather than two: each endpoint needs its neighbours to be
    -- despikeable. Still an indexed range scan.
    kept AS (
        SELECT p.group_id AS gid, ps.product_id AS pid, ps.sub_type_name AS stn,
               ps.snapshot_date AS sd, ps.market_price AS price
        FROM price_snapshots ps
        JOIN products p ON p.product_id = ps.product_id
        WHERE (ps.snapshot_date BETWEEN v_start - 1 AND v_start + 1
               OR ps.snapshot_date BETWEEN v_end - 1 AND v_end + 1)
          AND ps.market_price IS NOT NULL
          AND p.is_sealed = FALSE
          AND (p_category_id IS NULL OR p.category_id = p_category_id)
    ),
    -- (product, sub_type, date) is the primary key, so MAX() here is just
    -- "the one row", not an aggregate over several.
    resolved AS (
        SELECT a.which, k.gid, k.pid, k.stn,
               MAX(k.price) FILTER (WHERE k.sd = a.d)     AS cur,
               MAX(k.price) FILTER (WHERE k.sd = a.d - 1) AS prv,
               MAX(k.price) FILTER (WHERE k.sd = a.d + 1) AS nxt
        FROM anchors a
        JOIN kept k ON k.sd BETWEEN a.d - 1 AND a.d + 1
        GROUP BY 1, 2, 3, 4
    ),
    flagged AS (
        SELECT r.*,
               (p_despike
                AND r.cur IS NOT NULL AND r.prv IS NOT NULL AND r.nxt IS NOT NULL
                AND r.prv > 0 AND r.nxt > 0 AND r.cur > 0
                AND GREATEST(r.prv, r.nxt) / LEAST(r.prv, r.nxt) <= p_despike_tolerance
                AND GREATEST(r.cur, (r.prv + r.nxt) / 2)
                    / NULLIF(LEAST(r.cur, (r.prv + r.nxt) / 2), 0) >= p_despike_deviation
                AND ABS(r.cur - (r.prv + r.nxt) / 2) >= p_despike_min_usd
               ) AS is_spike
        FROM resolved r
        WHERE r.cur IS NOT NULL
    ),
    -- median-of-3 as max(min(a,b), min(max(a,b), c)): no sort, no
    -- percentile_cont, and correct for any ordering of the three.
    priced AS (
        SELECT f.which, f.gid, f.pid, f.stn, f.is_spike,
               CASE WHEN f.is_spike
                    THEN GREATEST(LEAST(f.prv, f.cur), LEAST(GREATEST(f.prv, f.cur), f.nxt))
                    ELSE f.cur END AS price,
               CASE WHEN f.is_spike
                    THEN ABS(f.cur - GREATEST(LEAST(f.prv, f.cur),
                                              LEAST(GREATEST(f.prv, f.cur), f.nxt)))
                    ELSE 0 END AS adjustment
        FROM flagged f
    ),
    now_rows  AS (SELECT * FROM priced WHERE which = 'e'),
    then_rows AS (SELECT * FROM priced WHERE which = 's'),
    -- set_value(): every single priced on the end date.
    val AS (
        SELECT nr.gid, COUNT(*)::INTEGER AS n_priced, SUM(nr.price) AS total_value
        FROM now_rows nr
        GROUP BY 1
    ),
    -- Counted across BOTH endpoints, so a set says how many suspect
    -- readings went into its numbers at all, not just the recent end.
    spikes AS (
        SELECT pd.gid, COUNT(*)::INTEGER AS n_despiked, SUM(pd.adjustment) AS despike_usd
        FROM priced pd WHERE pd.is_spike
        GROUP BY 1
    ),
    -- set_return()'s basket: priced on both days, less launch pricing at the
    -- start unless asked for (see the header).
    moves AS (
        SELECT nr.gid, nr.pid, nr.stn,
               tr.price AS start_price, nr.price AS end_price,
               (nr.price - tr.price) AS delta
        FROM then_rows tr
        JOIN now_rows nr ON nr.pid = tr.pid AND nr.stn = tr.stn
        WHERE (tr.pid, tr.stn) NOT IN (
            SELECT ln.product_id, ln.sub_type_name
            FROM launch_noise(v_start, p_include_prerelease, p_include_early) ln)
    ),
    ret AS (
        SELECT m.gid, COUNT(*)::INTEGER AS n_common,
               SUM(m.start_price) AS total_start,
               SUM(m.end_price) AS total_end,
               SUM(ABS(m.delta)) AS gross
        FROM moves m
        GROUP BY 1
    ),
    -- Biggest single contributor per set, by absolute move. DISTINCT ON is
    -- one ordered pass, no window function over every row of every set.
    top AS (
        SELECT DISTINCT ON (m.gid) m.gid, m.pid, m.stn, m.delta
        FROM moves m
        ORDER BY m.gid, ABS(m.delta) DESC, m.pid
    )
    SELECT
        g.group_id,
        g.name,
        c.name,
        g.published_on,
        val.n_priced,
        ROUND(val.total_value, 2),
        COALESCE(ret.n_common, 0),
        ROUND(ret.total_start, 2),
        ROUND(ret.total_end, 2),
        CASE WHEN ret.total_start > 0
             THEN ROUND((ret.total_end - ret.total_start) / ret.total_start * 100, 2)
             ELSE NULL END,
        top.pid,
        pr.name,
        top.stn,
        ROUND(top.delta, 2),
        CASE WHEN ret.gross > 0
             THEN ROUND(ABS(top.delta) / ret.gross * 100, 1)
             ELSE NULL END,
        pr.url,
        COALESCE(spikes.n_despiked, 0),
        ROUND(COALESCE(spikes.despike_usd, 0), 2),
        v_start,
        v_end,
        g.group_id IN (SELECT mg.group_id FROM modern_group_ids() mg),
        CASE WHEN g.published_on + t.early_days > v_start
             THEN g.published_on + t.early_days END
    FROM groups g
    JOIN categories c ON c.category_id = g.category_id
    LEFT JOIN tracked_categories t ON t.category_id = g.category_id
    -- Inner join on val: a set with nothing priced on the end date has no
    -- value and no return, so it is absent rather than a row of nulls.
    JOIN val ON val.gid = g.group_id
    LEFT JOIN ret ON ret.gid = g.group_id
    LEFT JOIN top ON top.gid = g.group_id
    LEFT JOIN products pr ON pr.product_id = top.pid
    LEFT JOIN spikes ON spikes.gid = g.group_id
    WHERE (p_category_id IS NULL OR g.category_id = p_category_id)
    ORDER BY g.published_on DESC NULLS LAST, g.name;
END;
$$ LANGUAGE plpgsql STABLE;


-- Is price data still arriving, per tracked game?
--
-- Once, the scheduled pull failed every hour for five days and nothing
-- noticed, because a job that cannot reach its own repository exits without
-- writing anything and an empty log is indistinguishable from a quiet
-- success. The answer had to come from the data rather than an exit status.
--
-- One definition, two callers: /api/health and sql/checks/data_freshness.sql.
-- A copy of this query in each caller would be the exact divergence
-- "handlers call SQL functions" exists to prevent.
--
-- The SHAPE is load-bearing. Grouping price_snapshots by category aggregates
-- all 42M rows and measured 22.5 SECONDS, on an endpoint the front end calls
-- on every page load. Bounding the lookup to the last four days reads
-- idx_snapshots_date instead and takes 250ms - the same trick item_movers()
-- documents. The unbounded fallback runs ONLY when the narrow window found
-- nothing, i.e. only when data really has stopped, which is exactly when a
-- slower answer is the one worth having. Four days rather than two so the
-- staleness threshold has margin either side and the number reported near
-- the boundary is still exact.
--
-- Per category, not a global MAX(): one game stopping while another keeps
-- arriving is precisely what a single maximum hides.
CREATE OR REPLACE FUNCTION data_freshness()
RETURNS TABLE (
    category_id   INTEGER,
    game          TEXT,
    newest        DATE,
    days_stale    INTEGER
) AS $$
    SELECT c.category_id, c.name, f.newest,
           (CURRENT_DATE - COALESCE(f.newest, DATE '1970-01-01'))::INTEGER
      FROM tracked_categories tc
      JOIN categories c ON c.category_id = tc.category_id
      CROSS JOIN LATERAL (
          SELECT COALESCE(
              (SELECT MAX(ps.snapshot_date)
                 FROM price_snapshots ps
                 JOIN products p ON p.product_id = ps.product_id
                WHERE p.category_id = c.category_id
                  AND ps.snapshot_date >= CURRENT_DATE - 4),
              (SELECT MAX(ps.snapshot_date)
                 FROM price_snapshots ps
                 JOIN products p ON p.product_id = ps.product_id
                WHERE p.category_id = c.category_id)
          ) AS newest
      ) f
     ORDER BY c.name;
$$ LANGUAGE sql STABLE;


