-- CardTracker schema (v1)
--
-- Design rules baked in here:
--   1. price_snapshots is APPEND-ONLY. We never UPDATE a price row. The whole
--      point of this project is the history; overwriting is the one mistake
--      that can't be undone.
--   2. A price is keyed by (product_id, sub_type_name, snapshot_date), NOT by
--      product_id alone. Pokemon cards exist as Normal / Holofoil / Reverse
--      Holofoil of the same product and they trade at different prices.
--   3. product_id survives everywhere, so we can always build a link back to
--      the card's page on TCGplayer.
--
-- This file defines a FRESH database and runs only on an empty data directory,
-- via docker-entrypoint-initdb.d. The live database was initialised 2026-08-12
-- and will never run it again. Any change after that point must ALSO be written
-- as a file in sql/migrations/ and applied with sql/migrate.sh, or a new
-- container and the running one silently diverge.

CREATE TABLE IF NOT EXISTS categories (
    category_id   INTEGER PRIMARY KEY,
    name          TEXT NOT NULL,
    modified_on   TIMESTAMPTZ
);

-- Which tcgcsv categories App/BackfillArchive actually pull. Data, not
-- code, on purpose: adding a card game is "INSERT a row," not a code
-- change + rebuild + redeploy. Seeded with Pokemon (3) only, so nothing
-- changes in behavior until another row is added deliberately.
--
-- modern_since: sets released on or after it count as "modern" for that game;
-- NULL means every set does. Why Pokemon's is 2019-11-15: migration 003.
-- early_days: how long after release a card's prices are still settling
-- ("early pricing"). Why 56: migration 004.
CREATE TABLE IF NOT EXISTS tracked_categories (
    category_id  INTEGER PRIMARY KEY,
    name         TEXT NOT NULL,
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    modern_since DATE,
    early_days   INTEGER NOT NULL DEFAULT 56 CHECK (early_days >= 0)
);

INSERT INTO tracked_categories (category_id, name, modern_since)
VALUES (3, 'Pokemon', DATE '2019-11-15')
ON CONFLICT (category_id) DO NOTHING;

-- Is a printing qualifier part of the card's IDENTITY, or a TREATMENT
-- applied over a base printing? This is a vocabulary judgment reserved for
-- the owner, and one that can't be derived: no property of our
-- data distinguishes them, because it is knowledge about how Pokemon cards
-- are printed.
--
--   'identity'  the qualifier names a genuinely different card that
--               pokemontcg.io lists in its own right, usually spelled its
--               own way. Delta Species was an attribute some cards carried
--               during those sets (owner, 2026-08-19), and the catalog has
--               them as "Aerodactyl d". The catalog art IS this card.
--   'treatment' the qualifier is a foil pattern or a stamp applied over a
--               base printing the catalog holds unstamped. Prerelease
--               cards almost always carry a stamp (owner, 2026-08-19), so
--               the catalog picture is missing something the physical card
--               has.
--
-- Kept as DATA, not a CASE in a function, for the same reason
-- tracked_categories is: adding a classification is an INSERT, and the
-- owner can add one without a code change. Deliberately incomplete, since an
-- unclassified qualifier falls back to the structural test in
-- card_image_provenance() rather than being guessed at.
--
-- NOTE the hedge on 'treatment': "almost always" is the owner's own
-- wording and is not flattened into "always" here. A treatment verdict
-- means the picture is probably not this exact printing, not certainly.
CREATE TABLE IF NOT EXISTS image_qualifier_class (
    qualifier  TEXT PRIMARY KEY,
    class      TEXT NOT NULL CHECK (class IN ('identity','treatment')),
    note       TEXT,
    decided_on DATE NOT NULL DEFAULT CURRENT_DATE
);

-- Seeded with the owner's calls to date. Qualifiers are stored NORMALISED
-- (see normalize_qualifier() in functions.sql): lowercase, hyphens and
-- underscores as spaces, runs of space collapsed. That matters structurally
-- because image_qualifier_class is joined on the normalised form: two rows
-- differing only in spelling would let one image match twice and duplicate
-- itself in the output. Guarded in
-- sql/checks/card_image_provenance_invariants.sql.
--
-- The 44-row tournament/placement block is a single family call by the
-- owner, 2026-08-19, verbatim: "As far as I know these are all in that
-- category". Candidates were found with
-- /finalist|champion|^[0-9]+(st|nd|rd|th) place$/ over the unclassified
-- vocabulary and all reviewed before insertion. The words are seeded
-- explicitly rather than the pattern being evaluated at runtime, so a new
-- qualifier that happens to match is left 'unknown' until someone looks at
-- it, not auto-classified.
--
-- The 77 player names are the World Championship Decks family, a second
-- one-call classification (owner, 2026-08-19, having checked: "a distinctive
-- special edition of that card, essentially the same type of thing as a pre
-- release card"). Selected structurally rather than by pattern: a qualifier
-- qualifies only if every card carrying it sits in the World Championship
-- Decks group, because no string rule reliably recognises a person's name,
-- and guessing at the shape of the string is not the same as knowing what
-- the card is.
INSERT INTO image_qualifier_class (qualifier, class, note, decided_on) VALUES
    ('delta species', 'identity',
     'An attribute some cards carried during those sets; catalog lists them separately as "Aerodactyl d". Owner, 2026-08-19.',
     DATE '2026-08-19'),
    ('prime', 'identity',
     'Part of the label, an attribute of the card rather than a finish over a base printing. Owner, 2026-08-19. Confirmed on disk: Gengar (Prime) Triumphant 94/102 resolves to hgss4-94, which carries the Prime frame.',
     DATE '2026-08-19'),
    ('special', 'identity',
     'A nicer/alternate version than the base rather than a treatment of it. Owner, 2026-08-19. Confirmed on disk: Darkness Energy (Special) Call of Legends 86/95 resolves to col1-86, which is labelled "Special Energy Card" on the card face.',
     DATE '2026-08-19'),
    ('team plasma', 'identity',
     'Card identity, not a stamp: the catalog lists these as their own cards. Owner, 2026-08-19.',
     DATE '2026-08-19'),
    ('1st place', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2005 world championships', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2014 champion', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2014 finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2014 quarter finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2014 semi finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2015 champion', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2015 finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2015 quarter finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2015 semi finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2016 champion', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2016 finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2016 quarter finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2016 semi finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('2nd place', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('3rd place', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('4th place', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('akira miyazaki', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('alejandro ng guzman', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('andre chiasson', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('andrew estrada', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('arena cup championships', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('asia championship series 23 24', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('champion', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('championship promo', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('championship series', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('chase moloney', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('chris fulop', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('christopher kan', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('city championships', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('clement lamberton', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('cody walinski', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('cosmos holo', 'treatment',
     'A type of foil on the card, so the catalog art is the non-cosmos printing. Owner, 2026-08-19.',
     DATE '2026-08-19'),
    ('curran hill', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('david cohen', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('diego cassiraga', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('dylan lefavour', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('europe championships', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('europe international championship', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('evan pavelski', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('evan pavelski gold signature', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('fernando cifuentes', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('fernando cifuentes gold signature', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('finalist', 'treatment',
     'A stamp. Owner, 2026-08-19.',
     DATE '2026-08-19'),
    ('gabriel fernandez', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('gustavo wada', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('haruki miyamoto', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('haruto kobayashi', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('henry brand', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('hiroki yano', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('ian whiton', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('igor costa', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jacob van wagner', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jason klaczynski', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jason martinez', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jeremy maron', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jeremy moran', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jeremy scharff kim', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jesper eriksen', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jesse parker', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jesse parker gold signature', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jimmy ballard', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('jun hasebe', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('kabu fukase', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('kaya lichtleitner', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('kevin nguyen', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('latin america championships', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('magnus pedersen', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('michael gonzalez', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('michael pramawat', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('michikazu tsuda', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('miska saari', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('mychael bryan', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('naohito inoue', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('naoko suzuki', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('naoto suzuki', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('national championships', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('north america championships', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('north america international championship', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('oceania championships', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('ondrej skubal', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('patrick martinez', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('paul atanassov', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('pedro eugenio torres', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('prerelease', 'treatment',
     'Prerelease cards almost always carry a stamp; the catalog art is the unstamped printing. Owner, 2026-08-19. Note "almost always", not universal.',
     DATE '2026-08-19'),
    ('quarter finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('reed weichler', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('regional championship', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('regional championships', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('rikuto ohashi', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('robin schulz', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('ross cawthon', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('rowan stavenow', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('sakuya o.', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('sakuya o. gold signature', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('sebastian lashmet', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('semi finalist', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('shao tong yen', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('shintaro ito', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('shunto sadahiro', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('shuto itagaki', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('staff', 'treatment',
     'Behaves just like prerelease, a stamp over a base printing, so the catalog art is missing it. Owner, 2026-08-19.',
     DATE '2026-08-19'),
    ('state championship', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('state championship promo', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('state championships', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('stephen silvestro', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('takashi yoneda', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('tom roos', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('tord reklev', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('trent orndorff', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('tristan robinson', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('tsubasa nakamura', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('tsuguyoshi yamato', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('vance kelley', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('winner', 'treatment',
     'A stamp, like prerelease and staff. Owner, 2026-08-19.',
     DATE '2026-08-19'),
    ('world championships 2017', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('world championships 2018', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('world championships 2019', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('world championships 2022', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('world championships 2023', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('world championships 2024', 'treatment',
     'Tournament/placement stamp on a promo. Family call, owner 2026-08-19. See schema comment.',
     DATE '2026-08-19'),
    ('yugo sato', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('yuka furusawa', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('yuta komatsuda', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19'),
    ('zachary bokhari', 'treatment',
     'World Championship deck printing, a distinctive special edition of the card and essentially the same kind of thing as a prerelease. Owner, 2026-08-19, having checked.',
     DATE '2026-08-19')
ON CONFLICT (qualifier) DO NOTHING;

CREATE TABLE IF NOT EXISTS groups (
    group_id        INTEGER PRIMARY KEY,
    category_id     INTEGER NOT NULL REFERENCES categories(category_id),
    name            TEXT NOT NULL,
    abbreviation    TEXT,
    is_supplemental BOOLEAN,
    published_on    DATE,          -- release date; NULL when the catalog has none (migration 002)
    modified_on     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_groups_category ON groups(category_id);

CREATE TABLE IF NOT EXISTS products (
    product_id    INTEGER PRIMARY KEY,
    group_id      INTEGER NOT NULL REFERENCES groups(group_id),
    category_id   INTEGER NOT NULL REFERENCES categories(category_id),
    name          TEXT NOT NULL,
    clean_name    TEXT,
    image_url     TEXT,
    -- The canonical TCGplayer product URL. Kept as a column rather than
    -- derived at render time so a link never depends on us guessing a
    -- URL format that may change.
    url           TEXT,
    -- Sealed boxes/packs vs singles. Flagged at ingest, not reverse-engineered
    -- from the name later.
    is_sealed     BOOLEAN NOT NULL DEFAULT FALSE,
    extended_data JSONB,
    modified_on   TIMESTAMPTZ,
    first_seen_on DATE NOT NULL DEFAULT CURRENT_DATE
);

CREATE INDEX IF NOT EXISTS idx_products_group ON products(group_id);

-- Name search (item_search() in functions.sql) is a leading-wildcard ILIKE,
-- which no btree index can serve. It was a full seq scan of every product
-- on every keystroke. Trigram GIN index takes 'charizard' across ~38.5k
-- products from ~30ms to ~1.3ms, and unlike the seq scan it doesn't
-- degrade as more games are added to tracked_categories.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);

-- The important one.
CREATE TABLE IF NOT EXISTS price_snapshots (
    product_id       INTEGER NOT NULL,
    sub_type_name    TEXT    NOT NULL,
    snapshot_date    DATE    NOT NULL,
    market_price     NUMERIC(12,2),
    low_price        NUMERIC(12,2),
    mid_price        NUMERIC(12,2),
    -- Stored for completeness but DO NOT compute on this. Sellers "price park"
    -- (list at absurd values so nothing sells), which makes high_price noise.
    high_price       NUMERIC(12,2),
    direct_low_price NUMERIC(12,2),
    ingested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, sub_type_name, snapshot_date)
);

CREATE INDEX IF NOT EXISTS idx_snapshots_date ON price_snapshots(snapshot_date);
CREATE INDEX IF NOT EXISTS idx_snapshots_product ON price_snapshots(product_id, sub_type_name, snapshot_date DESC);

-- When each card (product + variant) was first priced, kept by the trigger
-- below. Derived from price_snapshots, never a source of truth: it exists so
-- telling pre-release and early prices apart costs a join rather than an
-- index seek per card (4.3s per screen, measured). Why, and why a trigger:
-- migration 004.
CREATE TABLE IF NOT EXISTS card_first_priced (
    product_id    INTEGER NOT NULL,
    sub_type_name TEXT    NOT NULL,
    first_date    DATE    NOT NULL,
    PRIMARY KEY (product_id, sub_type_name)
);

-- Statement-level, so a COPY of a whole day is one upsert. Only ever moves a
-- date earlier, so replaying old days is harmless. Writes nothing to
-- price_snapshots.
CREATE OR REPLACE FUNCTION note_first_priced() RETURNS trigger AS $$
BEGIN
    INSERT INTO card_first_priced (product_id, sub_type_name, first_date)
    SELECT i.product_id, i.sub_type_name, MIN(i.snapshot_date)
    FROM inserted i
    GROUP BY 1, 2
    ON CONFLICT (product_id, sub_type_name) DO UPDATE
        SET first_date = EXCLUDED.first_date
        WHERE EXCLUDED.first_date < card_first_priced.first_date;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER price_snapshots_first_priced
    AFTER INSERT ON price_snapshots
    REFERENCING NEW TABLE AS inserted
    FOR EACH STATEMENT EXECUTE FUNCTION note_first_priced();

-- Accounts. Identity only, no credentials: how someone proves who they are is
-- a separate decision (see sql/migrations/001_users.sql). user_id 1 is the
-- local owner, so code can reference a real user before any login exists.
CREATE TABLE IF NOT EXISTS users (
    user_id      BIGSERIAL PRIMARY KEY,
    -- Lower-cased at the boundary AND enforced here: UNIQUE alone would let
    -- "Jo@x.com" and "jo@x.com" both in, because TEXT compares case-sensitively.
    email        TEXT NOT NULL UNIQUE CHECK (email = lower(email)),
    display_name TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ
);

INSERT INTO users (user_id, email, display_name)
VALUES (1, 'owner@localhost', 'Owner')
ON CONFLICT (email) DO NOTHING;

SELECT setval(pg_get_serial_sequence('users', 'user_id'),
              GREATEST((SELECT MAX(user_id) FROM users), 1));

-- One row of preferences per user, created on first save; no row means the
-- defaults. Updated in place: preferences are not history. Game rules are
-- per game in tracked_categories, not here. Why: migration 005.
CREATE TABLE IF NOT EXISTS user_settings (
    user_id            BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    include_prerelease BOOLEAN NOT NULL DEFAULT FALSE,
    include_early      BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Products deliberately excluded from ingestion and all calculations, e.g.
-- digital code-card redemptions, which behave nothing like a physical card
-- and skew set-level return calculations.
-- Kept as its own table, independent of `products`, because BackfillArchive
-- only ever sees a bare product_id from tcgcsv's archives, never a name,
-- so it needs somewhere to look up "is this excluded" that still works
-- after the product's row is removed from `products` entirely.
--
-- category_id is recorded (not just implied by joining to `products`, which
-- may already be gone by the time this is read) specifically so an
-- exclusion reason discovered for one game is never mistaken for a
-- universal rule about trading cards in general. Code cards, for example,
-- are a Pokemon quirk, not something to assume any other game also has.
CREATE TABLE IF NOT EXISTS excluded_products (
    product_id    INTEGER PRIMARY KEY,
    category_id   INTEGER NOT NULL,
    name          TEXT NOT NULL,
    reason        TEXT NOT NULL,
    excluded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Card images, sourced from the pokemontcg.io community API (the ONLY image
-- source permitted; see CLAUDE.md "Permitted hosts". TCGplayer's CDN is
-- explicitly off limits).
--
-- Misses are recorded as rows with status='no_match', not left absent. That
-- distinction is the whole point of the table: roughly 40% of our Pokemon
-- singles have no counterpart upstream (World Championship decks, jumbo
-- cards, prize packs, promos, and the newest sets, which lag), and without
-- a negative record every one of those would be re-queried on every import
-- run forever.
--
-- Only Pokemon for now. One Piece has no permitted image source, so its
-- products simply have no rows here.
CREATE TABLE IF NOT EXISTS card_images (
    product_id    INTEGER PRIMARY KEY,
    -- pokemontcg.io card id, e.g. 'base1-4'. Null when status='no_match'.
    source_id     TEXT,
    -- 'ok'       = matched and a thumbnail is on disk
    -- 'no_match' = no upstream card; don't look again
    -- 'failed'   = matched but the download failed; retryable
    status        TEXT NOT NULL,
    -- Relative to the image root (CARDTRACKER_IMAGE_DIR), so moving the
    -- directory doesn't invalidate every row.
    file_path     TEXT,
    bytes         INTEGER,
    -- How the match was made, kept so a bad match can be traced back to the
    -- rule that produced it rather than guessed at.
    match_rule    TEXT,
    -- The printing qualifier that had to be stripped from the product name
    -- before this image would match: "cracked ice holo", "poke ball
    -- pattern", "delta species", or NULL when the name matched as-is.
    --
    -- A FACT about how the match was made, deliberately not a verdict on
    -- whether the artwork is wrong. Some stripped qualifiers mean the image
    -- really is the base printing (foil treatments like Cracked Ice Holo are
    -- applied to existing art); others mean nothing is wrong at all, because
    -- pokemontcg.io just spells the card differently ("Aerodactyl (Delta
    -- Species)" is their "Aerodactyl δ"). Classifying the vocabulary is a
    -- domain call; storing the word lets it be made once in SQL and applied
    -- retroactively without re-running the import.
    --
    -- Decided at match time, where the importer holds both the product and
    -- the card it matched. Deliberately NOT derived later by comparing set
    -- names, which the two catalogs disagree on ("SWSH: Sword & Shield Promo
    -- Cards" vs "SWSH Black Star Promos", ~900 cards a name comparison would
    -- wrongly flag).
    stripped_qualifier TEXT,
    checked_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_card_images_status ON card_images(status);

-- Operational log. Lets you answer "did the pull run, and did it run clean?"
-- without reading application logs.
CREATE TABLE IF NOT EXISTS ingest_runs (
    id             BIGSERIAL PRIMARY KEY,
    source         TEXT NOT NULL,
    snapshot_date  DATE,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    status         TEXT NOT NULL DEFAULT 'running',
    request_count  INTEGER NOT NULL DEFAULT 0,
    rows_written   INTEGER NOT NULL DEFAULT 0,
    notes          TEXT
);

-- hasSuccessfulRun() filters on exactly these three columns, and
-- BackfillArchive calls it once per day in its range, up to about 900 times in
-- one gap-catchup call. Without this, every one of those calls is a full
-- table scan (verified via EXPLAIN before adding this).
CREATE INDEX IF NOT EXISTS idx_ingest_runs_source_date_status
    ON ingest_runs(source, snapshot_date, status);
