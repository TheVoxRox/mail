import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Measures the correspondent typeahead against synthetic tables of growing size,
 * to answer the one question the table's own comment leaves open: how far above
 * "thousands of rows per account" the scan bounded by
 * {@code ux_correspondent_account_email} still serves a per-keystroke lookup.
 *
 * <p>
 * Run it with the sqlite-jdbc the backend itself resolves, so the driver and the
 * bundled SQLite are the ones production runs (check with
 * {@code mvn dependency:list -Dincludes=org.xerial:sqlite-jdbc}):
 *
 * <pre>
 * java -cp "$env:USERPROFILE\.m2\repository\org\xerial\sqlite-jdbc\&lt;version&gt;\sqlite-jdbc-&lt;version&gt;.jar" `
 *     scripts/measure-correspondent-typeahead.java "$env:TEMP\correspondent-bench" [rowCounts]
 * </pre>
 *
 * <p>
 * The first argument is a scratch directory for the generated databases (one per
 * size, left behind for inspection); the optional second is a comma-separated
 * list of rows per account. Results belong in PERFORMANCE_BASELINE.md
 * <b>with the machine named</b> — the two machines this repository alternates
 * between differ by roughly 2x.
 *
 * <p>
 * Two deliberate choices about fidelity. The schema, the pragmas and the query
 * are copied from V1__init.sql, application.properties and
 * {@code CorrespondentRepository#search} rather than reached through JPA: the
 * cost being measured is SQLite's, and a Spring context would only add its own.
 * That makes this a copy that can rot — when the query changes, this file has to
 * change with it, or its numbers describe a query nobody runs. The generated
 * rows are ASCII-folded on purpose, matching what the harvest stores.
 */
public class CorrespondentTypeaheadBenchmark {

    /** Table under test, verbatim from V1__init.sql minus its comments. */
    static final String DDL = """
            CREATE TABLE correspondent (
                id             INTEGER      PRIMARY KEY AUTOINCREMENT,
                account_id     INTEGER      NOT NULL,
                email          VARCHAR(255) NOT NULL,
                display_name   VARCHAR(255),
                sent_count     INTEGER      NOT NULL DEFAULT 0,
                received_count INTEGER      NOT NULL DEFAULT 0,
                last_seen_at   DATETIME     NOT NULL,
                FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
            )""";

    /** Mirrors CorrespondentRepository#search, with ROBOT_LOCAL_PARTS inlined. */
    static final String SEARCH = """
            SELECT * FROM correspondent c
            WHERE c.account_id = ?
              AND (c.email LIKE ? ESCAPE '\\'
                   OR lower(c.display_name) LIKE ? ESCAPE '\\')
              AND (c.sent_count > 0
                   OR substr(c.email, 1, instr(c.email, '@') - 1) NOT IN
                      ('no-reply','noreply','donotreply','do-not-reply','bounce','bounces','mailer-daemon','postmaster'))
            ORDER BY
                CASE WHEN c.sent_count > 0 THEN 0 ELSE 1 END,
                CASE WHEN c.email LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END,
                c.last_seen_at DESC,
                c.sent_count + c.received_count DESC,
                c.email
            LIMIT 20""";

    /** Mirrors CorrespondentRepository#upsert, the sync-path half of the load. */
    static final String UPSERT = """
            INSERT INTO correspondent (account_id, email, display_name, sent_count, received_count, last_seen_at)
            VALUES (1, ?, 'Bench Sighting', 0, 1, '2026-09-08 12:00:00')
            ON CONFLICT (account_id, email) DO UPDATE SET
                display_name   = COALESCE(excluded.display_name, correspondent.display_name),
                sent_count     = correspondent.sent_count + excluded.sent_count,
                received_count = correspondent.received_count + excluded.received_count,
                last_seen_at   = MAX(correspondent.last_seen_at, excluded.last_seen_at)""";

    static final String[] FIRST = { "jan", "petr", "jana", "eva", "tomas", "lucie", "martin", "karel", "alena", "david",
            "milan", "zuzana", "ondrej", "pavel", "hana", "radek", "marek", "iveta", "filip", "sona" };
    static final String[] LAST = { "novak", "svoboda", "novotny", "dvorak", "cerny", "prochazka", "kucera", "vesely",
            "horak", "nemec", "pokorny", "marek", "pospisil", "hajek", "jelinek", "kral", "ruzicka", "benes" };
    static final String[] DOMAIN = { "gmail.com", "seznam.cz", "outlook.com", "centrum.cz", "email.cz", "firma.cz",
            "company.example", "post.cz", "volny.cz", "icloud.com" };
    static final String[] ROBOT = { "no-reply", "noreply", "donotreply", "do-not-reply", "bounce", "bounces",
            "mailer-daemon", "postmaster" };

    /** Warmup iterations discarded before each timed set, and the timed set size. */
    static final int WARMUP = 50;
    static final int ITERATIONS = 200;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: measure-correspondent-typeahead.java <scratch-dir> [rowsPerAccount,...]");
            System.exit(2);
        }
        int[] sizes = args.length > 1 ? Arrays.stream(args[1].split(",")).map(String::trim).mapToInt(Integer::parseInt)
                .toArray() : new int[] { 1_000, 5_000, 20_000, 50_000, 100_000, 200_000, 300_000, 500_000 };
        System.out.printf("%9s %9s %9s %9s %9s %11s %12s%n", "rows/acct", "hit_p50", "hit_p95", "miss_p50", "miss_p95",
                "upsert_p50", "db_bytes");
        for (int size : sizes) {
            run(args[0], size);
        }
    }

    static void run(String dir, int rowsPerAccount) throws SQLException {
        File scratch = new File(dir);
        if (!scratch.isDirectory() && !scratch.mkdirs()) {
            throw new IllegalStateException("cannot create " + scratch);
        }
        File db = new File(scratch, "correspondent-bench-" + rowsPerAccount + ".db");
        if (db.exists() && !db.delete()) {
            throw new IllegalStateException("cannot delete " + db);
        }
        // Pragmas copied from spring.datasource.url in application.properties.
        String url = "jdbc:sqlite:" + db.getAbsolutePath()
                + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=ON&busy_timeout=5000&cache_size=-20000";
        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement statement = connection.createStatement()) {
                // Two accounts, so the leading account_id column has something to bound.
                statement.execute("CREATE TABLE accounts (id INTEGER PRIMARY KEY)");
                statement.execute("INSERT INTO accounts (id) VALUES (1), (2)");
                statement.execute(DDL);
                statement.execute(
                        "CREATE UNIQUE INDEX ux_correspondent_account_email ON correspondent (account_id, email)");
            }
            fill(connection, rowsPerAccount);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE");
            }

            double[] hit = timeSearch(connection, "jan");
            double[] miss = timeSearch(connection, "zxq");
            double upsert = timeUpsert(connection);
            long bytes = db.length() + new File(db.getAbsolutePath() + "-wal").length();
            System.out.printf("%9d %9.2f %9.2f %9.2f %9.2f %11.3f %12d%n", rowsPerAccount, hit[0], hit[1], miss[0],
                    miss[1], upsert, bytes);
            explain(connection);
        }
    }

    static void fill(Connection connection, int rowsPerAccount) throws SQLException {
        connection.setAutoCommit(false);
        Random random = new Random(42);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO correspondent (account_id, email, display_name, sent_count, received_count, last_seen_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            for (int account = 1; account <= 2; account++) {
                for (int i = 0; i < rowsPerAccount; i++) {
                    // Every tenth address is a robot one, so the NOT IN filter has work to do.
                    boolean robot = i % 10 == 0;
                    String local = robot ? ROBOT[random.nextInt(ROBOT.length)] + "+" + i
                            : FIRST[random.nextInt(FIRST.length)] + "." + LAST[random.nextInt(LAST.length)] + i;
                    insert.setInt(1, account);
                    insert.setString(2, local + "@" + DOMAIN[random.nextInt(DOMAIN.length)]);
                    // Some sightings carried a bare address, so display_name is nullable in practice.
                    insert.setString(3, robot && i % 20 == 0 ? null
                            : capitalize(FIRST[random.nextInt(FIRST.length)]) + " "
                                    + capitalize(LAST[random.nextInt(LAST.length)]));
                    insert.setInt(4, i % 7 == 0 ? 1 + random.nextInt(5) : 0);
                    insert.setInt(5, random.nextInt(30));
                    insert.setString(6, "2026-0" + (1 + random.nextInt(8)) + "-1" + random.nextInt(9) + " 10:00:00");
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        connection.commit();
        connection.setAutoCommit(true);
    }

    static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    static double[] timeSearch(Connection connection, String query) throws SQLException {
        List<Double> samples = new ArrayList<>();
        try (PreparedStatement search = connection.prepareStatement(SEARCH)) {
            for (int i = 0; i < WARMUP + ITERATIONS; i++) {
                long started = System.nanoTime();
                search.setLong(1, 1);
                search.setString(2, query + "%");
                search.setString(3, "%" + query + "%");
                search.setString(4, query + "%");
                try (ResultSet rows = search.executeQuery()) {
                    while (rows.next()) {
                        rows.getString("email");
                    }
                }
                double elapsedMs = (System.nanoTime() - started) / 1e6;
                if (i >= WARMUP) {
                    samples.add(elapsedMs);
                }
            }
        }
        return new double[] { percentile(samples, 50), percentile(samples, 95) };
    }

    static double timeUpsert(Connection connection) throws SQLException {
        List<Double> samples = new ArrayList<>();
        try (PreparedStatement upsert = connection.prepareStatement(UPSERT)) {
            for (int i = 0; i < WARMUP + ITERATIONS; i++) {
                long started = System.nanoTime();
                // Cycles over 25 addresses, so both branches of ON CONFLICT get exercised.
                upsert.setString(1, "bench.sighting" + (i % 25) + "@example.com");
                upsert.executeUpdate();
                double elapsedMs = (System.nanoTime() - started) / 1e6;
                if (i >= WARMUP) {
                    samples.add(elapsedMs);
                }
            }
        }
        return percentile(samples, 50);
    }

    static void explain(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet plan = statement.executeQuery("EXPLAIN QUERY PLAN " + SEARCH.replace("?", "1"))) {
            while (plan.next()) {
                System.out.println("    plan: " + plan.getString("detail"));
            }
        }
    }

    static double percentile(List<Double> samples, int percentile) {
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
