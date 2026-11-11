package com.cardtracker.sql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

/**
 * A throwaway database per test class, built from the real schema.
 *
 * The precedent is a scratch Postgres container: full realism,
 * zero risk. The same reasoning applies here and matters more, because these
 * tests insert prices, and `price_snapshots` is append-only with no way back.
 * **No test may ever be pointed at the working database.** The name is
 * randomised per run so a test cannot collide with a previous one, and it is
 * dropped in teardown.
 *
 * Loads sql/schema.sql and sql/functions.sql verbatim rather than a
 * hand-maintained copy. A fixture schema that drifts from the real one tests
 * something that does not exist.
 *
 * Skips rather than fails when no container is running: the Java unit tests
 * deliberately need nothing, and a developer without Docker up should still
 * get a useful `mvn test` rather than a wall of red.
 */
public final class TestDatabase implements AutoCloseable {

    private static final String HOST_URL =
            System.getenv().getOrDefault("CARDTRACKER_TEST_DB_URL",
                    "jdbc:postgresql://localhost:5433/postgres");
    private static final String USER =
            System.getenv().getOrDefault("CARDTRACKER_DB_USER", "cardtracker");
    private static final String PASS =
            System.getenv().getOrDefault("CARDTRACKER_DB_PASS", "workspace-dev");

    private final String dbName;
    private final String url;
    private final Connection conn;

    private TestDatabase(String dbName, String url, Connection conn) {
        this.dbName = dbName;
        this.url = url;
        this.conn = conn;
    }

    /** JDBC URL of the throwaway database, for code that opens its own connections. */
    public String url() {
        return url;
    }

    public String user() {
        return USER;
    }

    public String pass() {
        return PASS;
    }

    /** True when a Postgres we can create databases on is reachable. */
    public static boolean available() {
        try (Connection c = DriverManager.getConnection(HOST_URL, USER, PASS)) {
            return c.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    public static TestDatabase create() throws Exception {
        String name = "cardtracker_test_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection admin = DriverManager.getConnection(HOST_URL, USER, PASS);
             Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }

        String url = HOST_URL.substring(0, HOST_URL.lastIndexOf('/') + 1) + name;
        Connection c = DriverManager.getConnection(url, USER, PASS);

        Path root = Path.of("").toAbsolutePath().normalize();
        try (Statement st = c.createStatement()) {
            st.execute(Files.readString(root.resolve("sql/schema.sql")));
            st.execute(Files.readString(root.resolve("sql/functions.sql")));
        }
        return new TestDatabase(name, url, c);
    }

    public Connection conn() {
        return conn;
    }

    public void exec(String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** Single scalar as a String, or null. Keeps assertions terse. */
    public String scalar(String sql) throws Exception {
        try (Statement st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Override
    public void close() throws Exception {
        conn.close();
        try (Connection admin = DriverManager.getConnection(HOST_URL, USER, PASS);
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
        }
    }
}
