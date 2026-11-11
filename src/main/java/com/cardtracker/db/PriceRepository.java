package com.cardtracker.db;

import com.cardtracker.model.Category;
import com.cardtracker.model.Group;
import com.cardtracker.model.PriceSnapshot;
import com.cardtracker.model.Product;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes price history.
 *
 * The insert is ON CONFLICT DO NOTHING rather than DO UPDATE, on purpose.
 * Re-running today's pull is then harmless, but a bad run can never overwrite
 * a good observation that is already recorded. History is the asset; it is
 * append-only.
 */
public class PriceRepository {

    private static final String INSERT = """
            INSERT INTO price_snapshots
                (product_id, sub_type_name, snapshot_date,
                 market_price, low_price, mid_price, high_price, direct_low_price)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (product_id, sub_type_name, snapshot_date) DO NOTHING
            """;

    private final Connection conn;

    public PriceRepository(Connection conn) {
        this.conn = conn;
    }

    public int insertBatch(List<PriceSnapshot> snapshots) throws Exception {
        if (snapshots.isEmpty()) return 0;

        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
            for (PriceSnapshot s : snapshots) {
                ps.setInt(1, s.productId());
                ps.setString(2, s.subTypeName());
                ps.setObject(3, s.snapshotDate());
                setDecimal(ps, 4, s.marketPrice());
                setDecimal(ps, 5, s.lowPrice());
                setDecimal(ps, 6, s.midPrice());
                setDecimal(ps, 7, s.highPrice());
                setDecimal(ps, 8, s.directLowPrice());
                ps.addBatch();
            }
            int written = 0;
            for (int r : ps.executeBatch()) {
                if (r > 0) written += r;
            }
            conn.commit();
            return written;
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    private static void setDecimal(PreparedStatement ps, int idx, java.math.BigDecimal v)
            throws Exception {
        if (v == null) {
            ps.setNull(idx, Types.NUMERIC);
        } else {
            ps.setBigDecimal(idx, v);
        }
    }

    // --- catalog (categories / groups / products) --------------------------
    //
    // Unlike price_snapshots, these describe the catalog itself rather than a
    // day's observation, so they're upserted (ON CONFLICT DO UPDATE) instead
    // of append-only. products.group_id and products.category_id are foreign
    // keys, so a category and its group must be upserted before the product.

    private static final String UPSERT_CATEGORY = """
            INSERT INTO categories (category_id, name, modified_on)
            VALUES (?, ?, ?)
            ON CONFLICT (category_id) DO UPDATE
                SET name = EXCLUDED.name, modified_on = EXCLUDED.modified_on
            """;

    public void upsertCategory(Category c) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_CATEGORY)) {
            ps.setInt(1, c.categoryId());
            ps.setString(2, c.name());
            setTimestamp(ps, 3, c.modifiedOn());
            ps.executeUpdate();
        }
    }

    // COALESCE on the release date: if the catalog stops sending a date we
    // already hold, keep ours rather than forget it.
    private static final String UPSERT_GROUP = """
            INSERT INTO groups
                (group_id, category_id, name, abbreviation, is_supplemental, published_on, modified_on)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (group_id) DO UPDATE
                SET name = EXCLUDED.name, abbreviation = EXCLUDED.abbreviation,
                    is_supplemental = EXCLUDED.is_supplemental,
                    published_on = COALESCE(EXCLUDED.published_on, groups.published_on),
                    modified_on = EXCLUDED.modified_on
            """;

    public void upsertGroup(Group g) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_GROUP)) {
            ps.setInt(1, g.groupId());
            ps.setInt(2, g.categoryId());
            ps.setString(3, g.name());
            ps.setString(4, g.abbreviation());
            ps.setBoolean(5, g.isSupplemental());
            ps.setObject(6, g.publishedOn(), Types.DATE);
            setTimestamp(ps, 7, g.modifiedOn());
            ps.executeUpdate();
        }
    }

    private static final String UPSERT_PRODUCT = """
            INSERT INTO products
                (product_id, group_id, category_id, name, clean_name, image_url, url,
                 is_sealed, extended_data, modified_on)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (product_id) DO UPDATE
                SET name = EXCLUDED.name, clean_name = EXCLUDED.clean_name,
                    image_url = EXCLUDED.image_url, url = EXCLUDED.url,
                    is_sealed = EXCLUDED.is_sealed,
                    extended_data = EXCLUDED.extended_data, modified_on = EXCLUDED.modified_on
            """;

    public void upsertProduct(Product p) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_PRODUCT)) {
            ps.setInt(1, p.productId());
            ps.setInt(2, p.groupId());
            ps.setInt(3, p.categoryId());
            ps.setString(4, p.name());
            ps.setString(5, p.cleanName());
            ps.setString(6, p.imageUrl());
            ps.setString(7, p.url());
            ps.setBoolean(8, p.isSealed());
            ps.setString(9, p.extendedDataJson());
            setTimestamp(ps, 10, p.modifiedOn());
            ps.executeUpdate();
        }
    }

    /**
     * Mirror's timestamps are usually naive ISO strings, but a just-modified
     * one can come back with a trailing 'Z' (seen live 2026-08-12, on the
     * most-recently-modified group). 'Z' means zero UTC offset, and we're
     * not doing timezone conversion either way, so it's just stripped.
     */
    private static void setTimestamp(PreparedStatement ps, int idx, String isoLocal) throws Exception {
        if (isoLocal == null) {
            ps.setNull(idx, Types.TIMESTAMP);
        } else {
            String cleaned = isoLocal.endsWith("Z") ? isoLocal.substring(0, isoLocal.length() - 1) : isoLocal;
            ps.setObject(idx, LocalDateTime.parse(cleaned));
        }
    }

    // --- excluded products ---------------------------------------------------
    //
    // Products we deliberately never ingest or compute on (e.g. code cards).
    // Independent of `products` on purpose:
    // BackfillArchive only ever sees a bare product_id, never a name, so it
    // needs this table to still answer "is this excluded" even after the
    // product's row is gone from `products`. category_id is recorded so an
    // exclusion reason discovered for one game is never mistaken for a
    // universal rule about trading cards in general.

    private static final String UPSERT_EXCLUDED_PRODUCT = """
            INSERT INTO excluded_products (product_id, category_id, name, reason)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (product_id) DO NOTHING
            """;

    public void upsertExcludedProduct(int productId, int categoryId, String name, String reason) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_EXCLUDED_PRODUCT)) {
            ps.setInt(1, productId);
            ps.setInt(2, categoryId);
            ps.setString(3, name);
            ps.setString(4, reason);
            ps.executeUpdate();
        }
    }

    /**
     * Global, not scoped to a category, since a TCGplayer product_id belongs to
     * exactly one category already, so filtering by category here would be
     * redundant, not more correct.
     */
    public Set<Integer> excludedProductIds() throws Exception {
        Set<Integer> ids = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT product_id FROM excluded_products");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids;
    }

    // --- tracked categories ---------------------------------------------------
    //
    // Which tcgcsv categories App/BackfillArchive actually pull. Data, not
    // code, so adding a card game is "INSERT a row," not a rebuild.

    public List<Integer> trackedCategoryIds() throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT category_id FROM tracked_categories ORDER BY category_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids;
    }

    // --- run logging -------------------------------------------------------

    /**
     * Lets a batch job (BackfillArchive) skip a day without downloading or
     * parsing anything for it. Only "ok" counts, since a day that previously
     * failed partway through is retried in full, not resumed mid-day.
     */
    public boolean hasSuccessfulRun(String source, LocalDate snapshotDate) throws Exception {
        String sql = "SELECT 1 FROM ingest_runs WHERE source = ? AND snapshot_date = ? AND status = 'ok' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setObject(2, snapshotDate);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Is this date already covered for this category, by EITHER the live pull
     * or the archive backfill?
     *
     * hasSuccessfulRun() matches one exact source string, which was right
     * while the live pull only ever asked about a date nothing else could
     * have filled, i.e. "today". Now that the date comes from the mirror rather
     * than the clock, a run started before the daily refresh legitimately
     * asks about yesterday, and yesterday is usually already in hand from
     * `tcgcsv-archive-N`. Without this the pull would spend ~436 requests
     * per category re-fetching rows that ON CONFLICT DO NOTHING then
     * discards.
     */
    public boolean hasDateCovered(int categoryId, LocalDate snapshotDate) throws Exception {
        String sql = "SELECT 1 FROM ingest_runs WHERE source IN (?, ?) "
                + "AND snapshot_date = ? AND status = 'ok' LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "tcgcsv-" + categoryId);
            ps.setString(2, "tcgcsv-archive-" + categoryId);
            ps.setObject(3, snapshotDate);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public long startRun(String source, LocalDate snapshotDate) throws Exception {
        String sql = "INSERT INTO ingest_runs (source, snapshot_date) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, source);
            ps.setObject(2, snapshotDate);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void finishRun(long runId, String status, int requests, int rows, String notes)
            throws Exception {
        String sql = """
                UPDATE ingest_runs
                   SET finished_at = now(), status = ?, request_count = ?,
                       rows_written = ?, notes = ?
                 WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, requests);
            ps.setInt(3, rows);
            ps.setString(4, notes);
            ps.setLong(5, runId);
            ps.executeUpdate();
        }
    }
}
