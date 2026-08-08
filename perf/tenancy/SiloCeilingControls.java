import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * ADR 0010 §8 Q1, part two: the controls that separate "planning is expensive per branch" from
 * "planning is expensive the FIRST time a backend touches those branches". §8 Q1's load-bearing
 * sentence is that the per-branch planning cost does NOT amortize, and nothing in part one's
 * A-section isolates that, because a pooled connection is reused between runs.
 */
public final class SiloCeilingControls {

    private static final String URL =
            System.getenv().getOrDefault("TENANCY_BENCH_URL", "jdbc:postgresql://localhost:35433/smsone");
    private static final String USER = System.getenv().getOrDefault("TENANCY_BENCH_USER", "postgres");
    private static final String PASS = System.getenv().getOrDefault("TENANCY_BENCH_PASS", "postgres");

    private static final String TICKET_COLUMNS =
            "id, org_id, opener_person_id, subject, category, priority, status, assignee_person_id,"
                    + " first_response_at, first_response_due_at, resolution_due_at, escalated, version,"
                    + " created_at, created_by, updated_at, updated_by, deleted_at";

    public static void main(String[] args) throws Exception {
        int[] branchCounts = args.length > 0
                ? Arrays.stream(args[0].split(",")).mapToInt(Integer::parseInt).toArray()
                : new int[] {50, 100, 200};
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL);
        config.setUsername(USER);
        config.setPassword(PASS);
        config.setMaximumPoolSize(8);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            List<String> silos = silos(pool);
            out("fleet: tenant_pool + %d silos", silos.size());

            out("");
            out("== A3. Cold BACKEND vs warm backend: is the first-execution cost per-plan or per-backend? ==");
            out("branches\tmode\trun\tplanning_ms\texecution_ms");
            for (int n : branchCounts) {
                String sql = unionQuery(silos, n);
                // Each run on a BRAND NEW physical connection: nothing in relcache/catcache, nothing
                // in any plan cache. This is what a request landing on a freshly opened pool
                // connection pays.
                for (int run = 1; run <= 3; run++) {
                    try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
                        double[] r = explain(c, sql);
                        out("%d\tfresh-backend\t%d\t%.3f\t%.3f", n, run, r[0], r[1]);
                    }
                }
                // Then ten runs on ONE connection, which is what a pooled connection does all day.
                try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
                    for (int run = 1; run <= 10; run++) {
                        double[] r = explain(c, sql);
                        out("%d\tsame-backend\t%d\t%.3f\t%.3f", n, run, r[0], r[1]);
                    }
                }
            }

            out("");
            out("== A4. pgjdbc PreparedStatement, default prepareThreshold=5, one connection ==");
            out("branches\texecution\tclient_ms");
            for (int n : branchCounts) {
                String sql = unionQuery(silos, n).replace("status = 'OPEN'", "status = ?");
                int params = n;
                try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                        PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int run = 1; run <= 10; run++) {
                        for (int p = 1; p <= params; p++) {
                            ps.setString(p, "OPEN");
                        }
                        long t0 = System.nanoTime();
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                // drain
                            }
                        }
                        out("%d\t%d\t%.2f", n, run, (System.nanoTime() - t0) / 1_000_000.0);
                    }
                }
            }

            out("");
            out("== D2. The SHIPPED shape, clean: one statement per home, one transaction each ==");
            out("homes\trun\ttotal_ms\tper_home_ms");
            String oneHome = "select " + TICKET_COLUMNS
                    + " from ticket where deleted_at is null and status = ?"
                    + " order by created_at desc, id desc limit 21";
            for (int n : branchCounts) {
                for (int run = 1; run <= 3; run++) {
                    try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
                        c.setAutoCommit(false);
                        long t0 = System.nanoTime();
                        for (int i = 0; i < n; i++) {
                            String schema = i == 0 ? "tenant_pool" : silos.get(i - 1);
                            try (Statement s = c.createStatement()) {
                                s.execute("set search_path to \"" + schema + "\", ext");
                            }
                            try (PreparedStatement ps = c.prepareStatement(oneHome)) {
                                ps.setString(1, "OPEN");
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        // drain
                                    }
                                }
                            }
                            c.commit();
                        }
                        double total = (System.nanoTime() - t0) / 1_000_000.0;
                        out("%d\t%d\t%.1f\t%.3f", n, run, total, total / n);
                    }
                }
            }

            out("");
            out("== C2. Lock entries, by locktype and mode, at the top branch count ==");
            int top = Arrays.stream(branchCounts).max().orElse(200);
            try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    s.execute("explain (analyze, timing off) " + unionQuery(silos, top));
                    try (ResultSet rs = s.executeQuery(
                            "select locktype, mode, count(*) from pg_locks where pid = pg_backend_pid()"
                                    + " group by 1,2 order by 3 desc")) {
                        while (rs.next()) {
                            out("%d branches\t%s\t%s\t%d", top, rs.getString(1), rs.getString(2), rs.getInt(3));
                        }
                    }
                }
                c.rollback();
            }
        }
    }

    private static double[] explain(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("explain (analyze, timing off, format json) " + sql)) {
            rs.next();
            String json = rs.getString(1);
            return new double[] {number(json, "Planning Time"), number(json, "Execution Time")};
        }
    }

    private static double number(String json, String key) {
        int at = json.indexOf("\"" + key + "\":");
        if (at < 0) {
            return -1;
        }
        int start = at + key.length() + 3;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && "-0123456789.eE".indexOf(json.charAt(end)) >= 0) {
            end++;
        }
        return Double.parseDouble(json.substring(start, end));
    }

    private static String unionQuery(List<String> silos, int branches) {
        var sql = new StringBuilder();
        for (int i = 0; i < branches; i++) {
            String schema = i == 0 ? "tenant_pool" : "\"" + silos.get(i - 1) + "\"";
            if (i > 0) {
                sql.append("\nunion all\n");
            }
            sql.append("select ").append(TICKET_COLUMNS).append(" from ").append(schema).append(".ticket")
                    .append(" where deleted_at is null and status = 'OPEN'");
        }
        return "select * from (\n" + sql + "\n) q order by created_at desc, id desc limit 21";
    }

    private static List<String> silos(HikariDataSource pool) throws SQLException {
        var found = new ArrayList<String>();
        try (Connection c = pool.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "select schema_name from information_schema.schemata"
                                + " where schema_name ~ '^t_[0-9a-f]{32}$' order by schema_name")) {
            while (rs.next()) {
                found.add(rs.getString(1));
            }
        }
        return found;
    }

    private static void out(String format, Object... args) {
        System.out.println(String.format(Locale.ROOT, format, args));
    }
}
