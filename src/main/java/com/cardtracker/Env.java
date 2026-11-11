package com.cardtracker;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Env {

    private Env() {
    }

    public static String get(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** The database every entry point uses, overridable per environment variable. */
    public static String dbUrl() {
        return get("CARDTRACKER_DB_URL", "jdbc:postgresql://localhost:5433/cardtracker");
    }

    public static String dbUser() {
        return get("CARDTRACKER_DB_USER", "cardtracker");
    }

    public static String dbPass() {
        return get("CARDTRACKER_DB_PASS", "workspace-dev");
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(dbUrl(), dbUser(), dbPass());
    }
}
