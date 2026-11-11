package com.cardtracker.api;

import com.cardtracker.Env;
import com.cardtracker.ImageDir;
import com.cardtracker.db.QueryRepository;
import com.cardtracker.db.SettingsRepository;
import com.cardtracker.images.ImageFiles;
import com.cardtracker.model.DataFreshness;
import com.cardtracker.model.LaunchPricing;
import com.cardtracker.model.UserSettings;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.json.JavalinJackson;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP API over the price history and the user's settings.
 *
 * Exists so a front end never talks to Postgres (or to tcgcsv) directly.
 *
 * Read-only over PRICE data, and that part is not negotiable: ingestion stays
 * in App/BackfillArchive where the append-only guarantees live, and nothing
 * here writes price_snapshots. What it does write is the user's own data:
 * preferences, and each game's rules (see SettingsRepository).
 *
 * Run it:
 *   java -cp target/cardtracker-0.1.0.jar com.cardtracker.api.ApiServer
 */
public class ApiServer {

    public static final int DEFAULT_PORT = 7070;

    public static void main(String[] args) {
        int port = Integer.parseInt(Env.get("CARDTRACKER_API_PORT", String.valueOf(DEFAULT_PORT)));
        // Resolved the same way as ImportCardImages, which writes them, so the
        // writer and the reader cannot end up pointing at different trees.
        Path imageRoot = ImageDir.resolve();

        Javalin app = createApp(Env.dbUrl(), Env.dbUser(), Env.dbPass(), imageRoot);

        app.start(port);
        System.out.printf("CardTracker API on http://localhost:%d%n", port);
        // Printed because a wrong image root is otherwise indistinguishable
        // from "we have no image for that card". Both render as a placeholder.
        System.out.printf("  serving card images from %s%n", imageRoot);
    }

    /**
     * Builds the app without starting it, so a test can bind an ephemeral port
     * and point it at a throwaway database.
     *
     * The routing is thin, but the response contract is not: no endpoint may
     * ever return high_price, a bad image path must not escape the image root,
     * and an absent value must arrive as null rather than 0. Those are worth
     * exercising over real HTTP rather than by reading the handlers.
     */
    public static Javalin createApp(String url, String user, String pass, Path imageRoot) {
        ImageFiles imageFiles = new ImageFiles(imageRoot);
        Db db = new Db(url, user, pass);

        Javalin app = Javalin.create(config -> {
            // LocalDate must serialize as "2026-08-14", not an array of ints
            // or an epoch number. The client charts on these directly.
            config.jsonMapper(new JavalinJackson().updateMapper(m -> {
                m.registerModule(new JavaTimeModule());
                m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            }));
            // The Angular dev server runs on its own port, so browser calls
            // here are cross-origin and blocked without this. Scoped to
            // localhost: no site a visitor happens to open should be able to
            // query it from their browser.
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                rule.allowHost("http://localhost:4200", "http://127.0.0.1:4200");
            }));
        });

        // Health means "is the data still arriving", not "is this process up".
        // Once, the pull failed silently for five days and the only signal
        // was data that stopped changing, which nothing was looking at. A
        // liveness check that answers "ok" while the history quietly stops is
        // the exact failure that cost those days, so this reads the data.
        //
        // Cached for a minute because the front end asks on every page load
        // and the answer cannot change faster than the daily pull writes.
        // Held per-app rather than statically so a test's server gets its own.
        AtomicReference<Cached<List<DataFreshness>>> freshnessCache = new AtomicReference<>();
        app.get("/api/health", ctx -> {
            Cached<List<DataFreshness>> hit = freshnessCache.get();
            List<DataFreshness> freshness;
            if (hit != null && !hit.expired()) {
                freshness = hit.value();
            } else {
                freshness = db.withRepo(QueryRepository::freshness);
                freshnessCache.set(new Cached<>(freshness, System.nanoTime()));
            }
            boolean stale = freshness.stream().anyMatch(DataFreshness::stale);
            ctx.json(Map.of(
                    "status", stale ? "stale" : "ok",
                    "staleAfterDays", DataFreshness.STALE_AFTER_DAYS,
                    "games", freshness));
        });

        app.get("/api/games", ctx ->
                ctx.json(db.withRepo(QueryRepository::games)));

        app.get("/api/games/{categoryId}/sets", ctx -> {
            int id = pathId(ctx, "categoryId");
            ctx.json(db.withRepo(repo -> repo.sets(id)));
        });

        // Every set of a game, with current value and windowed return.
        //   ?game=3&days=30&asOf=2026-08-18&despike=false
        // game omitted = all tracked games. days defaults to 30 in
        // game_set_overview() rather than here, so psql callers match.
        //
        // despike defaults ON: a price that swings hard and is back the next
        // day is an upstream error, and the median of its neighbours is used
        // instead. Pass despike=false for the raw two-point comparison.
        //
        // prerelease=true / early=true count launch pricing toward the change;
        // both are left out by default (see launchPricing()).
        app.get("/api/sets/overview", ctx -> {
            Integer days = atLeast(ctx, "days", 1);
            ctx.json(db.withRepo(repo -> repo.setOverview(
                    intParam(ctx.queryParam("game"), "game"),
                    days,
                    dateParam(ctx.queryParam("asOf"), "asOf"),
                    !"false".equalsIgnoreCase(ctx.queryParam("despike")),
                    launchPricing(ctx))));
        });

        // Biggest movers over a window.
        //   ?days=7&game=3&set=23353&sealed=false&minPrice=1&direction=down
        //   &smoothing=3&limit=50&modern=true&prerelease=true&early=true
        // Every parameter is optional: the defaults (7 days, gains,
        // singles, $1 floor, 3-day median smoothing, every era, settled prices
        // only) are the screen's default view and live in item_movers() so
        // psql callers get them too.
        app.get("/api/movers", ctx -> {
            String dir = ctx.queryParam("direction");
            if (dir != null && !dir.equals("up") && !dir.equals("down")) {
                throw new BadRequestResponse("direction must be 'up' or 'down'");
            }
            Integer days = atLeast(ctx, "days", 1);
            Integer smoothing = atLeast(ctx, "smoothing", 1);
            BigDecimal minPrice = decimalParam(ctx.queryParam("minPrice"), "minPrice");

            ctx.json(db.withRepo(repo -> repo.movers(
                    days,
                    intParam(ctx.queryParam("game"), "game"),
                    intParam(ctx.queryParam("set"), "set"),
                    "true".equalsIgnoreCase(ctx.queryParam("sealed")),
                    minPrice,
                    dir,
                    smoothing,
                    intParam(ctx.queryParam("limit"), "limit"),
                    "true".equalsIgnoreCase(ctx.queryParam("modern")),
                    launchPricing(ctx))));
        });

        // Card / product search. q is required; everything else optional.
        //   ?q=charizard&game=3&sealed=false&limit=25&offset=0
        app.get("/api/items/search", ctx -> {
            String q = ctx.queryParam("q");
            if (q == null || q.isBlank()) {
                throw new BadRequestResponse("q is required");
            }
            Integer game = intParam(ctx.queryParam("game"), "game");
            boolean includeSealed = !"false".equalsIgnoreCase(ctx.queryParam("sealed"));
            int limit = Objects.requireNonNullElse(intParam(ctx.queryParam("limit"), "limit"), 25);
            int offset = Objects.requireNonNullElse(intParam(ctx.queryParam("offset"), "offset"), 0);

            ctx.json(db.withRepo(repo -> repo.search(q, game, includeSealed, limit, offset)));
        });

        // Cached card thumbnail, served off OUR disk. The browser must never
        // fetch an image from a third party. That is the rule that got broken
        // with TCGplayer's CDN (see CLAUDE.md), and it holds for the permitted
        // image source too: ImportCardImages fetches once, this serves forever.
        //
        // 404 is an ordinary outcome, not an error: about 5% of Pokemon
        // singles have no upstream image, and One Piece has none yet, so the
        // client shows a placeholder.
        app.get("/api/items/{productId}/image", ctx -> {
            int id = pathId(ctx, "productId");
            // Resolved on disk rather than out of card_images. This endpoint
            // used to spend a JDBC connection translating an id into a path,
            // which is the single biggest source of connection pressure here:
            // one per thumbnail, 50 per search page. See ImageFiles.
            Path file = imageFiles.find(id);
            if (file == null) throw new NotFoundResponse("No image for product " + id);

            ctx.contentType("image/jpeg");
            // Immutable in practice: a card's art doesn't change, and the file
            // is only rewritten by an explicit re-import.
            ctx.header("Cache-Control", "public, max-age=604800");
            ctx.result(Files.readAllBytes(file));
        });

        app.get("/api/items/{productId}", ctx -> {
            int id = pathId(ctx, "productId");
            var product = db.withRepo(repo -> repo.product(id));
            if (product == null) throw new NotFoundResponse("No product " + id);
            ctx.json(product);
        });

        app.get("/api/items/{productId}/variants", ctx -> {
            int id = pathId(ctx, "productId");
            var variants = db.withRepo(repo -> repo.variants(id));
            // An empty variant list means the id has no price history at all,
            // which for a client is the same failure as an unknown id, so say so
            // rather than returning [] and letting a UI render a blank chart.
            if (variants.isEmpty()) {
                throw new NotFoundResponse("No price history for product " + id);
            }
            ctx.json(variants);
        });

        // Whose artwork this card's thumbnail actually shows. Kept separate
        // from /items/{id} because only the detail view needs it. Folding it
        // in would make every caller pay for something most never render.
        // 404 means we hold no image for the product, which is ordinary.
        app.get("/api/items/{productId}/image/provenance", ctx -> {
            int id = pathId(ctx, "productId");
            var prov = db.withRepo(repo -> repo.imageProvenance(id));
            if (prov == null) {
                throw new NotFoundResponse("No image provenance for product " + id);
            }
            ctx.json(prov);
        });

        // ?subType=Holofoil&from=2024-02-08&to=2026-08-14
        // subType is required and deliberately has no default: picking one
        // for the caller would mean guessing which market they meant, and
        // Normal/Holofoil/Reverse Holofoil are genuinely different markets.
        app.get("/api/items/{productId}/history", ctx -> {
            int id = pathId(ctx, "productId");
            String subType = ctx.queryParam("subType");
            if (subType == null || subType.isBlank()) {
                throw new BadRequestResponse(
                        "subType is required - call /api/items/" + id + "/variants to list them");
            }
            LocalDate from = dateParam(ctx.queryParam("from"), "from");
            LocalDate to = dateParam(ctx.queryParam("to"), "to");
            if (from != null && to != null && from.isAfter(to)) {
                throw new BadRequestResponse("from must not be after to");
            }

            var points = db.withRepo(repo -> repo.history(id, subType, from, to));
            if (points.isEmpty()) {
                throw new NotFoundResponse(
                        "No history for product " + id + " variant '" + subType + "'");
            }
            ctx.json(points);
        });

        // --- settings -------------------------------------------------
        //
        // What the Settings screen reads and writes. Preferences are per user
        // (LOCAL_USER until sign-in exists). Game rules are per game and
        // shared, because they define what the data means rather than how
        // one person likes to see it.

        app.get("/api/settings", ctx -> ctx.json(
                db.withConn(conn -> new SettingsRepository(conn).get(LOCAL_USER))));

        // Every field is required. A partial body would mean guessing the
        // rest, and a guessed preference is a changed preference.
        app.put("/api/settings", ctx -> {
            SettingsRequest req = body(ctx, SettingsRequest.class);
            if (req.includePreRelease() == null || req.includeEarly() == null) {
                throw new BadRequestResponse("includePreRelease and includeEarly are both required");
            }
            ctx.json(db.withConn(conn -> new SettingsRepository(conn).save(LOCAL_USER,
                    new UserSettings(req.includePreRelease(), req.includeEarly()))));
        });

        // One game's rules: where "modern" starts (null means every set) and
        // how many days after release a card's prices count as early. Answers
        // with the game as /api/games describes it, including how many sets
        // the new cutoff takes in, counted by the same rule every screen uses.
        app.put("/api/games/{categoryId}/rules", ctx -> {
            int id = pathId(ctx, "categoryId");
            GameRulesRequest req = body(ctx, GameRulesRequest.class);
            if (req.earlyDays() == null || req.earlyDays() < 0 || req.earlyDays() > 365) {
                throw new BadRequestResponse("earlyDays must be between 0 and 365");
            }
            ctx.json(db.withConn(conn -> {
                if (!new SettingsRepository(conn).saveGameRules(id, req.modernSince(), req.earlyDays())) {
                    throw new NotFoundResponse("Game " + id + " is not tracked");
                }
                return new QueryRepository(conn).games().stream()
                        .filter(g -> g.categoryId() == id)
                        .findFirst()
                        .orElseThrow();
            }));
        });

        return app;
    }

    /** Body of PUT /api/settings. Boxed so a missing field can be told from false. */
    public record SettingsRequest(Boolean includePreRelease, Boolean includeEarly) {
    }

    /** Body of PUT /api/games/{id}/rules. modernSince null means every set is modern. */
    public record GameRulesRequest(LocalDate modernSince, Integer earlyDays) {
    }

    /**
     * A request body, or a 400 saying it couldn't be read. Without this a
     * malformed date or a string where a number belongs surfaces as a 500,
     * which blames the server for the caller's typo.
     */
    private static <T> T body(Context ctx, Class<T> type) {
        try {
            return ctx.bodyAsClass(type);
        } catch (Exception e) {
            // Exception, not RuntimeException: Javalin is Kotlin, and Jackson's
            // parse errors are checked exceptions it can throw undeclared.
            throw new BadRequestResponse("Could not read the request body: " + e.getMessage());
        }
    }

    /**
     * Whose settings the API reads and writes, until sign-in exists.
     *
     * Seeded by sql/migrations/001_users.sql. Deliberately a named constant
     * rather than a literal 1 scattered through the handlers, so the day a
     * session provides it there is exactly one place that stops being a
     * constant.
     */
    private static final long LOCAL_USER = 1L;

    /**
     * A value with an expiry, for answers that cost more than they change.
     *
     * nanoTime rather than currentTimeMillis: this measures an elapsed
     * interval, and wall-clock time can step backwards (NTP, DST) which would
     * pin a cache entry as permanently fresh.
     */
    private record Cached<T>(T value, long takenAtNanos) {
        private static final long TTL_NANOS = 60L * 1_000_000_000L;

        boolean expired() {
            return System.nanoTime() - takenAtNanos > TTL_NANOS;
        }
    }

    /**
     * One connection per call, closed on the way out.
     *
     * Not a pool on purpose: a JDBC Connection is not thread-safe, Javalin
     * serves requests concurrently, and a single shared Connection would give
     * intermittent corruption under any parallel use rather than a clean
     * failure. Opening one per request costs a few ms locally, the same order
     * as the queries themselves. If this ever serves more than one person,
     * put HikariCP in withConn(); it is the only place that would change.
     *
     * A handler needing more than one repository (the game-rules write path
     * saves, then re-reads the game) calls withConn() once rather than paying
     * a connection per repository.
     */
    private record Db(String url, String user, String pass) {
        <T> T withConn(SqlFunction<Connection, T> work) throws Exception {
            try (Connection conn = DriverManager.getConnection(url, user, pass)) {
                return work.apply(conn);
            }
        }

        <T> T withRepo(SqlFunction<QueryRepository, T> work) throws Exception {
            return withConn(conn -> work.apply(new QueryRepository(conn)));
        }
    }

    /** Like java.util.function.Function but allowed to throw, since JDBC does. */
    @FunctionalInterface
    private interface SqlFunction<A, R> {
        R apply(A a) throws Exception;
    }

    private static int pathId(Context ctx, String name) {
        Integer id = intParam(ctx.pathParam(name), name);
        if (id == null) throw new BadRequestResponse(name + " is required");
        return id;
    }

    /**
     * ?prerelease=true and ?early=true count launch pricing on a trend screen.
     * Absent means left out, which is the point: pre-release and early prices
     * are mostly noise, so only a caller who asks gets them.
     */
    private static LaunchPricing launchPricing(Context ctx) {
        return new LaunchPricing(
                "true".equalsIgnoreCase(ctx.queryParam("prerelease")),
                "true".equalsIgnoreCase(ctx.queryParam("early")));
    }

    /** An optional integer query parameter that must be at least min when present. */
    private static Integer atLeast(Context ctx, String name, int min) {
        Integer v = intParam(ctx.queryParam(name), name);
        if (v != null && v < min) {
            throw new BadRequestResponse(name + " must be at least " + min);
        }
        return v;
    }

    private static Integer intParam(String raw, String name) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new BadRequestResponse(name + " must be an integer, got '" + raw + "'");
        }
    }

    private static BigDecimal decimalParam(String raw, String name) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new BadRequestResponse(name + " must be a number, got '" + raw + "'");
        }
    }

    private static LocalDate dateParam(String raw, String name) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BadRequestResponse(name + " must be YYYY-MM-DD, got '" + raw + "'");
        }
    }
}
