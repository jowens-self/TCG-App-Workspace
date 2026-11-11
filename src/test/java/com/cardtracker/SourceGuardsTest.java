package com.cardtracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards that assert facts about the codebase rather than its behaviour.
 *
 * These exist because this project's most durable rules live in CLAUDE.md as
 * prose, and prose is advisory: it can be read, agreed with, and violated in
 * the same session. Each guard below turns one such rule into something that
 * fails a build. They are the cheapest tests here and each catches a whole
 * class rather than an instance.
 *
 * Every one of them corresponds to something that has actually gone wrong.
 */
@DisplayName("source guards")
class SourceGuardsTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();

    private static List<Path> filesUnder(String dir, String... extensions) throws IOException {
        Path base = ROOT.resolve(dir);
        if (!Files.isDirectory(base)) return List.of();
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        for (String ext : extensions) if (n.endsWith(ext)) return true;
                        return false;
                    })
                    .toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new RuntimeException(p.toString(), e);
        }
    }

    /** Strips line and block comments so a guard checks code, not prose about code. */
    private static String codeOnly(String src) {
        String noBlocks = src.replaceAll("(?s)/\\*.*?\\*/", " ")
                             .replaceAll("(?s)<!--.*?-->", " ");
        StringBuilder out = new StringBuilder();
        for (String line : noBlocks.split("\n")) {
            String t = line.strip();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("--")) continue;
            int slashes = line.indexOf("//");
            out.append(slashes >= 0 ? line.substring(0, slashes) : line).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // The rule that keeps the app off the marketplaces.
    // ------------------------------------------------------------------

    /**
     * Only these hosts may appear as a literal anywhere in shipped code.
     * tcgcsv and pokemontcg.io are the two granted permissions; localhost is
     * our own API. Anything else (a marketplace, a CDN, an image provider)
     * needs the owner's explicit permission BEFORE code is written, and this
     * is the check that makes "before" enforceable.
     */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "tcgcsv.com", "api.pokemontcg.io", "images.pokemontcg.io",
            "localhost", "127.0.0.1");

    private static final Pattern URL_HOST =
            Pattern.compile("https?://([A-Za-z0-9._-]+)");

    @Test
    void noOutboundHostBeyondThePermittedOnes() throws IOException {
        List<String> offences = new ArrayList<>();
        List<Path> sources = new ArrayList<>(filesUnder("src/main/java", ".java"));
        sources.addAll(filesUnder("web/src", ".ts", ".html", ".css"));

        for (Path p : sources) {
            Matcher m = URL_HOST.matcher(codeOnly(read(p)));
            while (m.find()) {
                if (!ALLOWED_HOSTS.contains(m.group(1))) {
                    offences.add(ROOT.relativize(p) + " -> " + m.group(1));
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "Outbound host not on the permitted list. A CDN subdomain is still the "
                + "marketplace, and the front end already shipped TCGplayer thumbnails once. "
                + "Ask the owner before adding a host, not after:\n  " + String.join("\n  ", offences));
    }

    /**
     * The same rule where it was actually broken. An href fetches nothing, but
     * an img src fetches on render, roughly 50 requests per search page when
     * this last went wrong.
     */
    @Test
    void noImageTagFetchesFromAnywhereButOurOwnApi() throws IOException {
        Pattern imgSrc = Pattern.compile("<img[^>]*?\\[?src\\]?=\"([^\"]+)\"", Pattern.DOTALL);
        List<String> offences = new ArrayList<>();

        for (Path p : filesUnder("web/src", ".html")) {
            Matcher m = imgSrc.matcher(codeOnly(read(p)));
            while (m.find()) {
                String src = m.group(1);
                boolean ourApi = src.startsWith("imageUrl(") || src.contains("imageUrl(");
                boolean relative = src.startsWith("/") || src.startsWith("assets/");
                if (!ourApi && !relative) {
                    offences.add(ROOT.relativize(p) + " -> " + src);
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "An <img> must resolve through our own API, which serves cached files off "
                + "disk. Binding one to a third-party URL puts the browser on their host:\n  "
                + String.join("\n  ", offences));
    }

    // ------------------------------------------------------------------
    // The rule that protects the history.
    // ------------------------------------------------------------------

    @Test
    void priceSnapshotsIsNeverTheTargetOfAnUpdate() throws IOException {
        List<String> offences = new ArrayList<>();
        List<Path> sources = new ArrayList<>(filesUnder("src/main/java", ".java"));
        sources.addAll(filesUnder("sql", ".sql"));

        for (Path p : sources) {
            String code = codeOnly(read(p)).toLowerCase();
            if (!code.contains("price_snapshots")) continue;
            if (code.contains("update price_snapshots")
                    || code.contains("delete from price_snapshots")) {
                offences.add(ROOT.relativize(p).toString());
            }
            // ON CONFLICT ... DO UPDATE anywhere near the insert is the one
            // that would silently let a bad run overwrite a good observation.
            Matcher m = Pattern.compile("insert\\s+into\\s+price_snapshots(.{0,400}?)(;|$)",
                    Pattern.DOTALL).matcher(code);
            while (m.find()) {
                if (m.group(1).contains("do update")) {
                    offences.add(ROOT.relativize(p) + " (ON CONFLICT DO UPDATE)");
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "price_snapshots is append-only: inserts use ON CONFLICT DO NOTHING so a "
                + "re-run is safe and a bad run can never overwrite a good observation. "
                + "The history is the whole asset:\n  " + String.join("\n  ", offences));
    }

    /**
     * Search paging is safe only because every path that establishes a fresh
     * result list runs through Search.setResults(), which bumps the
     * generation counter that a page request in flight is checked against.
     * A fourth path calling this.results.set() directly would skip that bump,
     * and the symptom would be the old query's rows appended to the new
     * query's list, which is intermittent, timing-dependent and invisible.
     *
     * Asserted as a count rather than by parsing the method, because the
     * property that actually matters is "exactly one writer", and that is
     * what a count says. Appends go through results.update() inside
     * loadMore(), behind the generation check.
     */
    @Test
    void onlyOnePlaceReplacesTheSearchResultList() throws IOException {
        Path search = ROOT.resolve("web/src/app/search.ts");
        if (!Files.isRegularFile(search)) return;

        Matcher m = Pattern.compile("this\\.results\\.set\\(").matcher(read(search));
        int writers = 0;
        while (m.find()) writers++;

        assertEquals(1, writers,
                "web/src/app/search.ts must replace the result list in exactly one place "
                + "(Search.setResults), so no path can establish a new list without bumping "
                + "the page generation that Load more checks against. Found " + writers
                + " - route the new one through setResults().");
    }

    // ------------------------------------------------------------------
    // Dates: never the host's clock, and for ingestion never a clock at all.
    // ------------------------------------------------------------------

    /**
     * A bare LocalDate.now() reads the host's timezone, so snapshot_date would
     * stop meaning the same thing across the table's history.
     */
    @Test
    void noBareLocalDateNow() throws IOException {
        Pattern bare = Pattern.compile("LocalDate\\.now\\(\\s*\\)");
        List<String> offences = new ArrayList<>();
        for (Path p : filesUnder("src/main/java", ".java")) {
            if (bare.matcher(codeOnly(read(p))).find()) {
                offences.add(ROOT.relativize(p).toString());
            }
        }
        assertTrue(offences.isEmpty(),
                "Use LocalDate.now(ZoneOffset.UTC) - a bare now() reads the host timezone:\n  "
                + String.join("\n  ", offences));
    }

    /**
     * And the stronger rule that replaced it for ingestion: the live pull's
     * date comes from the mirror, because the live endpoints carry no date and
     * a clock answers when we asked rather than what we got. Dating a pull
     * from any clock is what wrote 51,287 duplicate rows on 2026-08-20.
     */
    @Test
    void theLivePullTakesItsDateFromTheSourceNotAClock() throws IOException {
        String app = codeOnly(read(ROOT.resolve("src/main/java/com/cardtracker/App.java")));
        Matcher assignment = Pattern.compile("snapshotDate\\s*=\\s*([^;]+);").matcher(app);
        assertTrue(assignment.find(), "App must still assign a snapshotDate");
        assertTrue(assignment.group(1).contains("dataAsOf"),
                "snapshot_date must come from PriceSource.dataAsOf(), not a clock. "
                + "Found: " + assignment.group(1).strip());
    }

    // ------------------------------------------------------------------
    // Front-end correctness that review missed once already.
    // ------------------------------------------------------------------

    /**
     * Two options bound to the same value break how Angular matches a select's
     * current value. This happened on 2026-08-20 when a period ladder was
     * added over an existing "1 year" option that also meant 365.
     */
    @Test
    void noSelectHasTwoOptionsBoundToTheSameValue() throws IOException {
        Pattern selectBlock = Pattern.compile("<select\\b.*?</select>", Pattern.DOTALL);
        Pattern ngValue = Pattern.compile("\\[ngValue\\]=\"([^\"]+)\"");
        List<String> offences = new ArrayList<>();

        for (Path p : filesUnder("web/src", ".html")) {
            Matcher blocks = selectBlock.matcher(read(p));
            while (blocks.find()) {
                Map<String, Integer> seen = new HashMap<>();
                Matcher vals = ngValue.matcher(blocks.group());
                while (vals.find()) {
                    seen.merge(vals.group(1), 1, Integer::sum);
                }
                seen.forEach((v, n) -> {
                    if (n > 1) offences.add(ROOT.relativize(p) + " -> [ngValue]=\"" + v + "\" x" + n);
                });
            }
        }
        assertTrue(offences.isEmpty(),
                "Duplicate option values break select binding:\n  " + String.join("\n  ", offences));
    }

    // ------------------------------------------------------------------
    // The docs are load-bearing here; a dead reference is a real defect.
    // ------------------------------------------------------------------

    /**
     * CLAUDE.md is read at the start of every session and its rules are
     * followed, so an identifier in it that names nothing is worse than a
     * typo, because it sends the reader looking for code that does not exist. A
     * deleted class was cited inside a hard invariant for days before anyone
     * noticed.
     *
     * Only checks things that look like this project's own identifiers:
     * something() or a CamelCase name. Prose terms that are genuinely not our
     * code are listed explicitly rather than pattern-matched away.
     */
    private static final Set<String> NOT_OUR_CODE = Set.of(
            "ClassNotFoundException",   // JDK
            "LICENSE",                  // the file at the repo root
            "Postgres", "Docker", "Maven", "Angular", "Javalin", "Jackson",
            "TCGplayer", "CardTracker", "Pokemon");

    /**
     * Names of OUR code that no longer exists and is cited on purpose, because
     * the doc is explaining history rather than claiming the class is there.
     *
     * An explicit list, not a pattern that sniffs for words like "deleted"
     * nearby, the same call this project made for the tournament qualifier
     * family. A pattern would quietly excuse a genuinely stale reference that
     * happened to sit in the wrong sentence.
     *
     * Adding a name here is a claim that the surrounding prose says it is gone.
     */
    private static final Set<String> DELETED_AND_CITED_AS_HISTORY = Set.of();

    @Test
    void everyIdentifierCitedInClaudeMdExists() throws IOException {
        Path doc = ROOT.resolve("CLAUDE.md");
        if (!Files.isRegularFile(doc)) return;

        StringBuilder corpus = new StringBuilder();
        for (Path p : filesUnder("src/main/java", ".java")) corpus.append(read(p));
        // Test sources count: CLAUDE.md legitimately points at the guards and
        // their allowlists, and those are as real as anything in src/main.
        for (Path p : filesUnder("src/test/java", ".java")) corpus.append(read(p));
        for (Path p : filesUnder("sql", ".sql")) corpus.append(read(p));
        for (Path p : filesUnder("web/src", ".ts", ".html", ".css")) corpus.append(read(p));
        for (Path p : filesUnder("sql/checks", ".sh")) corpus.append(read(p));
        String all = corpus.toString();

        Set<String> missing = new HashSet<>();
        Matcher ticks = Pattern.compile("`([^`\n]+)`").matcher(read(doc));
        Pattern call = Pattern.compile("([A-Za-z_][A-Za-z0-9_.]*)\\(\\)");
        Pattern camel = Pattern.compile("([A-Z][A-Za-z0-9_]{3,})");

        while (ticks.find()) {
            String token = ticks.group(1).strip();
            String name = null;
            Matcher c = call.matcher(token);
            Matcher k = camel.matcher(token);
            if (c.matches()) name = c.group(1);
            else if (k.matches()) name = k.group(1);
            if (name == null) continue;

            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (NOT_OUR_CODE.contains(simple)) continue;
            if (DELETED_AND_CITED_AS_HISTORY.contains(simple)) continue;
            if (!all.contains(simple)) missing.add(token);
        }

        assertTrue(missing.isEmpty(),
                "CLAUDE.md cites identifiers that exist nowhere in the source. Either the "
                + "code was deleted and the doc not updated, or the name is prose and belongs "
                + "in NOT_OUR_CODE:\n  " + String.join("\n  ", missing));
    }
}
