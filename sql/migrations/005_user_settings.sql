-- Settings: one row of preferences per user, behind the Settings screen.
--
-- Mirrors user_settings in schema.sql.
--
-- Per user, so it is ready for accounts. A user with no row gets the
-- defaults below, and the first save creates the row. Preferences are not
-- history, so this row is updated in place.
--
-- Held on the server rather than in the browser: settings kept in
-- localStorage vanish with the site data and differ per browser, and the
-- launch-pricing checkboxes had lived there until now.
--
-- Game rules (tracked_categories.modern_since and early_days) are NOT here.
-- They say what "modern" and "early" mean for the data, which today is the
-- same for everyone. If accounts ever need their own, that is a per-user
-- override of those, not a move.
CREATE TABLE IF NOT EXISTS user_settings (
    user_id            BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    include_prerelease BOOLEAN NOT NULL DEFAULT FALSE,
    include_early      BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
