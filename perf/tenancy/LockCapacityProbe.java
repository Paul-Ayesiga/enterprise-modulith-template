import java.sql.*;
import java.util.*;

/**
 * How many relation locks can ONE transaction actually hold before "out of shared memory"?
 * max_locks_per_transaction x max_connections is documented as an average, not a per-transaction
 * cap, so the only honest answer is measured.
 */
public final class LockCapacityProbe {
    private static final String URL =
            System.getenv().getOrDefault("TENANCY_BENCH_URL", "jdbc:postgresql://localhost:35433/smsone");
    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "postgres", "postgres")) {
            try (Statement s = c.createStatement()) {
                for (String p : new String[] {"max_locks_per_transaction", "max_connections",
                        "max_prepared_transactions", "shared_buffers"}) {
                    try (ResultSet rs = s.executeQuery("show " + p)) {
                        rs.next();
                        System.out.println(p + " = " + rs.getString(1));
                    }
                }
            }
            List<String> tables = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "select quote_ident(n.nspname) || '.' || quote_ident(t.relname)"
                         + " from pg_class t join pg_namespace n on n.oid = t.relnamespace"
                         + " where t.relkind = 'r'"
                         + "   and (n.nspname = 'tenant_pool' or n.nspname ~ '^t_[0-9a-f]{32}$'"
                         + "        or n.nspname = 'platform')")) {
                while (rs.next()) { tables.add(rs.getString(1)); }
            }
            System.out.println("candidate tables = " + tables.size());
            c.setAutoCommit(false);
            int touched = 0;
            int locks = 0;
            try (Statement s = c.createStatement()) {
                for (String table : tables) {
                    try {
                        s.execute("select 1 from " + table + " limit 0");
                        touched++;
                    } catch (SQLException failure) {
                        System.out.printf("FAILED after touching %d tables: %s (SQLSTATE %s)%n",
                                touched, failure.getMessage().split("\n")[0], failure.getSQLState());
                        System.out.printf("=> locks held at the wall (last successful count) = %d%n", locks);
                        return;
                    }
                    if (touched % 100 == 0) {
                        try (ResultSet rs = s.executeQuery(
                                "select count(*) from pg_locks where pid = pg_backend_pid()")) {
                            rs.next(); locks = rs.getInt(1);
                        }
                    }
                }
                try (ResultSet rs = s.executeQuery(
                        "select count(*) from pg_locks where pid = pg_backend_pid()")) {
                    rs.next(); locks = rs.getInt(1);
                }
            }
            System.out.printf("=> no failure: touched %d tables holding %d locks%n", touched, locks);
            c.rollback();
        }
    }
}
