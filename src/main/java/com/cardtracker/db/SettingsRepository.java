package com.cardtracker.db;

import com.cardtracker.model.UserSettings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;

/**
 * What the Settings screen reads and writes: a user's preferences, and each
 * game's rules for what counts as modern and how long early pricing lasts.
 *
 * Borrows its connection, like the other repositories.
 *
 * Both are updated in place, unlike the price history. A preference is not history,
 * and the game rules are definitions the whole app reads live (see
 * modern_group_ids() and launch_noise()), not observations to keep.
 */
public class SettingsRepository {

    private final Connection conn;

    public SettingsRepository(Connection conn) {
        this.conn = conn;
    }

    /** The user's preferences, or the defaults if they have never saved any. */
    public UserSettings get(long userId) throws Exception {
        String sql = """
                SELECT include_prerelease, include_early
                  FROM user_settings WHERE user_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return UserSettings.DEFAULTS;
                return new UserSettings(
                        rs.getBoolean("include_prerelease"), rs.getBoolean("include_early"));
            }
        }
    }

    /** Creates the row on first save, replaces it after. */
    public UserSettings save(long userId, UserSettings s) throws Exception {
        String sql = """
                INSERT INTO user_settings
                    (user_id, include_prerelease, include_early)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                    SET include_prerelease = EXCLUDED.include_prerelease,
                        include_early = EXCLUDED.include_early,
                        updated_at = now()
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setBoolean(2, s.includePreRelease());
            ps.setBoolean(3, s.includeEarly());
            ps.executeUpdate();
        }
        return get(userId);
    }

    /**
     * Sets a tracked game's rules. modernSince null means every set of the
     * game counts as modern. Returns false for a game that isn't tracked.
     */
    public boolean saveGameRules(int categoryId, LocalDate modernSince, int earlyDays)
            throws Exception {
        String sql = """
                UPDATE tracked_categories SET modern_since = ?, early_days = ?
                 WHERE category_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (modernSince == null) ps.setNull(1, Types.DATE); else ps.setObject(1, modernSince);
            ps.setInt(2, earlyDays);
            ps.setInt(3, categoryId);
            return ps.executeUpdate() == 1;
        }
    }
}
