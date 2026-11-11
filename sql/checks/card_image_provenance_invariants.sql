-- Does card_image_provenance() still hold its own definition?
--
-- The function decides whose artwork a card is showing by asking who else
-- claims the same pokemontcg.io card. The
-- verdict is a property of the GROUP sharing a source_id, not of the row,
-- so the ways it can go wrong are structural: a row escaping the
-- classification entirely, a base_art row with nobody to have borrowed
-- from, or an 'unknown' group that actually does contain a plain listing
-- and should therefore have resolved.
--
-- Run it after touching the function:
--   docker compose exec -T db psql -U cardtracker -d cardtracker \
--     -f - < sql/checks/card_image_provenance_invariants.sql
--
-- Every row must read `t`. These are invariants, not measurements, so the
-- counts in the function's comment will drift as images are imported, but
-- none of these should ever go false.
WITH ok AS (
    SELECT ci.product_id, ci.source_id, ci.stripped_qualifier
    FROM card_images ci
    WHERE ci.status = 'ok' AND ci.source_id IS NOT NULL
),
prov AS (SELECT * FROM card_image_provenance()),
grp AS (
    SELECT source_id, COUNT(*) FILTER (WHERE stripped_qualifier IS NULL) AS unqualified
    FROM ok GROUP BY source_id
)
SELECT
    -- Every downloaded image is classified, exactly once. A row that falls
    -- through the CASE would silently vanish rather than fail.
    ((SELECT COUNT(*) FROM prov) = (SELECT COUNT(*) FROM ok))
        AS every_ok_row_classified,
    ((SELECT COUNT(DISTINCT product_id) FROM prov) = (SELECT COUNT(*) FROM prov))
        AS one_verdict_per_product,
    ((SELECT COUNT(*) FROM prov WHERE provenance NOT IN ('own_art','base_art','unknown')) = 0)
        AS no_unexpected_verdict,

    -- base_art means the picture is not this printing. An owner is named
    -- only when some product actually holds it. A 'treatment' qualifier
    -- (a stamp or foil the catalog art never carries) can be a sole
    -- claimant with nobody to have borrowed from, e.g. Bronzong - SWSH091
    -- (Prerelease). So the invariant is "owner named, OR treatment".
    ((SELECT COUNT(*) FROM prov
       WHERE provenance='base_art' AND owner_product_id IS NULL
         AND qualifier_class IS DISTINCT FROM 'treatment') = 0)
        AS base_art_names_owner_unless_treatment,
    ((SELECT COUNT(*) FROM prov WHERE provenance='base_art' AND owner_product_id = product_id) = 0)
        AS owner_is_never_the_borrower,

    -- An unqualified product is by definition showing the printing it is
    -- named for, so it can never be the borrower.
    ((SELECT COUNT(*) FROM prov WHERE provenance='base_art' AND stripped_qualifier IS NULL) = 0)
        AS unqualified_rows_never_base_art,

    -- 'unknown' is reserved for groups where NO plain listing exists. If one
    -- does, the group should have resolved to own_art/base_art instead.
    ((SELECT COUNT(*) FROM prov p JOIN grp g USING (source_id)
      WHERE p.provenance='unknown' AND g.unqualified > 0) = 0)
        AS unknown_groups_have_no_plain_listing,

    -- A sole claimant has nobody to borrow from, so it is own_art, unless
    -- the vocabulary says the qualifier is a treatment. Sole-claimancy only
    -- proves no other product took the picture, never that the picture
    -- matches the physical card; that blind spot is why the vocabulary
    -- table exists.
    ((SELECT COUNT(*) FROM prov
       WHERE claimants = 1 AND provenance <> 'own_art'
         AND qualifier_class IS DISTINCT FROM 'treatment') = 0)
        AS sole_claimants_own_art_unless_treatment,

    -- A treatment qualifier is never own_art: the catalog art cannot carry
    -- a stamp or foil pattern the catalog does not have a card for.
    ((SELECT COUNT(*) FROM prov WHERE qualifier_class='treatment' AND provenance='own_art') = 0)
        AS treatments_are_never_own_art,

    -- 'unknown' means nothing settles the group: neither a plain listing
    -- nor a classified claimant.
    ((SELECT COUNT(*) FROM prov WHERE provenance='unknown' AND qualifier_class IS NOT NULL) = 0)
        AS unknown_rows_are_unclassified,

    -- The vocabulary is joined on normalize_qualifier(), so every stored
    -- qualifier must already BE normalised. Two rows differing only by a
    -- hyphen would both match one image and duplicate it in the output -
    -- which every_ok_row_classified would then catch, but by then the cause
    -- is three joins away. This says it directly.
    ((SELECT COUNT(*) FROM image_qualifier_class
       WHERE qualifier <> normalize_qualifier(qualifier)) = 0)
        AS vocabulary_is_stored_normalised;
