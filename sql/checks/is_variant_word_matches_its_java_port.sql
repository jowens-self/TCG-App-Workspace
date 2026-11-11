-- Does is_variant_word() still behave like the Java port it must mirror?
--
-- ImportCardImages.isVariantWord() decides which products get an image, and
-- is_variant_word() decides which get a label. CLAUDE.md calls them
-- "change both or neither" because a disagreement means a row's picture and
-- its caption were chosen by different rules.
--
-- The Java side trims its argument internally. The SQL side did not until
-- 2026-08-20, so the two matched only while every caller remembered to
-- btrim() first, and variant_label() does, which is why nothing was ever
-- visibly wrong. Both bugs these rules exist to prevent return the moment a
-- caller forgets: ' Z ' clears the length floor that keeps all 26
-- "Unown [A]".."[Z]" from reading as variants, and ' XY73 ' slips past the
-- card-code test that keeps "Latios EX (XY72)" from doing the same.
-- Measured before the fix: 81 of 2,547 real tokens flipped verdict when
-- padded with a single space either side.
--
-- This cannot call the Java directly, so it checks the property that makes
-- the two equivalent (the SQL function is insensitive to surrounding
-- whitespace), plus the two documented bug shapes by name.
--
-- Run it after touching either implementation:
--   docker compose exec -T db psql -U cardtracker -d cardtracker \
--     -f - < sql/checks/is_variant_word_matches_its_java_port.sql
--
-- Every column must read `t`.
WITH toks AS (
  SELECT btrim(COALESCE(substring(name FROM '\[([^\]]+)\]\s*$'), '')) AS t
  FROM products
  UNION
  SELECT btrim(COALESCE(substring(name FROM '\(([^)]*)\)\s*$'), ''))
  FROM products
),
real AS (SELECT t FROM toks WHERE t <> '')
SELECT
  -- The corpus is non-empty, so a pass below means something was checked.
  (SELECT COUNT(*) FROM real) > 2000
      AS corpus_is_populated,

  -- Padding a token must never change the answer. This is the whole
  -- equivalence with the Java port, stated as a property.
  (SELECT bool_and(is_variant_word(t) = is_variant_word(' ' || t || ' '))
     FROM real)
      AS verdict_is_whitespace_insensitive,

  -- Tabs and newlines are whitespace to Java's String.trim() too.
  (SELECT bool_and(is_variant_word(t) = is_variant_word(E'\t' || t || E'\n'))
     FROM real)
      AS verdict_ignores_tabs_and_newlines,

  -- The Unown bug: a single letter is not a printing variant, padded or not.
  NOT is_variant_word(' Z ') AND NOT is_variant_word('Z')
      AS padded_single_letter_is_not_a_variant,

  -- The card-code bug: "XY72"-shaped tokens are card numbers, padded or not.
  NOT is_variant_word(' XY73 ') AND NOT is_variant_word('XY73')
      AS padded_card_code_is_not_a_variant,

  -- A genuine qualifier still reads as one from either side of the change.
  is_variant_word('Cracked Ice Holo') AND is_variant_word('  Cosmos Holo  ')
      AS real_qualifiers_still_pass,

  -- Null and empty stay false rather than erroring.
  NOT COALESCE(is_variant_word(NULL), FALSE) AND NOT is_variant_word('   ')
      AS null_and_blank_are_not_variants;
