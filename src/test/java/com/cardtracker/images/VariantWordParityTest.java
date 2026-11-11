package com.cardtracker.images;

import com.cardtracker.sql.TestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Does {@code is_variant_word()} still agree with
 * {@link ImportCardImages#isVariantWord}?
 *
 * CLAUDE.md calls these two a "change both or neither" pair because one
 * decides a row's label and the other decides its image, so a disagreement
 * means the caption and the picture were chosen by different rules. Nothing
 * checked that until 2026-08-20, and when something finally did, they had
 * already drifted, though not in either implementation, both of which were
 * individually correct, but in the CONTRACT between them, which said nothing
 * about who trims. 81 of 2,547 real tokens changed verdict when padded.
 *
 * Two things this does that the SQL check file cannot:
 *
 *  1. It runs BOTH implementations, rather than asserting a property of one.
 *  2. It compares in-process. The first version of this harness passed tokens
 *     through tab-separated text and reported 2,163 disagreements from 2,547
 *     inputs, the doubled count being the tell that tokens containing a
 *     newline had been split in two. The harness was broken, not the code.
 *     Nothing here is serialised, so that failure mode is gone rather than
 *     guarded against.
 */
@EnabledIf("com.cardtracker.sql.TestDatabase#available")
@DisplayName("is_variant_word parity")
class VariantWordParityTest {

    private static TestDatabase db;
    private static PreparedStatement sqlVerdict;

    /**
     * Real shapes from the catalog, chosen to span every branch: the length
     * floor, the card-code test, genuine qualifiers, and the punctuation that
     * makes spelling variants distinct.
     */
    private static final List<String> CORPUS = List.of(
            "A", "M", "Z", "ex", "GX", "V",
            "1", "2", "147", "004", "XY72", "XY73", "SV66", "H1", "SWSH014", "RC4", "TG12", "GG46",
            "Cracked Ice Holo", "Cosmos Holo", "Delta Species", "Team Plasma", "Prerelease",
            "Staff", "Winner", "Finalist", "Semi-Finalist", "Semi Finalist",
            "Quarter-Finalist", "Quarter Finalist", "1st Place", "4th Place",
            "Professor Magnolia", "Professor Elm", "Tsubasa Nakamura", "Riley McKay",
            "Ice", "Shiny", "Full Art", "Prime", "Special", "Non-Holo", "Holo",
            "World Championships 2017", "Regional Championship", "Regional Championships",
            "Poke Ball Pattern", "Detective Pikachu Stamped", "e-League", "Top 16",
            "", "   ", "ab", "abc");

    @BeforeAll
    static void setUp() throws Exception {
        db = TestDatabase.create();
        sqlVerdict = db.conn().prepareStatement("SELECT is_variant_word(?)");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (sqlVerdict != null) sqlVerdict.close();
        if (db != null) db.close();
    }

    private static boolean sql(String token) throws Exception {
        sqlVerdict.setString(1, token);
        try (ResultSet rs = sqlVerdict.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /** Every disagreement, named, rather than a bare count. */
    private static List<String> disagreements(List<String> tokens) throws Exception {
        List<String> out = new ArrayList<>();
        for (String token : tokens) {
            boolean s = sql(token);
            boolean j = ImportCardImages.isVariantWord(token);
            if (s != j) {
                out.add("[" + visible(token) + "] sql=" + s + " java=" + j);
            }
        }
        return out;
    }

    private static String visible(String s) {
        return s.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static List<String> perturb(java.util.function.UnaryOperator<String> f) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : CORPUS) out.add(f.apply(t));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------

    @Test
    void agreesOnEveryTokenAsWritten() throws Exception {
        List<String> bad = disagreements(CORPUS);
        assertTrue(bad.isEmpty(), "the two implementations disagree:\n  " + String.join("\n  ", bad));
    }

    /**
     * The perturbation that actually found the bug. Agreement on the inputs
     * that happen to occur proves very little, since the callers were all btrimming,
     * so the plain corpus agreed perfectly while the contract was broken.
     */
    @Test
    void agreesWhenTokensArePaddedWithSpaces() throws Exception {
        List<String> bad = disagreements(perturb(t -> " " + t + " "));
        assertTrue(bad.isEmpty(),
                "padding changed a verdict - the untrimmed-token bug is back:\n  "
                        + String.join("\n  ", bad));
    }

    /**
     * Postgres btrim(x) strips SPACES ONLY while Java's trim() takes everything
     * at or below U+0020, so the obvious one-argument fix left these
     * disagreeing. The character set in is_variant_word() is deliberate.
     */
    @Test
    void agreesWhenTokensCarryTabsAndNewlines() throws Exception {
        List<String> bad = new ArrayList<>();
        bad.addAll(disagreements(perturb(t -> "\t" + t + "\n")));
        bad.addAll(disagreements(perturb(t -> "\r\n" + t + "\t")));
        assertTrue(bad.isEmpty(),
                "btrim(x) strips spaces only - use the explicit character set:\n  "
                        + String.join("\n  ", bad));
    }

    @Test
    void agreesOnNull() throws Exception {
        sqlVerdict.setString(1, null);
        try (ResultSet rs = sqlVerdict.executeQuery()) {
            rs.next();
            boolean sqlSaidTrue = rs.getBoolean(1) && !rs.wasNull();
            assertEquals(ImportCardImages.isVariantWord(null), sqlSaidTrue,
                    "null must not be a variant word on either side");
        }
    }

    /**
     * A guard on the corpus itself. If this ever trips, the perturbations above
     * are running over an empty list and passing for the wrong reason.
     */
    @Test
    void theCorpusActuallyExercisesBothOutcomes() throws Exception {
        long trues = 0;
        long falses = 0;
        for (String t : CORPUS) {
            if (ImportCardImages.isVariantWord(t)) trues++;
            else falses++;
        }
        assertTrue(trues > 10, "corpus must contain real qualifiers, got " + trues);
        assertTrue(falses > 10, "corpus must contain real non-qualifiers, got " + falses);
    }
}
