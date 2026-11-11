package com.cardtracker.images;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The matcher's string rules, which is where this project's real defects have
 * concentrated.
 *
 * Every case below is a printing that exists in the catalog, not an invented
 * edge case. That matters: the rules were each added because a specific real
 * card broke something, so a test that drifts to synthetic inputs stops
 * defending the thing it was written for.
 */
class ImportCardImagesTest {

    @Nested
    @DisplayName("isVariantWord")
    class IsVariantWord {

        /**
         * The length floor. Without it every one of "Unown [A]".."[Z]" reads
         * as a printing variant, 26 cards labelled with a single letter.
         */
        @ParameterizedTest
        @ValueSource(strings = {"A", "M", "Z"})
        void singleLettersAreNotVariants(String token) {
            assertFalse(ImportCardImages.isVariantWord(token),
                    "Unown [" + token + "] would be labelled a variant");
        }

        /**
         * The card-code test. These all appear in product names as
         * parentheticals and are card NUMBERS, not printings.
         */
        @ParameterizedTest
        @ValueSource(strings = {"1", "2", "147", "XY72", "XY73", "SV66", "H1", "SWSH014", "RC4", "TG12"})
        void cardCodesAreNotVariants(String token) {
            assertFalse(ImportCardImages.isVariantWord(token),
                    token + " is a card number, not a printing");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Cracked Ice Holo", "Cosmos Holo", "Delta Species", "Team Plasma",
                "Prerelease", "Staff", "Professor Magnolia", "Ice"})
        void realQualifiersAreVariants(String token) {
            assertTrue(ImportCardImages.isVariantWord(token));
        }

        /**
         * Trims internally rather than trusting callers to do it. Its SQL
         * mirror relied on caller discipline until 2026-08-20, and 81 of
         * 2,547 real tokens changed verdict when padded, and every one of them
         * re-opening one of the two bugs above.
         */
        @Test
        void trimsItsOwnArgument() {
            assertFalse(ImportCardImages.isVariantWord(" Z "));
            assertFalse(ImportCardImages.isVariantWord("\tXY73\n"));
            assertTrue(ImportCardImages.isVariantWord("  Cosmos Holo  "));
        }

        @Test
        void nullAndBlankAreNotVariants() {
            assertFalse(ImportCardImages.isVariantWord(null));
            assertFalse(ImportCardImages.isVariantWord(""));
            assertFalse(ImportCardImages.isVariantWord("   "));
        }
    }

    @Nested
    @DisplayName("numberDigits")
    class NumberDigits {

        /**
         * An anchored leading-digits regex once returned null for every
         * number not starting with a digit, which left 1,550 of 20,479
         * catalog cards unindexed and their products never even attempting a
         * match. These are the shapes that were being dropped.
         */
        @Test
        void findsDigitsInCodesThatDoNotStartWithOne() {
            assertEquals("1", ImportCardImages.numberDigits("H1"));
            assertEquals("66", ImportCardImages.numberDigits("SV066"));
            assertEquals("14", ImportCardImages.numberDigits("SWSH014"));
            assertEquals("4", ImportCardImages.numberDigits("RC4"));
            assertEquals("12", ImportCardImages.numberDigits("TG12"));
            assertEquals("46", ImportCardImages.numberDigits("GG46"));
        }

        /**
         * Leading zeros are padding, not value, so the printed "004/102"
         * matches a catalog card numbered 4. Dropping them by string rather
         * than parseInt is deliberate: a pathological run of digits would
         * overflow an int, and the numeric value is never wanted.
         */
        @Test
        void treatsLeadingZerosAsPadding() {
            assertEquals("4", ImportCardImages.numberDigits("4"));
            assertEquals("4", ImportCardImages.numberDigits("004"));
            assertEquals("4", ImportCardImages.numberDigits("004/102"));
        }

        /** The denominator is the set size and must never be read as the number. */
        @Test
        void readsTheNumberBeforeTheSlashNotAfter() {
            assertEquals("4", ImportCardImages.numberDigits("4/102"));
            assertEquals("102", ImportCardImages.numberTotal("4/102"));
            assertNull(ImportCardImages.numberTotal("SWSH014"));
        }

        @Test
        void returnsNullWhenThereAreNoDigits() {
            assertNull(ImportCardImages.numberDigits("ABC"));
            assertNull(ImportCardImages.numberDigits(null));
        }
    }

    @Nested
    @DisplayName("numberCode")
    class NumberCode {

        /**
         * The prefix is the whole point: it is what tells Aquapolis' holo
         * "H2" apart from every other card numbered 2, after the printed
         * totals (32 vs 147) have already ruled out the strict key.
         */
        @Test
        void keepsTheAlphabeticPrefix() {
            assertEquals("h2", ImportCardImages.numberCode("H02/H32"));
            assertEquals("bw64", ImportCardImages.numberCode("BW64"));
            assertEquals("swsh39", ImportCardImages.numberCode("SWSH039"));
            assertEquals("sh6", ImportCardImages.numberCode("SH6"));
            assertEquals("hgss20", ImportCardImages.numberCode("HGSS20"));
        }

        /**
         * Null, not the bare digits. Without a prefix this key would be
         * character-for-character the loose key, so a non-null answer would
         * add a tier that re-decides what the loose tier already decides -
         * and would report a different rule name for the same match.
         */
        @Test
        void isNullWhenTheNumberHasNoPrefix() {
            assertNull(ImportCardImages.numberCode("4"));
            assertNull(ImportCardImages.numberCode("004/102"));
            assertNull(ImportCardImages.numberCode("16"));
            assertNull(ImportCardImages.numberCode(null));
            assertNull(ImportCardImages.numberCode("ABC"));
        }

        /** Padding is dropped on this side too, or "H02" misses the catalog's "H2". */
        @Test
        void treatsLeadingZerosAsPadding() {
            assertEquals("h2", ImportCardImages.numberCode("H02"));
            assertEquals("h2", ImportCardImages.numberCode("H2"));
            assertEquals("h10", ImportCardImages.numberCode("H10"));
            assertEquals("h1", ImportCardImages.numberCode("H01"));
        }

        /** The denominator is the subset size, never the number. */
        @Test
        void readsTheNumberBeforeTheSlashNotAfter() {
            assertEquals("h2", ImportCardImages.numberCode("H02/H32"));
            assertEquals("tg12", ImportCardImages.numberCode("TG12/TG30"));
        }

        /**
         * Bounded at five letters so a word can never be read as a prefix.
         * The real prefixes are 1-4 ("H", "GG", "SWSH"); the bound is what
         * stops a malformed number contributing a junk key to the index.
         */
        @Test
        void refusesAPrefixTooLongToBeOne() {
            assertNull(ImportCardImages.numberCode("promotional1"));
        }

        /**
         * Every card this keys is also keyed by numberDigits, which is what
         * makes the tier a narrowing of the loose one rather than a
         * widening. It can turn an ambiguous candidate list into a single
         * hit, and can never introduce a candidate the loose key did not
         * already hold.
         */
        @Test
        void isAlwaysANarrowingOfTheLooseKey() {
            for (String n : new String[]{"H02/H32", "BW64", "SWSH039", "SH6", "HGSS20"}) {
                String code = ImportCardImages.numberCode(n);
                assertNotNull(code, n);
                assertTrue(code.endsWith(ImportCardImages.numberDigits(n)),
                        n + " -> " + code + " must end in its own digits");
            }
        }
    }

    @Nested
    @DisplayName("numberLetters")
    class NumberLetters {

        /**
         * numberDigits() handles numbers whose digits are not at the start. These have
         * no digits to find at all, and were fatal on both sides in exactly
         * the same way: the Unown alphabet in Unseen Forces and the four
         * HGSS-era Alph Lithographs, numbered ONE through FOUR.
         */
        @Test
        void readsANumberThatIsEntirelyNonNumeric() {
            assertEquals("l", ImportCardImages.numberLetters("L/28"));
            assertEquals("one", ImportCardImages.numberLetters("ONE"));
            assertEquals("four", ImportCardImages.numberLetters("FOUR"));
            assertEquals("!", ImportCardImages.numberLetters("!/28"));
            assertEquals("?", ImportCardImages.numberLetters("?/28"));
        }

        /**
         * The subtlety worth a test of its own. TCGplayer writes "N/A" for a
         * card with no number; splitting on "/" would read that as the letter
         * N and hand it any catalog card numbered N. A real letter number
         * reads "L/28", letters over a count, so the denominator has to be
         * digits.
         */
        @Test
        void refusesNotApplicableAsIfItWereTheLetterN() {
            assertNull(ImportCardImages.numberLetters("N/A"));
            assertNull(ImportCardImages.numberLetters("n/a"));
            assertNull(ImportCardImages.numberLetters("L/"));
            assertNull(ImportCardImages.numberLetters("A/B/C"));
        }

        /** Silent whenever numberDigits() can answer, since it owns those. */
        @Test
        void isNullWheneverThereAreDigits() {
            assertNull(ImportCardImages.numberLetters("4"));
            assertNull(ImportCardImages.numberLetters("004/102"));
            assertNull(ImportCardImages.numberLetters("H02/H32"));
            assertNull(ImportCardImages.numberLetters("SWSH039"));
            assertNull(ImportCardImages.numberLetters(null));
        }

        /**
         * The two are exhaustive and disjoint over any non-blank number: every
         * number is answered by exactly one of them, which is what lets the
         * letter index hold precisely the cards the other three cannot see.
         */
        @Test
        void isExactlyTheComplementOfNumberDigits() {
            for (String n : new String[]{"4", "004/102", "H02/H32", "SWSH039",
                                         "L/28", "ONE", "!/28", "TG12/TG30"}) {
                boolean digits = ImportCardImages.numberDigits(n) != null;
                boolean lettersToo = ImportCardImages.numberLetters(n) != null;
                assertNotEquals(digits, lettersToo,
                        n + " must be answered by exactly one of the two");
            }
        }
    }

    @Nested
    @DisplayName("fold / nameKeys")
    class Folding {

        /**
         * The defect this exists for: finish() replaces anything outside
         * [a-z0-9 ] with a space, so the catalog's "Pokémon Catcher" became
         * "pok mon catcher" and could never meet TCGplayer's ASCII spelling.
         * 157 catalog cards carry the accent.
         */
        @Test
        void foldsAccentsSoTheTwoCatalogsCanMeet() {
            assertEquals("poke ball",
                    ImportCardImages.normName(ImportCardImages.fold("Pok\u00e9 Ball")));
            assertEquals("pokemon catcher",
                    ImportCardImages.normName(ImportCardImages.fold("Pok\u00e9mon Catcher")));
            assertEquals("pokegear 3 0",
                    ImportCardImages.normName(ImportCardImages.fold("Pok\u00e9gear 3.0")));
        }

        /**
         * These are TCGplayer's own spellings, read off the product table -
         * "Nidoran F", "Mew Star", "Diancie Prism Star", not invented ones.
         */
        @Test
        void spellsSymbolsTheWayTcgplayerDoes() {
            assertEquals("nidoran f",
                    ImportCardImages.normName(ImportCardImages.fold("Nidoran \u2640")));
            assertEquals("nidoran m",
                    ImportCardImages.normName(ImportCardImages.fold("Nidoran \u2642")));
            assertEquals("mew star",
                    ImportCardImages.normName(ImportCardImages.fold("Mew \u2605")));
            assertEquals("diancie prism star",
                    ImportCardImages.normName(ImportCardImages.fold("Diancie \u25c7")));
        }

        /**
         * The one that guards the qualifier classes. Delta species is card IDENTITY the
         * owner classified through stripped_qualifier: "Aerodactyl δ" is
         * TCGplayer's "Aerodactyl (Delta Species)", matched at tier 2 with
         * `delta species` recorded and classified `identity`. Folding δ to a
         * word would resolve those at tier 1 instead and silently rewrite
         * settled verdicts. NFD leaves it alone because it carries no
         * combining mark, which makes this a property to pin rather than code.
         */
        @Test
        void leavesDeltaAndTheGreekLettersAlone() {
            for (String greek : new String[]{"\u03b4", "\u03b1", "\u03b2", "\u03b3"}) {
                assertEquals("Aerodactyl " + greek,
                        ImportCardImages.fold("Aerodactyl " + greek),
                        "fold() must not touch " + greek);
            }
            // ...and so the normalised name is unchanged either way.
            assertEquals(ImportCardImages.normName("Aerodactyl \u03b4"),
                    ImportCardImages.normName(ImportCardImages.fold("Aerodactyl \u03b4")));
        }

        /**
         * TCGplayer is not consistent with itself: one card is "Nidoran F",
         * another "Nidoran (F)", and the catalog writes "Nidoran ♀" for both.
         * The bracketed form normalises to a bare "nidoran", and a one-letter
         * token is not a printing qualifier, so indexing only the folded
         * spelling would win one and lose the other. Measured at the time:
         * folding alone +332/-1, keying both +331/-0.
         */
        @Test
        void keysACatalogCardUnderBothSpellings() {
            var keys = ImportCardImages.nameKeys("Nidoran \u2640");
            assertTrue(keys.contains("nidoran"), "unfolded spelling: " + keys);
            assertTrue(keys.contains("nidoran f"), "folded spelling: " + keys);
            assertEquals(2, keys.size(), keys.toString());
        }

        /**
         * A card with nothing to fold must be indexed ONCE, or the size()==1
         * ambiguity checks in loose() and numCoded() stop meaning what they
         * say and every plain card becomes its own collision.
         */
        @Test
        void keysAnOrdinaryCardExactlyOnce() {
            assertEquals(1, ImportCardImages.nameKeys("Pikachu").size());
            assertEquals(1, ImportCardImages.nameKeys("Charizard").size());
            // delta collapses to the same key both ways, so it is one entry too
            assertEquals(1, ImportCardImages.nameKeys("Aerodactyl \u03b4").size());
        }

        @Test
        void handlesNull() {
            assertNull(ImportCardImages.fold(null));
            assertTrue(ImportCardImages.nameKeys(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("normName")
    class NormName {

        @Test
        void stripsTrailingCardNumbers() {
            assertEquals("mew ex", ImportCardImages.normName("Mew ex - 232/091"));
            assertEquals("rillaboom v", ImportCardImages.normName("Rillaboom V - SWSH014"));
        }

        /**
         * A genuine qualifier is kept here, since the catalog usually carries it
         * too, and keeping it is what resolves the card to its own artwork
         * rather than the base printing's.
         */
        @Test
        void keepsGenuineQualifiers() {
            assertTrue(ImportCardImages.normName("Blastoise - 13/95 (Cracked Ice Holo)")
                    .contains("cracked ice holo"));
        }

        /**
         * But a parenthesised card number is still noise. "Aerodactyl (1)" is
         * simply Fossil #1; the dry run caught this being treated as a
         * printing in the first version of the tier-2 rule.
         */
        @Test
        void stripsParenthesisedCardNumbers() {
            assertEquals("aerodactyl", ImportCardImages.normName("Aerodactyl (1)"));
        }
    }

    @Nested
    @DisplayName("strippedQualifier")
    class StrippedQualifier {

        @Test
        void reportsTheWordThatWouldBeDropped() {
            assertEquals("cracked ice holo",
                    ImportCardImages.strippedQualifier("Blastoise - 13/95 (Cracked Ice Holo)"));
        }

        /**
         * Records verbatim, deliberately. TCGplayer sells both a
         * "Semi-Finalist" and a "Semi Finalist" printing, so folding them
         * here would destroy a true fact about the catalog to tidy a lookup
         * table. Spelling is reconciled at comparison time by
         * normalize_qualifier() instead.
         */
        @Test
        void keepsSpellingVariantsDISTINCT() {
            String hyphen = ImportCardImages.strippedQualifier("Fake Card (Semi-Finalist)");
            String space = ImportCardImages.strippedQualifier("Fake Card (Semi Finalist)");
            assertEquals("semi-finalist", hyphen);
            assertEquals("semi finalist", space);
            assertNotEquals(hyphen, space,
                    "folding these here would destroy a true fact about the catalog");
        }

        /**
         * The number comes off before the qualifier is visible to a trailing
         * match. TCGplayer writes the two in that order.
         */
        @Test
        void seesAQualifierSittingBehindACardNumber() {
            assertEquals("professor magnolia",
                    ImportCardImages.strippedQualifier("Professor's Research [Professor Magnolia] - 223"));
        }

        @Test
        void returnsNullForAnOrdinaryPrinting() {
            assertNull(ImportCardImages.strippedQualifier("Charizard - 4/102"));
            assertNull(ImportCardImages.strippedQualifier("Aerodactyl (1)"));
        }
    }
}
