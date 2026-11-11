package com.cardtracker.db;

import com.cardtracker.model.DataFreshness;
import com.cardtracker.model.ImageProvenance;
import com.cardtracker.model.ItemSearchResult;
import com.cardtracker.model.ItemVariant;
import com.cardtracker.model.LaunchPricing;
import com.cardtracker.model.Mover;
import com.cardtracker.model.PricePoint;
import com.cardtracker.model.SetOverview;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads price history. The counterpart to PriceRepository, which is
 * write-only by design ("Writes price history"). Keeping the read path
 * separate rather than growing that class means the ingestion code and the
 * API surface can't accidentally couple.
 *
 * Every method here is a thin call into a function in sql/functions.sql, not
 * inline SQL. That's on purpose: the query logic stays
 * verifiable directly in psql (which is how this project checks its own
 * numbers), and the same definition serves both the API and hand-written
 * analysis, so the two can never quietly diverge.
 */
public class QueryRepository {

    private final Connection conn;

    public QueryRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * Name search across products. categoryId null = all tracked games.
     * The limit cap lives in item_search() itself (LEAST(limit, 200)) rather
     * than here, so it holds for psql callers too, not just this class.
     */
    public List<ItemSearchResult> search(String query, Integer categoryId,
                                         boolean includeSealed, int limit, int offset)
            throws Exception {
        String sql = "SELECT " + SEARCH_COLUMNS + " FROM item_search(?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query);
            if (categoryId == null) {
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setInt(2, categoryId);
            }
            ps.setBoolean(3, includeSealed);
            ps.setInt(4, limit);
            ps.setInt(5, offset);

            List<ItemSearchResult> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(searchResult(rs));
            }
            return out;
        }
    }

    /** Every variant of one product, with current price and all-time high. */
    public List<ItemVariant> variants(int productId) throws Exception {
        String sql = "SELECT sub_type_name, first_date, latest_date, latest_market, "
                + "all_time_high FROM item_variants(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            List<ItemVariant> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ItemVariant(
                            rs.getString("sub_type_name"),
                            localDate(rs.getDate("first_date")),
                            localDate(rs.getDate("latest_date")),
                            rs.getBigDecimal("latest_market"),
                            rs.getBigDecimal("all_time_high")));
                }
            }
            return out;
        }
    }

    /**
     * Whose artwork one product's thumbnail shows, or null when we hold no
     * image for it (no row, or the match failed), which is an ordinary
     * outcome, not an error.
     *
     * About 5ms. Search rows carry the verdict too, as artBorrowed, from one
     * call per page inside item_products().
     */
    public ImageProvenance imageProvenance(int productId) throws Exception {
        String sql = "SELECT product_id, name, source_id, stripped_qualifier, "
                + "qualifier_class, claimants, provenance, owner_product_id, owner_name "
                + "FROM card_image_provenance(NULL, ARRAY[?])";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                // wasNull() reports on the most recent get, so the null test
                // has to happen here and not inline below, because by then several
                // more columns have been read.
                int ownerId = rs.getInt("owner_product_id");
                Integer ownerProductId = rs.wasNull() ? null : ownerId;
                return new ImageProvenance(
                        rs.getInt("product_id"),
                        rs.getString("name"),
                        rs.getString("source_id"),
                        rs.getString("stripped_qualifier"),
                        rs.getString("qualifier_class"),
                        rs.getInt("claimants"),
                        rs.getString("provenance"),
                        ownerProductId,
                        rs.getString("owner_name"));
            }
        }
    }

    /** Price time series for one product+variant. Null dates mean unbounded. */
    public List<PricePoint> history(int productId, String subTypeName,
                                    LocalDate from, LocalDate to) throws Exception {
        String sql = "SELECT snapshot_date, market_price, low_price, mid_price, "
                + "direct_low_price, phase FROM item_history(?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            ps.setString(2, subTypeName);
            setDate(ps, 3, from);
            setDate(ps, 4, to);

            List<PricePoint> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PricePoint(
                            localDate(rs.getDate("snapshot_date")),
                            rs.getBigDecimal("market_price"),
                            rs.getBigDecimal("low_price"),
                            rs.getBigDecimal("mid_price"),
                            rs.getBigDecimal("direct_low_price"),
                            rs.getString("phase")));
                }
            }
            return out;
        }
    }

    /**
     * Product identity for one id, or null if unknown. Search already returns
     * this, but a client that arrives by product_id alone (a bookmarked link,
     * a future inventory join) has no other way to resolve a name/set.
     */
    public ItemSearchResult product(int productId) throws Exception {
        String sql = "SELECT " + SEARCH_COLUMNS + " FROM item_product(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? searchResult(rs) : null;
            }
        }
    }

    /** What item_search() and item_product() both return, in that order. */
    private static final String SEARCH_COLUMNS = "product_id, name, card_number, rarity, "
            + "variant_label, set_name, game, is_sealed, variant_count, latest_date, url, image_url, "
            + "art_borrowed, art_owner_name";

    private static ItemSearchResult searchResult(ResultSet rs) throws Exception {
        return new ItemSearchResult(
                rs.getInt("product_id"),
                rs.getString("name"),
                rs.getString("card_number"),
                rs.getString("rarity"),
                rs.getString("variant_label"),
                rs.getString("set_name"),
                rs.getString("game"),
                rs.getBoolean("is_sealed"),
                rs.getInt("variant_count"),
                localDate(rs.getDate("latest_date")),
                rs.getString("url"),
                rs.getString("image_url"),
                rs.getBoolean("art_borrowed"),
                rs.getString("art_owner_name"));
    }

    /** The games actually being tracked, for the screens' game filters. */
    public List<Game> games() throws Exception {
        String sql = "SELECT * FROM tracked_games()";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Game> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Game(rs.getInt("category_id"), rs.getString("name"),
                        rs.getInt("product_count"), localDate(rs.getDate("modern_since")),
                        rs.getInt("early_days"), rs.getInt("set_count"),
                        rs.getInt("modern_set_count")));
            }
            return out;
        }
    }

    /**
     * A tracked game, for filter UI. {@code modernSince} is where its modern
     * era starts, null when every set counts; {@code earlyDays} is how long a
     * new card's prices count as early pricing. Both are carried so a screen
     * can say what its filters mean without restating the rules.
     */
    public record Game(int categoryId, String name, int productCount, LocalDate modernSince,
                       int earlyDays, int setCount, int modernSetCount) {
    }

    /** A set within a game, for the movers screen's set filter. */
    public record GameSet(int groupId, String name, LocalDate publishedOn, int productCount,
                          boolean modern) {
    }

    /** Sets belonging to one game, newest first. */
    public List<GameSet> sets(int categoryId) throws Exception {
        String sql = "SELECT group_id, name, published_on, product_count, is_modern FROM game_sets(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, categoryId);
            List<GameSet> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GameSet(rs.getInt("group_id"), rs.getString("name"),
                            localDate(rs.getDate("published_on")), rs.getInt("product_count"),
                            rs.getBoolean("is_modern")));
                }
            }
            return out;
        }
    }

    /**
     * Every set of a game with its current value and windowed return.
     *
     * One query, not one per set: there are 302 tracked groups, so calling
     * set_value()/set_return() per set would be 604 round trips. The bulk
     * function's numbers are cross-checked against those primitives rather
     * than assumed equal.
     *
     * categoryId null = every tracked game. despike replaces readings that
     * swung hard and reverted the next day with the median of their
     * neighbours; see game_set_overview(). Pass false for a raw two-point
     * comparison matching set_return(). launch says whether pre-release and
     * early prices count toward the change (value always counts them).
     */
    public List<SetOverview> setOverview(Integer categoryId, Integer days, LocalDate asOf,
                                        boolean despike, LaunchPricing launch) throws Exception {
        String sql = """
                SELECT group_id, set_name, game, published_on, n_priced, total_value,
                       n_common, total_start, total_end, pct_return,
                       top_product_id, top_name, top_sub_type, top_delta, top_share,
                       top_url, n_despiked, despike_usd, start_date, end_date, is_modern,
                       settles_on
                  FROM game_set_overview(?, ?, ?, ?,
                       p_include_prerelease => ?, p_include_early => ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (categoryId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, categoryId);
            setIntOrDefault(ps, 2, days, 30);
            setDate(ps, 3, asOf);
            ps.setBoolean(4, despike);
            ps.setBoolean(5, launch.includePreRelease());
            ps.setBoolean(6, launch.includeEarly());

            List<SetOverview> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SetOverview(
                            rs.getInt("group_id"),
                            rs.getString("set_name"),
                            rs.getString("game"),
                            localDate(rs.getDate("published_on")),
                            rs.getInt("n_priced"),
                            rs.getBigDecimal("total_value"),
                            rs.getInt("n_common"),
                            rs.getBigDecimal("total_start"),
                            rs.getBigDecimal("total_end"),
                            rs.getBigDecimal("pct_return"),
                            nullableInt(rs, "top_product_id"),
                            rs.getString("top_name"),
                            rs.getString("top_sub_type"),
                            rs.getBigDecimal("top_delta"),
                            rs.getBigDecimal("top_share"),
                            rs.getString("top_url"),
                            rs.getInt("n_despiked"),
                            rs.getBigDecimal("despike_usd"),
                            localDate(rs.getDate("start_date")),
                            localDate(rs.getDate("end_date")),
                            rs.getBoolean("is_modern"),
                            localDate(rs.getDate("settles_on"))));
                }
            }
            return out;
        }
    }

    /**
     * Biggest movers over a window. Every argument has a server-side default
     * in item_movers(); nulls here mean "use it" rather than "no filter", so
     * a caller that passes nothing still gets the sane default view (7 days,
     * gains, singles, $1 floor) instead of an unfiltered firehose.
     *
     * modernOnly and launch are passed by name so p_as_of, which the API never
     * sets, can stay at its default between them.
     */
    public List<Mover> movers(Integer days, Integer categoryId, Integer groupId,
                              boolean includeSealed, BigDecimal minPrice,
                              String direction, Integer smoothing, Integer limit,
                              boolean modernOnly, LaunchPricing launch)
            throws Exception {
        String sql = """
                SELECT product_id, name, card_number, rarity, set_name, game,
                       sub_type_name, is_sealed, then_price, now_price, pct_change, url
                  FROM item_movers(?, ?, ?, ?, ?, ?, ?, ?, p_modern_only => ?,
                       p_include_prerelease => ?, p_include_early => ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            setIntOrDefault(ps, 1, days, 7);
            if (categoryId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, categoryId);
            if (groupId == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, groupId);
            ps.setBoolean(4, includeSealed);
            ps.setBigDecimal(5, minPrice == null ? BigDecimal.ONE : minPrice);
            ps.setString(6, direction == null ? "up" : direction);
            setIntOrDefault(ps, 7, smoothing, 3);
            setIntOrDefault(ps, 8, limit, 50);
            ps.setBoolean(9, modernOnly);
            ps.setBoolean(10, launch.includePreRelease());
            ps.setBoolean(11, launch.includeEarly());

            List<Mover> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Mover(
                            rs.getInt("product_id"),
                            rs.getString("name"),
                            rs.getString("card_number"),
                            rs.getString("rarity"),
                            rs.getString("set_name"),
                            rs.getString("game"),
                            rs.getString("sub_type_name"),
                            rs.getBoolean("is_sealed"),
                            rs.getBigDecimal("then_price"),
                            rs.getBigDecimal("now_price"),
                            rs.getBigDecimal("pct_change"),
                            rs.getString("url")));
                }
            }
            return out;
        }
    }

    /**
     * getInt() maps SQL NULL to 0, which for a product_id is a real-looking
     * id rather than "absent", since a set with no priced rows would otherwise
     * report a top mover with id 0.
     */
    private static Integer nullableInt(ResultSet rs, String column) throws Exception {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static void setIntOrDefault(PreparedStatement ps, int idx, Integer v, int dflt)
            throws Exception {
        ps.setInt(idx, v == null ? dflt : v);
    }

    private static void setDate(PreparedStatement ps, int idx, LocalDate d) throws Exception {
        if (d == null) {
            ps.setNull(idx, Types.DATE);
        } else {
            ps.setObject(idx, d);
        }
    }

    private static LocalDate localDate(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    /**
     * How current each tracked game's price data is.
     *
     * Once, the scheduled pull failed silently for five days because a
     * launchd job denied access to its own repository exits without writing
     * anything, and an empty log looks exactly like a quiet success. The fix
     * was a check that reads the DATA rather than the exit
     * status, and this is what serves it to /api/health so the answer appears
     * where someone is already looking instead of in a log nobody opens.
     *
     * Per category, because one game stopping while another keeps arriving is
     * exactly what a single global MAX() would hide.
     *
     * The query's shape is load-bearing and non-obvious - the natural version
     * takes 22.5 seconds - so it lives in data_freshness() where the checks
     * and the API call the same definition rather than copies of it.
     */
    public List<DataFreshness> freshness() throws Exception {
        String sql = "SELECT * FROM data_freshness()";
        List<DataFreshness> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new DataFreshness(
                        rs.getInt("category_id"),
                        rs.getString("game"),
                        rs.getObject("newest", LocalDate.class),
                        rs.getInt("days_stale")));
            }
        }
        return out;
    }
}
