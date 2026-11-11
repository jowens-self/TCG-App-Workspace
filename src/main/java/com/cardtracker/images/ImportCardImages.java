package com.cardtracker.images;

import com.cardtracker.Env;
import com.cardtracker.ImageDir;
import com.cardtracker.source.pokemontcg.PokemonTcgCatalog;
import com.cardtracker.source.pokemontcg.PokemonTcgCatalog.Card;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.Normalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches our Pokemon products against the pokemontcg.io catalog and caches a
 * downscaled thumbnail on local disk.
 *
 * Run it:
 *   java -cp target/cardtracker-0.1.0.jar com.cardtracker.images.ImportCardImages
 *   ... [--limit N] [--group GROUP_ID] [--match-only] [--skip-failed]
 *
 * **Why images are cached locally rather than hotlinked.** The browser must
 * never contact a third party for these; our own API serves them off disk.
 * That was the rule that got broken once already with TCGplayer's CDN (see
 * CLAUDE.md), and it applies to a permitted host too: fetch once, serve
 * forever, no per-view traffic to anyone.
 *
 * **Coverage is 94.1%, and the ~60% it sat at before was a bug, not a
 * ceiling.** Measured then: 16,841 of 27,807 Pokemon singles matched
 * (60.6%); after the two fixes below, 26,166. An earlier
 * version of this comment blamed the gap on the upstream catalog, claiming
 * "World Championship decks / jumbo / prize packs / promos are absent
 * entirely." That claim is false. Audited 2026-08-18 against the
 * cached catalog: `swshp` alone carries 304 promo cards we had written off.
 * The real causes are two normalization defects below. Replayed offline, fixing both recovers
 * 8,834 of the 10,912 no_match rows (81%).
 *
 * 1. {@link #LEADING_DIGITS} is anchored, so {@link #numberDigits} returns
 *    null for any number not starting with a digit (H1, SV066, SWSH014,
 *    RC4, TG12, GG46). That null is fatal on BOTH sides: 1,550 of 20,479
 *    catalog cards (7.6%) are never indexed, and those products never
 *    attempt a match.
 * 2. {@link #normName} strips a trailing "- 123" and "- 4/5" but not a
 *    trailing parenthetical or letter-number token, so "Alakazam (H1)" and
 *    "Rillaboom V - SWSH014" never match their bare catalog names.
 *
 * **Both were fixed 2026-08-19**. Matching now runs in two tiers:
 * tier 1 uses the product's name with its printing qualifier intact, and
 * only if that finds nothing does tier 2 retry with the qualifier stripped.
 * Tier 1 is preferred because the catalog usually carries the qualifier too
 * ("Professor's Research (Professor Magnolia)"), so it resolves to the
 * card's own artwork. Reaching tier 2 means the catalog holds no card
 * bearing that printing, so the image is the base printing's, and the
 * qualifier that had to be dropped is stored in
 * {@code card_images.stripped_qualifier}.
 *
 * That column is a FACT about the match, not a verdict on the artwork -
 * "Cracked Ice Holo" is a foil treatment over existing art, while "Delta
 * Species" is card identity pokemontcg.io spells as "Aerodactyl δ". Storing
 * the word rather than a verdict is what let the verdict be computed later
 * without re-running an 80-minute import. Note the qualifier is decided at
 * match time and never by comparing set names: the two catalogs disagree on
 * those.
 *
 * The first conclusion here was superseded, so don't re-derive it. The comment
 * here used to conclude that whether a stripped qualifier means the picture
 * is wrong "has no mechanical answer." There is one: a pokemontcg.io
 * card is one picture, so asking which OTHER products claim the same
 * {@code source_id} settles most of it structurally, and the owner's
 * treatment/identity calls in {@code image_qualifier_class} settle the rest.
 * {@code card_image_provenance()} in sql/functions.sql combines the two. The
 * verdict is computed there, never stored here.
 *
 * Record the stripped word verbatim; don't normalise it in this class.
 * TCGplayer genuinely sells both "Semi-Finalist" and "Semi Finalist"
 * printings, so folding them here would destroy a true fact about the catalog
 * to tidy a lookup table. Spelling variants are reconciled at comparison
 * time by {@code normalize_qualifier()} instead.
 *
 * Use {@code --dry-run} to measure rules without writing or downloading
 * anything. It exists because the misses this records are permanent, and
 * the rules have been wrong before, and the dry run caught a defect in the
 * first version of the tier-2 test, which mistook parenthesised card
 * numbers ("Aerodactyl (1)") for printing qualifiers.
 *
 * Misses are recorded as status='no_match' so they are never re-queried -
 * which is why changing any of the above also requires clearing those rows.
 */
public class ImportCardImages {

    /** Pokemon only. No permitted image source exists for any other game. */
    private static final int POKEMON = 3;

    /** Rendered at 40x56 CSS px; 160 wide covers 2x displays with headroom. */
    private static final int THUMB_WIDTH = 160;
    private static final float JPEG_QUALITY = 0.82f;

    public static void main(String[] args) throws Exception {
        int limit = Integer.MAX_VALUE;
        Integer onlyGroup = null;
        boolean matchOnly = false;
        boolean dryRun = false;
        boolean skipFailed = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--group" -> onlyGroup = Integer.parseInt(args[++i]);
                case "--match-only" -> matchOnly = true;
                // Measure without consequences: no download, no DB write, no
                // no_match row. Exists because the matching rules have been
                // wrong before and the misses they record are permanent.
                case "--dry-run" -> dryRun = true;
                // Leave 'failed' rows alone. They are normally retried, which
                // is right for a transient blip, but 61 of them are an
                // upstream gap that has now failed on three separate days
                // (CLAUDE.md has the evidence), and retrying those costs ~6
                // requests each against a free community API for no expected
                // gain. This makes that rule enforceable instead of
                // remembered.
                case "--skip-failed" -> skipFailed = true;
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }

        // Fails loudly if launched outside the project root. See ImageDir.
        Path imageDir = ImageDir.resolve();
        Files.createDirectories(imageDir);

        PokemonTcgCatalog api = new PokemonTcgCatalog();

        try (Connection conn = Env.connect()) {
            List<Product> todo = productsNeedingImages(conn, onlyGroup, limit, dryRun, skipFailed);
            if (todo.isEmpty()) {
                // Distinguish "there is nothing pending" from "you asked for
                // none". The old message claimed the former in both cases.
                System.out.println(limit <= 0
                        ? "Nothing selected: --limit " + limit + " requests no products."
                        : "Nothing pending - every matching Pokemon single already has a "
                          + "card_images row. Delete rows to force a re-check.");
                return;
            }
            System.out.printf("%d product(s) to check%n", todo.size());

            Map<String, List<Card>> byKeyStrict = new HashMap<>();
            Map<String, List<Card>> byKeyNumCode = new HashMap<>();
            Map<String, List<Card>> byKeyLetter = new HashMap<>();
            Map<String, List<Card>> byKeyLoose = new HashMap<>();
            CatalogCache cache = new CatalogCache(imageDir.resolve(".catalog-cache"));

            List<Card> catalog = cache.load();
            List<String> failedSets = new ArrayList<>();
            if (catalog != null) {
                System.out.printf("Using cached catalog (%d cards, age %s). "
                        + "Delete %s to force a refresh.%n",
                        catalog.size(), cache.ageDescription(), cache.file());
            } else {
                System.out.println("Downloading upstream catalog (per set - their deep "
                        + "pagination 500s past ~12k rows)...");
                catalog = new ArrayList<>();
                List<String> setIds = api.setIds();
                List<String> pending = new ArrayList<>(setIds);

                // Up to three sweeps over whatever is still missing. Their API
                // fails 5-11 sets out of 174 on any given attempt, essentially
                // at random, and a set that fails once usually succeeds moments
                // later. Without this the run almost never reaches a complete
                // catalog, and an incomplete catalog blocks miss-recording
                // entirely (see below), so every future run then re-checks all
                // ~11,700 unmatched products from scratch, forever.
                for (int sweep = 1; sweep <= 3 && !pending.isEmpty(); sweep++) {
                    if (sweep > 1) {
                        System.out.printf("  retry sweep %d for %d set(s): %s%n",
                                sweep, pending.size(), pending);
                    }
                    List<String> stillFailing = new ArrayList<>();
                    for (int i = 0; i < pending.size(); i++) {
                        try {
                            catalog.addAll(api.cardsInSet(pending.get(i)));
                        } catch (Exception e) {
                            stillFailing.add(pending.get(i));
                            if (sweep == 3) {
                                System.out.printf("  set %s failed after 3 sweeps: %s%n",
                                        pending.get(i), e.getMessage());
                            }
                        }
                        if (sweep == 1 && (i + 1) % 25 == 0) {
                            System.out.printf("  %d/%d sets, %d cards%n",
                                    i + 1, pending.size(), catalog.size());
                        }
                        api.pace();
                    }
                    pending = stillFailing;
                }
                failedSets = pending;
                if (failedSets.isEmpty()) cache.save(catalog);
            }

            for (Card c : catalog) {
                String d = numberDigits(c.number());
                if (d == null) {
                    // Not indexed anywhere before this: a card numbered "ONE"
                    // or "!" has no digits, so it fell out of every key. See
                    // numberLetters().
                    String cLetters = numberLetters(c.number());
                    if (cLetters != null) {
                        for (String n : nameKeys(c.name())) {
                            byKeyLetter.computeIfAbsent(n + "|@" + cLetters,
                                    k -> new ArrayList<>()).add(c);
                        }
                    }
                    continue;
                }
                String cCode = numberCode(c.number());
                // Under both spellings, see nameKeys(). Keying only the folded
                // one would trade "Nidoran (F)" away to win "Nidoran F".
                for (String n : nameKeys(c.name())) {
                    byKeyStrict.computeIfAbsent(n + "|" + d + "|" + c.printedTotal(),
                            k -> new ArrayList<>()).add(c);
                    if (cCode != null) {
                        byKeyNumCode.computeIfAbsent(n + "|" + cCode,
                                k -> new ArrayList<>()).add(c);
                    }
                    byKeyLoose.computeIfAbsent(n + "|" + d, k -> new ArrayList<>()).add(c);
                }
            }

            // If any set is still missing, the catalog is incomplete, and a
            // product that looks unmatched might simply live in a set we never
            // saw. Writing 'no_match' then would be a permanent lie: those rows
            // are never re-checked, so a transient 500 would silently cost that
            // card its image forever. Unmatched products are left with no row
            // at all in that case, so a later run reconsiders them.
            boolean catalogComplete = failedSets.isEmpty();
            System.out.printf("Catalog: %d cards%s%n", catalog.size(),
                    catalogComplete ? "" : " (INCOMPLETE - misses will NOT be recorded)");
            if (!catalogComplete) {
                System.out.println("  still failing after 3 sweeps: " + failedSets
                        + "\n  re-run later to pick them up.");
            }

            int matched = 0, noMatch = 0, downloaded = 0, failed = 0, skippedUnknown = 0;
            int baseArtMatches = 0, baseArtStrict = 0, baseArtNumCode = 0, baseArtLoose = 0;
            List<String> baseArtSample = new ArrayList<>();
            List<String> baseArtNumCodeSample = new ArrayList<>();
            List<String> baseArtLooseSample = new ArrayList<>();
            for (Product p : todo) {
                // fold() first: the catalog writes "Poké Ball" and "Nidoran ♀"
                // where TCGplayer writes "Poke Ball" and "Nidoran F".
                String folded = fold(p.name);
                String nFull = normName(folded);
                String nBase = normNameBase(folded);
                String d = numberDigits(p.cardNumber);
                String t = numberTotal(p.cardNumber);
                String code = numberCode(p.cardNumber);
                String letters = numberLetters(p.cardNumber);

                Card hit = null;
                String rule = null;
                String strippedQualifier = null;

                // Tier 1: the product's own name, printing qualifier intact.
                // Preferred always: when the catalog carries the qualifier too
                // this resolves to the card's actual artwork.
                if (nFull != null && d != null) {
                    hit = strict(byKeyStrict, nFull, d, t);
                    if (hit != null) rule = "name+number+total";
                    if (hit == null) {
                        hit = numCoded(byKeyNumCode, nFull, code);
                        if (hit != null) rule = "name+numcode";
                    }
                    if (hit == null) {
                        hit = loose(byKeyLoose, nFull, d);
                        if (hit != null) rule = "name+number";
                    }
                }
                if (hit == null && nFull != null && letters != null) {
                    hit = lettered(byKeyLetter, nFull, letters);
                    if (hit != null) rule = "name+letter";
                }

                // Tier 2: qualifier stripped. Only reached when tier 1 found
                // nothing, which means the catalog has no card bearing this
                // printing: whatever comes back is the BASE printing's art.
                // Which tier succeeded is the signal, so no set-name
                // comparison is involved, and none should be (the two
                // catalogs disagree on set names).
                if (hit == null && nBase != null && !nBase.equals(nFull)
                        && (d != null || letters != null)) {
                    if (d == null) {
                        // No digits to key on at all, so the letter index
                        // is the only one holding this card. See numberLetters().
                        hit = lettered(byKeyLetter, nBase, letters);
                        if (hit != null) rule = "name+letter/base-art";
                    } else {
                        hit = strict(byKeyStrict, nBase, d, t);
                        if (hit != null) rule = "name+number+total/base-art";
                        if (hit == null) {
                            hit = numCoded(byKeyNumCode, nBase, code);
                            if (hit != null) rule = "name+numcode/base-art";
                        }
                        if (hit == null) {
                            hit = loose(byKeyLoose, nBase, d);
                            if (hit != null) rule = "name+number/base-art";
                        }
                    }
                    if (hit != null) {
                        strippedQualifier = strippedQualifier(p.name);
                        baseArtMatches++;
                        List<String> bucket;
                        if (rule.startsWith("name+number+total")) {
                            baseArtStrict++;
                            bucket = baseArtSample;
                        } else if (rule.startsWith("name+numcode")) {
                            baseArtNumCode++;
                            bucket = baseArtNumCodeSample;
                        } else {
                            baseArtLoose++;
                            bucket = baseArtLooseSample;
                        }
                        if (bucket.size() < 12) {
                            bucket.add(String.format("%s  ->  %s (%s #%s)",
                                    p.name, hit.name(), hit.setName(), hit.number()));
                        }
                    }
                }

                if (hit == null || hit.imageUrl() == null) {
                    if (dryRun) {
                        noMatch++;
                    } else if (catalogComplete) {
                        record(conn, p.productId, null, "no_match", null, null, null, null);
                        noMatch++;
                    } else {
                        skippedUnknown++;
                    }
                    continue;
                }
                matched++;
                if (dryRun) continue;
                if (matchOnly) {
                    record(conn, p.productId, hit.id(), "failed", null, null, rule, strippedQualifier);
                    continue;
                }

                try {
                    byte[] raw = api.download(hit.imageUrl());
                    byte[] thumb = toThumbnail(raw);
                    String rel = POKEMON + "/" + p.productId + ".jpg";
                    Path dest = imageDir.resolve(rel);
                    Files.createDirectories(dest.getParent());
                    Files.write(dest, thumb);
                    record(conn, p.productId, hit.id(), "ok", rel, thumb.length, rule, strippedQualifier);
                    downloaded++;
                    if (downloaded % 100 == 0) {
                        System.out.printf("  %d downloaded, %d no-match, %d failed%n",
                                downloaded, noMatch, failed);
                    }
                } catch (Exception e) {
                    // 'failed' rather than 'no_match': the card exists upstream,
                    // so this is retryable on a later run.
                    record(conn, p.productId, hit.id(), "failed", null, null, rule, strippedQualifier);
                    failed++;
                }
                api.pace();
            }

            if (dryRun) {
                System.out.printf("%nDRY RUN - nothing written, nothing downloaded.%n");
                System.out.printf("  checked      %d%n", todo.size());
                System.out.printf("  would match  %d  (%.1f%%)%n",
                        matched, 100.0 * matched / Math.max(todo.size(), 1));
                System.out.printf("  would miss   %d%n", noMatch);
                System.out.printf("  of matches, base-art (tier 2): %d%n", baseArtMatches);
                System.out.printf("     via name+number+TOTAL (set size agrees): %d%n", baseArtStrict);
                System.out.printf("     via name+NUMCODE (number prefix agrees):  %d%n", baseArtNumCode);
                System.out.printf("     via name+number only:                    %d%n", baseArtLoose);
                if (!baseArtSample.isEmpty()) {
                    System.out.println("  -- tier 2, total agreed:");
                    for (String line : baseArtSample) System.out.println("    " + line);
                }
                if (!baseArtNumCodeSample.isEmpty()) {
                    System.out.println("  -- tier 2, matched on the number's prefix:");
                    for (String line : baseArtNumCodeSample) System.out.println("    " + line);
                }
                if (!baseArtLooseSample.isEmpty()) {
                    System.out.println("  -- tier 2, total did NOT agree:");
                    for (String line : baseArtLooseSample) System.out.println("    " + line);
                }
                return;
            }
            System.out.printf("Done. matched=%d (downloaded=%d, failed=%d), no_match=%d%n",
                    matched, downloaded, failed, noMatch);
            System.out.printf("  base-art matches (image is the base printing): %d%n",
                    baseArtMatches);
            if (skippedUnknown > 0) {
                System.out.printf("  %d unmatched product(s) left unrecorded because the "
                        + "catalog was incomplete - re-run to resolve them.%n", skippedUnknown);
            }
        }
    }

    private record Product(int productId, String name, String cardNumber) {
    }

    /**
     * Pokemon singles with no usable card_images row yet. 'failed' rows ARE
     * retried; 'ok' and 'no_match' are not. That negative caching is what
     * stops ~11,000 unmatchable cards being re-checked on every run.
     *
     * Note what this means for a matching-rule change: an 'ok' row is never
     * re-examined, so a new rule cannot redirect an image that already
     * exists. It can only fill a gap. That is a property of this query, not
     * a promise made elsewhere.
     *
     * @param includeNoMatch re-check the permanently-cached misses too
     *                       (--dry-run, which writes nothing)
     * @param skipFailed     leave 'failed' rows alone as well (--skip-failed)
     */
    private static List<Product> productsNeedingImages(Connection conn, Integer group, int limit,
                                                      boolean includeNoMatch, boolean skipFailed)
            throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT p.product_id, p.name, extended_field(p.extended_data, 'Number') AS num
                  FROM products p
                  LEFT JOIN card_images ci ON ci.product_id = p.product_id
                 WHERE p.category_id = ? AND p.is_sealed = FALSE
                   AND (ci.product_id IS NULL
                        OR (ci.status = 'failed'   AND NOT ?)
                        OR (ci.status = 'no_match' AND ?))
                """);
        if (group != null) sql.append(" AND p.group_id = ").append(group);
        sql.append(" ORDER BY p.product_id LIMIT ").append(limit);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setInt(1, POKEMON);
            ps.setBoolean(2, skipFailed);
            ps.setBoolean(3, includeNoMatch);
            List<Product> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Product(rs.getInt(1), rs.getString(2), rs.getString(3)));
                }
            }
            return out;
        }
    }

    /** Strictest lookup: name + number + printed total. Null total = skip. */
    private static Card strict(Map<String, List<Card>> idx, String n, String d, String t) {
        if (t == null) return null;
        List<Card> c = idx.get(n + "|" + d + "|" + t);
        return (c == null || c.isEmpty()) ? null : c.get(0);
    }

    /**
     * Name + number only, and only when it is unambiguous, since a confidently
     * wrong image is worse than an obvious blank for someone pricing
     * inventory, so an ambiguous key is treated as no match at all.
     */
    /**
     * Name + the card number WITH its alphabetic prefix, and only when it is
     * unambiguous. Sits between {@link #strict} and {@link #loose} because it
     * is strictly more discriminating than the latter: every card keyed
     * "arcanine|h2" is also keyed "arcanine|2", so this can only ever narrow a
     * candidate list the loose key would have found ambiguous, never widen
     * one. Same refusal on ambiguity, for the same reason.
     */
    private static Card numCoded(Map<String, List<Card>> idx, String n, String code) {
        if (code == null) return null;
        List<Card> c = idx.get(n + "|" + code);
        return (c != null && c.size() == 1) ? c.get(0) : null;
    }

    /**
     * Name + a card number that is entirely non-numeric, unambiguous only.
     * Its index holds exactly the cards the other three cannot see at all, so
     * consulting it can never disturb a match any of them would have made.
     */
    private static Card lettered(Map<String, List<Card>> idx, String n, String letters) {
        if (letters == null) return null;
        List<Card> c = idx.get(n + "|@" + letters);
        return (c != null && c.size() == 1) ? c.get(0) : null;
    }

    private static Card loose(Map<String, List<Card>> idx, String n, String d) {
        List<Card> c = idx.get(n + "|" + d);
        return (c != null && c.size() == 1) ? c.get(0) : null;
    }

    private static void record(Connection conn, int productId, String sourceId, String status,
                               String path, Integer bytes, String rule, String strippedQualifier)
            throws Exception {
        String sql = """
                INSERT INTO card_images (product_id, source_id, status, file_path, bytes,
                                         match_rule, stripped_qualifier, checked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (product_id) DO UPDATE SET
                    source_id = EXCLUDED.source_id, status = EXCLUDED.status,
                    file_path = EXCLUDED.file_path, bytes = EXCLUDED.bytes,
                    match_rule = EXCLUDED.match_rule,
                    stripped_qualifier = EXCLUDED.stripped_qualifier, checked_at = now()
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            ps.setString(2, sourceId);
            ps.setString(3, status);
            ps.setString(4, path);
            if (bytes == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, bytes);
            ps.setString(6, rule);
            ps.setString(7, strippedQualifier);
            ps.executeUpdate();
        }
    }

    /**
     * Downscale to a real thumbnail. Their "small" PNG is ~157KB for
     * something rendered at 40x56, and storing it as-is would be about
     * 2.6GB across the catalog, versus roughly 200MB like this.
     */
    static byte[] toThumbnail(byte[] raw) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
        if (src == null) throw new IllegalArgumentException("unreadable image");
        int w = THUMB_WIDTH;
        int h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));

        // TYPE_INT_RGB, not ARGB: JPEG has no alpha channel, and writing an
        // image that has one produces colour-inverted output on some encoders.
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(dst, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    // --- matching helpers -------------------------------------------------
    // Ported from the throwaway script used to measure the match rate before
    // committing to this, so the numbers in the class comment are the numbers
    // these rules actually produce.

    private static final Pattern TRAILING_FRACTION = Pattern.compile("\\s*-\\s*[0-9a-z]+/[0-9a-z]+\\s*$");
    /**
     * A trailing card number TCGplayer appended to the name. Widened from
     * digits-only to allow a letter prefix: "Rillaboom V - SWSH014" is the
     * same card as the catalog's bare "Rillaboom V", and the old
     * digits-only rule left the token attached so it never matched.
     */
    private static final Pattern TRAILING_ALNUM_TOKEN =
            Pattern.compile("\\s*-\\s*[a-z]*[0-9]+[a-z0-9]*\\s*$");
    /** A trailing parenthesised or bracketed group, with its contents. */
    private static final Pattern TRAILING_GROUP =
            Pattern.compile("\\s*[\\(\\[]([^\\)\\]]*)[\\)\\]]\\s*$");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");
    /** "2", "147", "XY72", "H1": a card number wearing a parenthesis. */
    private static final Pattern CARD_CODE = Pattern.compile("^[A-Za-z]{0,4}[0-9]+[A-Za-z]?$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /**
     * Digits anywhere in the number, not anchored to its start. Anchoring was
     * the original defect: "H1", "SV066", "SWSH014", "RC4", "TG12" and "GG46"
     * all begin with a letter, so the old pattern returned null, which was
     * fatal on BOTH sides of the match, dropping 1,550 of 20,479 catalog
     * cards from the index and stopping those products ever attempting one.
     */
    private static final Pattern ANY_DIGITS = Pattern.compile("([0-9]+)");
    /** A denominator that is a plain count. See {@link #numberLetters}. */
    private static final Pattern DIGITS_ONLY = Pattern.compile("[0-9]+");
    /**
     * An alphabetic prefix in front of a card number: the "H" of "H02", the
     * "SWSH" of "SWSH039". Bounded at 5 letters so a stray word can never be
     * read as one. See {@link #numberCode}.
     */
    private static final Pattern NUM_CODE = Pattern.compile("^\\s*([a-z]{1,5})0*([0-9]+)");

    /**
     * Is a trailing bracketed token a printing qualifier, or just a card
     * number in disguise?
     *
     * A direct port of {@code is_variant_word()} in sql/functions.sql, and it
     * must stay one, because the two answer the same question about the same
     * strings, and a disagreement would mean the label a row displays and the
     * image it displays were decided by different rules. Both were bugs
     * first: without the length floor all 26 "Unown [A]".."[Z]" read as
     * variants, and without the card-code test so do "Alakazam (2)",
     * "Crobat (147)" and "Latios EX (XY72)".
     */
    static boolean isVariantWord(String token) {
        if (token == null) return false;
        String t = token.trim();
        return t.length() >= 3
                && HAS_LETTER.matcher(t).find()
                && !CARD_CODE.matcher(t).matches();
    }

    /**
     * "Mew ex - 232/091" -> "mew ex", so it lines up with their bare names.
     *
     * A genuine printing qualifier is deliberately KEPT: the catalog usually
     * carries it too ("Professor's Research (Professor Magnolia)", "Ancient
     * Technical Machine [Ice]"), so keeping it is what makes those resolve to
     * their own artwork rather than the base card's. A parenthesised card
     * A number is still stripped here: "Aerodactyl (1)" is simply Fossil #1
     * and matching it is not borrowing anything.
     */
    /**
     * Characters the two catalogs spell differently for the same card.
     *
     * pokemontcg.io prints the real glyphs; TCGplayer transliterates them, and
     * {@link #finish} then replaces anything outside [a-z0-9 ] with a space -
     * so "Pokémon Catcher" normalises to "pok mon catcher" and can never meet
     * TCGplayer's "pokemon catcher". 430 catalog cards carry such a character
     * and the accent alone accounted for 228 permanently missed images.
     *
     * The symbol mappings are TCGplayer's own spellings, read off the products
     * rather than invented: "Nidoran ♀" is sold as "Nidoran F", "Mew ★" as
     * "Mew Star", "Diancie ◇" as "Diancie Prism Star". They are a fixed list
     * for the reason the qualifier vocabulary is a fixed list: a pattern that
     * FINDS candidates must not also classify them.
     *
     * Deliberately absent: δ, α, β and γ. Those are card identity that the
     * owner has already classified through {@code stripped_qualifier}
     * ("Aerodactyl δ" is TCGplayer's "Aerodactyl (Delta Species)", and
     * {@code delta species} is an `identity` row in
     * {@code image_qualifier_class}). Folding them here would resolve those
     * cards at tier 1 instead and silently rewrite verdicts already settled.
     * NFD leaves them alone on their own because they carry no combining
     * mark, so this is a property to test rather than code to write.
     */
    private static final String[] SYMBOL_WORDS = {
            "\u2640", " f ",           // ♀
            "\u2642", " m ",           // ♂
            "\u2605", " star ",        // ★
            "\u25C7", " prism star ",  // ◇
    };

    private static final Pattern COMBINING = Pattern.compile("\\p{M}+");

    /** "Poké Ball" -> "Poke Ball"; "Nidoran ♀" -> "Nidoran  f ". */
    static String fold(String s) {
        if (s == null) return null;
        String v = s;
        for (int i = 0; i < SYMBOL_WORDS.length; i += 2) {
            v = v.replace(SYMBOL_WORDS[i], SYMBOL_WORDS[i + 1]);
        }
        return COMBINING.matcher(Normalizer.normalize(v, Normalizer.Form.NFD)).replaceAll("");
    }

    /**
     * The distinct names a catalog card should be findable under: its own, and
     * its folded spelling.
     *
     * both, because TCGplayer is not consistent with itself: it sells one
     * card as "Nidoran F" and another as "Nidoran (F)", and the catalog writes
     * "Nidoran ♀" for both. The bracketed form normalises to a bare "nidoran"
     * (a one-letter token is not a printing qualifier), so keying only the
     * folded "nidoran f" would win the first and lose the second. Measured:
     * folding alone was +332 and -1; keying both is +331 and -0, for the same
     * total. A set, so a card whose spellings agree is indexed once and the
     * ambiguity checks in {@link #loose} and {@link #numCoded} still mean what
     * they say.
     */
    static Set<String> nameKeys(String catalogName) {
        Set<String> keys = new LinkedHashSet<>();
        String plain = normName(catalogName);
        if (plain != null) keys.add(plain);
        String folded = normName(fold(catalogName));
        if (folded != null) keys.add(folded);
        return keys;
    }

    static String normName(String s) {
        if (s == null) return null;
        return finish(stripTrailingNoise(s.toLowerCase(Locale.ROOT), false));
    }

    /**
     * As {@link #normName}, but printing qualifiers removed too:
     * "Blastoise - 13/95 (Cracked Ice Holo)" -> "blastoise".
     *
     * A match found with THIS and not with {@link #normName} means the
     * catalog holds no card bearing the qualifier, so the image that comes
     * back is the base printing's artwork rather than this product's. That is
     * the stripped_qualifier signal: decided by which name matched, never by
     * comparing set names, because the two catalogs disagree on those
     * (TCGplayer's "SWSH: Sword & Shield Promo Cards" is pokemontcg.io's
     * "SWSH Black Star Promos", about 900 cards a name comparison would wrongly
     * call borrowed).
     */
    static String normNameBase(String s) {
        if (s == null) return null;
        return finish(stripTrailingNoise(s.toLowerCase(Locale.ROOT), true));
    }

    private static String finish(String v) {
        v = NON_ALNUM.matcher(v).replaceAll(" ");
        v = SPACES.matcher(v).replaceAll(" ").trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * Strips trailing card numbers always, and trailing printing qualifiers
     * only when asked. Loops because the shapes nest: TCGplayer writes
     * "Professor's Research [Professor Magnolia] - 223", so the number has to
     * come off before the qualifier is even visible to a trailing match.
     *
     * @param dropQualifiers also remove a trailing qualifier that
     *                       {@link #isVariantWord} accepts
     */
    private static String stripTrailingNoise(String v, boolean dropQualifiers) {
        String prev;
        do {
            prev = v;
            v = TRAILING_FRACTION.matcher(v).replaceAll("");
            v = TRAILING_ALNUM_TOKEN.matcher(v).replaceAll("");
            Matcher m = TRAILING_GROUP.matcher(v);
            if (m.find() && (dropQualifiers || !isVariantWord(m.group(1)))) {
                v = v.substring(0, m.start());
            }
        } while (!v.equals(prev));
        return v;
    }

    /**
     * The printing qualifier {@link #normNameBase} would remove, or null if
     * there isn't one. "Blastoise - 13/95 (Cracked Ice Holo)" -> "cracked
     * ice holo".
     *
     * Recorded rather than interpreted. Whether a stripped qualifier means
     * the artwork is actually wrong is a domain question with no mechanical
     * answer: "Cracked Ice Holo" and "Poke Ball Pattern" are foil treatments
     * applied to existing art, so the picture really is the base printing's,
     * while "Delta Species" and "Team Plasma" are card identity that
     * pokemontcg.io simply spells differently ("Aerodactyl δ"), so the
     * picture is correct. Storing the word itself lets that classification be
     * made once, later, in SQL, and applied retroactively without
     * re-running this import.
     */
    static String strippedQualifier(String name) {
        if (name == null) return null;
        String v = name.toLowerCase(Locale.ROOT);
        String prev;
        do {
            prev = v;
            v = TRAILING_FRACTION.matcher(v).replaceAll("");
            v = TRAILING_ALNUM_TOKEN.matcher(v).replaceAll("");
            Matcher m = TRAILING_GROUP.matcher(v);
            if (m.find()) {
                if (isVariantWord(m.group(1))) return m.group(1).trim();
                v = v.substring(0, m.start());
            }
        } while (!v.equals(prev));
        return null;
    }

    /** "004/102" -> "4"; "H1" -> "1"; "196" -> "196". Leading zeros are padding. */
    static String numberDigits(String s) {
        if (s == null) return null;
        return firstNumber(s.split("/")[0]);
    }

    /**
     * The card number with its alphabetic prefix KEPT: "H02/H32" -> "h2",
     * "BW64" -> "bw64", "SWSH039" -> "swsh39". Null when there is no prefix.
     *
     * {@link #numberDigits} discards that prefix, and for most cards it is
     * noise. For a holo subset or a promo series it is the only thing
     * identifying the printing, and discarding it loses the match twice over.
     * TCGplayer numbers Aquapolis' holo subset "H02/H32" (32 cards) where
     * the catalog numbers the same card "H2" inside a parent set of 147, so
     * the printed totals disagree and {@link #strict} misses. The bare digits
     * then collide with every other card #2 in every other set, so
     * {@link #loose} is ambiguous and correctly refuses. The prefix is what
     * breaks the tie, and it was being thrown away before it could.
     *
     * Recovered 89 images across 15 sets when added (2026-08-21): the H
     * subsets of Aquapolis and Skyridge, the SM/BW/XY/DP Black Star Promos,
     * and Platinum's "SH" / Call of Legends' "SL" shiny subsets.
     *
     * **Null for a bare number on purpose.** Without a prefix this key is
     * character-for-character {@link #loose}'s, so it would add no
     * discrimination, only a second tier reporting a different rule name for
     * a decision already made.
     */
    static String numberCode(String s) {
        if (s == null) return null;
        Matcher m = NUM_CODE.matcher(s.split("/")[0].toLowerCase(Locale.ROOT));
        return m.find() ? m.group(1) + m.group(2) : null;
    }

    /**
     * A card number with NO digits at all: "L/28" -> "l", "ONE" -> "one",
     * "!/28" -> "!". Null whenever {@link #numberDigits} can answer.
     *
     * numberDigits() handles numbers whose digits are not at the start; this is the
     * same blind spot one layer deeper, for numbers that have no digits to
     * find. It was fatal on both sides exactly as that one was: 32 catalog
     * cards were absent from every index, and the products facing them never
     * attempted a match. They are the Unown alphabet in Unseen Forces
     * (A-Z plus ! and ?) and the four HGSS-era Alph Lithographs, whose
     * numbers are the words ONE, TWO, THREE and FOUR.
     *
     * **A non-numeric denominator is rejected**, which is the whole
     * subtlety: TCGplayer writes "N/A" for a card with no number, and
     * splitting on "/" would read that as the letter "n" and hand it any
     * catalog card numbered N. A real one reads "L/28", letters over a
     * count, so requiring the denominator to be digits separates them.
     */
    static String numberLetters(String s) {
        if (s == null) return null;
        if (ANY_DIGITS.matcher(s.split("/")[0]).find()) return null;
        String[] parts = s.split("/", -1);
        if (parts.length > 2) return null;
        if (parts.length == 2 && (parts[1].isBlank() || !DIGITS_ONLY.matcher(parts[1].trim()).matches())) {
            return null;
        }
        String head = parts[0].trim().toLowerCase(Locale.ROOT);
        return head.isEmpty() ? null : head;
    }

    /** "004/102" -> "102"; anything without a denominator -> null. */
    static String numberTotal(String s) {
        if (s == null) return null;
        String[] parts = s.split("/");
        if (parts.length != 2) return null;
        return firstNumber(parts[1]);
    }

    /**
     * Leading zeros are dropped by string, not by parseInt, since a pathological
     * run of digits would overflow an int, and this never needs the value.
     */
    private static String firstNumber(String s) {
        Matcher m = ANY_DIGITS.matcher(s);
        if (!m.find()) return null;
        String digits = m.group(1).replaceFirst("^0+(?=.)", "");
        return digits.isEmpty() ? null : digits;
    }
}
