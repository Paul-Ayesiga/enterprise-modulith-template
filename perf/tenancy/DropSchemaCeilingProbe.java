import java.sql.*;
import java.util.*;

/** ADR 0010 §5.12: how many `drop schema ... cascade` fit in one transaction? Rolled back, so non-destructive. */
public final class DropSchemaCeilingProbe {
    private static final String URL =
            System.getenv().getOrDefault("TENANCY_BENCH_URL", "jdbc:postgresql://localhost:35433/smsone");
    public static void main(String[] a) throws Exception {
        List<String> silos = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(URL, "postgres", "postgres");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select schema_name from information_schema.schemata"
                     + " where schema_name ~ '^t_[0-9a-f]{32}$' order by schema_name")) {
            while (rs.next()) { silos.add(rs.getString(1)); }
        }
        try (Connection c = DriverManager.getConnection(URL, "postgres", "postgres")) {
            c.setAutoCommit(false);
            int dropped = 0;
            int locks = 0;
            try (Statement s = c.createStatement()) {
                for (String schema : silos) {
                    try {
                        s.execute("drop schema \"" + schema + "\" cascade");
                        dropped++;
                    } catch (SQLException failure) {
                        System.out.printf("FAILED on drop #%d: %s (SQLSTATE %s)%n",
                                dropped + 1, failure.getMessage().split("\n")[0], failure.getSQLState());
                        System.out.printf("=> %d schemas dropped in one transaction, %d locks held%n", dropped, locks);
                        return;
                    }
                    try (ResultSet rs = s.executeQuery(
                            "select count(*) from pg_locks where pid = pg_backend_pid()")) {
                        rs.next(); locks = rs.getInt(1);
                    }
                    if (dropped <= 3 || dropped % 10 == 0) {
                        System.out.printf("dropped=%d locks=%d locks_per_schema=%.1f%n",
                                dropped, locks, locks / (double) dropped);
                    }
                }
            }
            System.out.printf("=> no failure: %d schemas in one transaction, %d locks%n", dropped, locks);
            c.rollback();
        }
    }
}
