import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import ug.co.smsone.shared.persistence.TenantMigrationRunner;

/**
 * ADR 0010 §8 Q1 re-measurement. Builds N REAL tenant schemas through the real tenant migration
 * sequence (TenantMigrationRunner), fills them with real rows, then replays the UNION benchmark and
 * measures the footprint claims of §1 / §5.10 / §4.4.
 *
 * Stages are separate so a long run can be resumed:
 *   migrate <n>   create n silos + run the real fan-out, report per-schema migration ms
 *   seed          populate tenant_pool + every silo with ticket / ticket_message / audit_log rows
 *   bench         the UNION benchmark at N = 50 / 100 / 200 (and whatever else is present)
 *   footprint     relation files, catalog size, JDBC metadata time
 */
public final class SiloCeilingBench {

    private static final String URL =
            System.getenv().getOrDefault("TENANCY_BENCH_URL", "jdbc:postgresql://localhost:35433/smsone");
    private static final String USER = System.getenv().getOrDefault("TENANCY_BENCH_USER", "postgres");
    private static final String PASS = System.getenv().getOrDefault("TENANCY_BENCH_PASS", "postgres");

    /** The ticket columns a UNION ALL branch must project, in mapping order. */
    private static final String TICKET_COLUMNS =
            "id, org_id, opener_person_id, subject, category, priority, status, assignee_person_id,"
                    + " first_response_at, first_response_due_at, resolution_due_at, escalated, version,"
                    + " created_at, created_by, updated_at, updated_by, deleted_at";

    public static void main(String[] args) throws Exception {
        String stage = args.length > 0 ? args[0] : "all";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL);
        config.setUsername(USER);
        config.setPassword(PASS);
        config.setMaximumPoolSize(24);
        config.setAutoCommit(true);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            switch (stage) {
                case "migrate" -> migrate(pool, Integer.parseInt(args[1]));
                case "seed" -> seed(pool);
                case "bench" -> bench(pool, args);
                case "footprint" -> footprint(pool);
                case "catalogbase" -> catalogSnapshot(pool, "BASELINE");
                default -> throw new IllegalArgumentException("unknown stage " + stage);
            }
        }
    }

    // ---------------------------------------------------------------- migrate

    private static void migrate(HikariDataSource pool, int silos) throws Exception {
        TenantMigrationRunner runner = TenantMigrationRunner.fromClasspath(pool, 8);

        long t0 = System.nanoTime();
        TenantMigrationRunner.SchemaResult platform = runner.migratePlatform(TenantMigrationRunner.Mode.MIGRATE);
        out("platform sequence: applied=%d ms=%d error=%s", platform.applied(), platform.millis(), platform.error());

        // tenant_pool through the real fan-out, on its own, so its cost is separable.
        long poolMs = System.nanoTime();
        var poolManifest = runner.fanOut(TenantMigrationRunner.Mode.MIGRATE, List.of("tenant_pool"));
        out("tenant_pool: %s (%d ms wall)", poolManifest.tenants().get(0), (System.nanoTime() - poolMs) / 1_000_000);

        catalogSnapshot(pool, "AFTER_PLATFORM_AND_POOL");

        // The silos. Names are derived exactly the way TenantSchemas does it, from an org uuid.
        List<String> schemas = new ArrayList<>();
        List<UUID> orgs = new ArrayList<>();
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            for (int i = 0; i < silos; i++) {
                UUID org = UUID.randomUUID();
                String schema = "t_" + org.toString().replace("-", "");
                orgs.add(org);
                schemas.add(schema);
                s.execute("create schema if not exists \"" + schema + "\"");
                s.execute("insert into platform.tenant_placement (org_id, schema_name, state, updated_at)"
                        + " values ('" + org + "', '" + schema + "', 'ACTIVE', now())"
                        + " on conflict (org_id) do nothing");
            }
        }
        out("created %d silo schemas + placement rows", silos);

        long fanStart = System.nanoTime();
        var manifest = runner.fanOut(TenantMigrationRunner.Mode.MIGRATE, schemas);
        long fanMs = (System.nanoTime() - fanStart) / 1_000_000;
        long failures = manifest.tenants().stream().filter(TenantMigrationRunner.SchemaResult::failed).count();
        var perSchema = manifest.tenants().stream()
                .mapToLong(TenantMigrationRunner.SchemaResult::millis).summaryStatistics();
        out("fan-out: schemas=%d workers=8 wall=%d ms failures=%d perSchemaMs min=%d avg=%.1f max=%d",
                schemas.size(), fanMs, failures, perSchema.getMin(), perSchema.getAverage(), perSchema.getMax());
        manifest.failures().forEach(f -> out("  FAILED %s: %s", f.schema(), f.error()));
        out("total migrate wall ms=%d", (System.nanoTime() - t0) / 1_000_000);
    }

    // ------------------------------------------------------------------- seed

    private static void seed(HikariDataSource pool) throws Exception {
        List<String> silos = silos(pool);
        out("seeding tenant_pool (%d pooled orgs) + %d silos", POOL_ORGS, silos.size());

        long t0 = System.nanoTime();
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.setQueryTimeout(0);
            // tenant_pool: many orgs sharing one schema, which is what makes org_id selective there.
            s.execute("set search_path to tenant_pool, ext");
            s.execute("truncate ticket, ticket_message, audit_log");
            s.execute("""
                    insert into ticket (id, org_id, opener_person_id, subject, category, priority, status,
                                        first_response_due_at, resolution_due_at, escalated, version,
                                        created_at, deleted_at)
                    select gen_random_uuid(),
                           orgs.org_id,
                           gen_random_uuid(),
                           'Pooled ticket ' || g,
                           (array['BILLING','TECHNICAL','ACCOUNT','OTHER'])[1 + (g %% 4)],
                           (array['P1','P2','P3','P4'])[1 + (g %% 4)],
                           (array['OPEN','IN_PROGRESS','WAITING_ON_CUSTOMER','RESOLVED','CLOSED'])[1 + ((g + orgs.n) %% 5)],
                           now() - ((g * 7) || ' minutes')::interval + interval '4 hours',
                           now() - ((g * 7) || ' minutes')::interval + interval '2 days',
                           false, 0,
                           now() - ((g * 7 + orgs.n) || ' minutes')::interval,
                           case when (g + orgs.n) %% 20 = 0 then now() - interval '3 days' end
                    from (select gen_random_uuid() as org_id, n from generate_series(1, %d) n) orgs,
                         generate_series(1, %d) g
                    """.formatted(POOL_ORGS, POOL_TICKETS_PER_ORG));
            out("  tenant_pool tickets: %d ms", ms(t0));

            long t1 = System.nanoTime();
            s.execute("""
                    insert into ticket_message (id, ticket_id, author_person_id, body, internal, created_at)
                    select gen_random_uuid(), t.id, gen_random_uuid(), 'message ' || m, m = 2, t.created_at
                    from ticket t, generate_series(1, 3) m
                    """);
            out("  tenant_pool ticket_message: %d ms", ms(t1));

            long t2 = System.nanoTime();
            s.execute("""
                    insert into audit_log (id, org_id, action, actor_person_id, target, to_state,
                                           occurred_at, version, created_at)
                    select gen_random_uuid(), t.org_id, 'support.ticket_opened', t.opener_person_id,
                           t.id::text, 'seeded', t.created_at, 0, t.created_at
                    from ticket t
                    """);
            out("  tenant_pool audit_log: %d ms", ms(t2));

            s.execute("analyze ticket, ticket_message, audit_log");
            out("  tenant_pool analyze done, total %d ms", ms(t0));
        }

        // Silos: one org each, so org_id has exactly one distinct value — the case §4.1 says makes the
        // org-prefixed indexes degenerate.
        long t3 = System.nanoTime();
        int done = 0;
        for (String schema : silos) {
            UUID org = orgOf(pool, schema);
            try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
                s.execute("set search_path to \"" + schema + "\", ext");
                s.execute("truncate ticket, ticket_message, audit_log");
                s.execute("""
                        insert into ticket (id, org_id, opener_person_id, subject, category, priority, status,
                                            first_response_due_at, resolution_due_at, escalated, version,
                                            created_at, deleted_at)
                        select gen_random_uuid(), '%s', gen_random_uuid(), 'Silo ticket ' || g,
                               (array['BILLING','TECHNICAL','ACCOUNT','OTHER'])[1 + (g %% 4)],
                               (array['P1','P2','P3','P4'])[1 + (g %% 4)],
                               (array['OPEN','IN_PROGRESS','WAITING_ON_CUSTOMER','RESOLVED','CLOSED'])[1 + (g %% 5)],
                               now() - ((g * 11) || ' minutes')::interval + interval '4 hours',
                               now() - ((g * 11) || ' minutes')::interval + interval '2 days',
                               false, 0,
                               now() - ((g * 11) || ' minutes')::interval,
                               case when g %% 20 = 0 then now() - interval '3 days' end
                        from generate_series(1, %d) g
                        """.formatted(org, SILO_TICKETS));
                s.execute("""
                        insert into ticket_message (id, ticket_id, author_person_id, body, internal, created_at)
                        select gen_random_uuid(), t.id, gen_random_uuid(), 'message ' || m, m = 2, t.created_at
                        from ticket t, generate_series(1, 3) m
                        """);
                s.execute("""
                        insert into audit_log (id, org_id, action, actor_person_id, target, to_state,
                                               occurred_at, version, created_at)
                        select gen_random_uuid(), t.org_id, 'support.ticket_opened', t.opener_person_id,
                               t.id::text, 'seeded', t.created_at, 0, t.created_at
                        from ticket t
                        """);
                s.execute("analyze ticket, ticket_message, audit_log");
            }
            if (++done % 25 == 0) {
                out("  silos seeded: %d/%d (%d ms)", done, silos.size(), ms(t3));
            }
        }
        out("silo seeding complete: %d schemas, %d ms", silos.size(), ms(t3));
        rowCounts(pool);
    }

    private static final int POOL_ORGS = 4800;
    private static final int POOL_TICKETS_PER_ORG = 25;
    private static final int SILO_TICKETS = 1000;

    // ------------------------------------------------------------------ bench

    private static void bench(HikariDataSource pool, String[] args) throws Exception {
        List<String> silos = silos(pool);
        out("fleet: tenant_pool + %d silos", silos.size());
        int[] branchCounts = args.length > 1
                ? java.util.Arrays.stream(args[1].split(",")).mapToInt(Integer::parseInt).toArray()
                : new int[] {1, 10, 50, 100, 200};

        out("");
        out("== A. UNION ALL fan-out, cold plan each execution (EXPLAIN ANALYZE) ==");
        out("branches\trun\tplanning_ms\texecution_ms\tclient_ms\tquery_text_bytes");
        for (int n : branchCounts) {
            if (n > silos.size() + 1) {
                continue;
            }
            String sql = unionQuery(silos, n, false);
            for (int run = 1; run <= 6; run++) {
                double[] r = explainAnalyze(pool, sql);
                out("%d\t%d\t%.3f\t%.3f\t%.1f\t%d", n, run, r[0], r[1], r[2], sql.length());
            }
        }

        out("");
        out("== A2. Same, with the keyset cursor predicate (page 2) ==");
        out("branches\trun\tplanning_ms\texecution_ms\tclient_ms");
        for (int n : branchCounts) {
            if (n > silos.size() + 1) {
                continue;
            }
            String sql = unionQuery(silos, n, true);
            for (int run = 1; run <= 3; run++) {
                double[] r = explainAnalyze(pool, sql);
                out("%d\t%d\t%.3f\t%.3f\t%.1f", n, run, r[0], r[1], r[2]);
            }
        }

        out("");
        out("== B. Does planning amortize? server-side PREPARE + 8 EXECUTEs ==");
        out("branches\texecute_n\tclient_ms\tplanning_ms");
        for (int n : branchCounts) {
            if (n > silos.size() + 1) {
                continue;
            }
            preparedRun(pool, silos, n);
        }

        out("");
        out("== C. Lock entries held by one fan-out transaction ==");
        out("branches\tlocks_total\tlocks_relation\tlocks_per_branch");
        int baseline = lockBaseline(pool);
        out("# empty-transaction baseline locks = %d", baseline);
        for (int n : branchCounts) {
            if (n > silos.size() + 1) {
                continue;
            }
            int[] locks = locksFor(pool, unionQuery(silos, n, false));
            out("%d\t%d\t%d\t%.3f", n, locks[0], locks[1], (locks[0] - baseline) / (double) n);
        }

        out("");
        out("== D. The SHIPPED shape: TicketFanOut's one-statement-per-home merge ==");
        out("homes\ttotal_client_ms\tavg_per_home_ms\tmax_locks_in_any_transaction");
        for (int n : branchCounts) {
            if (n > silos.size() + 1) {
                continue;
            }
            perHomeRun(pool, silos, n);
        }
    }

    /**
     * The UNION ALL form ADR 0010 §5.1 benchmarked: one branch per home, one outer sort, one limit.
     * Branch 1 is always tenant_pool.
     */
    private static String unionQuery(List<String> silos, int branches, boolean keyset) {
        var sql = new StringBuilder();
        for (int i = 0; i < branches; i++) {
            String schema = i == 0 ? "tenant_pool" : "\"" + silos.get(i - 1) + "\"";
            if (i > 0) {
                sql.append("\nunion all\n");
            }
            sql.append("select ").append(TICKET_COLUMNS).append(" from ").append(schema).append(".ticket")
                    .append(" where deleted_at is null and status = 'OPEN'");
            if (keyset) {
                sql.append(" and (created_at, id) < (now() - interval '10 days', "
                        + "'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)");
            }
        }
        return "select * from (\n" + sql + "\n) q order by created_at desc, id desc limit 21";
    }

    private static double[] explainAnalyze(HikariDataSource pool, String sql) throws Exception {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.setQueryTimeout(0);
            long t0 = System.nanoTime();
            try (ResultSet rs = s.executeQuery("explain (analyze, timing off, format json) " + sql)) {
                rs.next();
                String json = rs.getString(1);
                double client = (System.nanoTime() - t0) / 1_000_000.0;
                return new double[] {jsonNumber(json, "Planning Time"), jsonNumber(json, "Execution Time"), client};
            }
        }
    }

    private static double jsonNumber(String json, String key) {
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
        return Double.parseDouble(json.substring(start, end).trim());
    }

    /**
     * The claim under test is "planning does not amortize — warm re-runs pay it again". A server-side
     * PREPARE is the only thing that could amortize it, so this is the honest test of that sentence.
     */
    private static void preparedRun(HikariDataSource pool, List<String> silos, int n) throws Exception {
        // Parameterized, which is what Spring Data + pgjdbc actually send: `status = $1`. Postgres
        // custom-plans the first five executions and only then considers a generic plan, so eight is
        // the smallest run that can show the switch.
        String sql = unionQuery(silos, n, false).replace("status = 'OPEN'", "status = $1");
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.setQueryTimeout(0);
            s.execute("prepare silo_bench_stmt(text) as " + sql);
            for (int i = 1; i <= 8; i++) {
                long t0 = System.nanoTime();
                double planning;
                try (ResultSet rs = s.executeQuery(
                        "explain (analyze, timing off, format json) execute silo_bench_stmt('OPEN')")) {
                    rs.next();
                    planning = jsonNumber(rs.getString(1), "Planning Time");
                }
                out("%d\t%d\t%.1f\t%.3f", n, i, (System.nanoTime() - t0) / 1_000_000.0, planning);
            }
            s.execute("deallocate silo_bench_stmt");
        }
    }

    private static int lockBaseline(HikariDataSource pool) throws Exception {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("select count(*) from pg_locks where pid = pg_backend_pid()")) {
                    rs.next();
                    int n = rs.getInt(1);
                    c.rollback();
                    return n;
                }
            }
        }
    }

    private static int[] locksFor(HikariDataSource pool, String sql) throws Exception {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.setQueryTimeout(0);
                try (ResultSet rs = s.executeQuery("explain (analyze, timing off, format json) " + sql)) {
                    rs.next();
                }
                int total;
                int relation;
                try (ResultSet rs = s.executeQuery(
                        "select count(*), count(*) filter (where locktype = 'relation')"
                                + " from pg_locks where pid = pg_backend_pid()")) {
                    rs.next();
                    total = rs.getInt(1);
                    relation = rs.getInt(2);
                }
                c.rollback();
                return new int[] {total, relation};
            }
        }
    }

    /** What TicketFanOut actually does: the same one-home query once per home, each its own transaction. */
    private static void perHomeRun(HikariDataSource pool, List<String> silos, int homes) throws Exception {
        String oneHome = "select " + TICKET_COLUMNS + " from ticket where deleted_at is null and status = 'OPEN'"
                + " order by created_at desc, id desc limit 21";
        long t0 = System.nanoTime();
        int maxLocks = 0;
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                for (int i = 0; i < homes; i++) {
                    String schema = i == 0 ? "tenant_pool" : silos.get(i - 1);
                    s.execute("set search_path to \"" + schema + "\", ext");
                    try (ResultSet rs = s.executeQuery(oneHome)) {
                        while (rs.next()) {
                            // drain
                        }
                    }
                    if (i == homes - 1) {
                        // Probed once, on the last home only: a pg_locks scan per home would land
                        // inside the timing and is what made the first run of this section unusable.
                        try (ResultSet rs = s.executeQuery(
                                "select count(*) from pg_locks where pid = pg_backend_pid()")) {
                            rs.next();
                            maxLocks = Math.max(maxLocks, rs.getInt(1));
                        }
                    }
                    // Each home is its own transaction in TicketFanOut (@Transactional per branch).
                    c.commit();
                }
            }
        }
        double total = (System.nanoTime() - t0) / 1_000_000.0;
        out("%d\t%.1f\t%.3f\t%d", homes, total, total / homes, maxLocks);
    }

    // -------------------------------------------------------------- footprint

    private static void footprint(HikariDataSource pool) throws Exception {
        List<String> silos = silos(pool);
        out("== E. Relation footprint per tenant schema (%d silos present) ==", silos.size());

        // A silo that was migrated and never written to: the "empty relation files" claim.
        String empty = "t_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("create schema \"" + empty + "\"");
        }
        TenantMigrationRunner.fromClasspath(pool, 1)
                .fanOut(TenantMigrationRunner.Mode.MIGRATE, List.of(empty));

        report(pool, "relations in one EMPTY migrated silo", """
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = '%s'""".formatted(empty));
        report(pool, "  of which tables", """
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = '%s' and c.relkind = 'r'""".formatted(empty));
        report(pool, "  of which indexes", """
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = '%s' and c.relkind = 'i'""".formatted(empty));
        report(pool, "  toast relations it owns", """
                select count(*) from pg_class t
                 where t.relnamespace = 'pg_toast'::regnamespace
                   and exists (select 1 from pg_class o join pg_namespace n on n.oid = o.relnamespace
                               where n.nspname = '%s' and o.reltoastrelid = t.oid)""".formatted(empty));
        report(pool, "bytes: sum(pg_total_relation_size) over the EMPTY silo", """
                select coalesce(sum(pg_total_relation_size(c.oid)), 0)
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = '%s' and c.relkind in ('r','p','m')""".formatted(empty));
        report(pool, "bytes: same, seeded silo (ticket/ticket_message/audit_log filled)", """
                select coalesce(sum(pg_total_relation_size(c.oid)), 0)
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = '%s' and c.relkind in ('r','p','m')""".formatted(silos.get(0)));
        report(pool, "bytes: tenant_pool", """
                select coalesce(sum(pg_total_relation_size(c.oid)), 0)
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'tenant_pool' and c.relkind in ('r','p','m')""");
        report(pool, "bytes: platform schema", """
                select coalesce(sum(pg_total_relation_size(c.oid)), 0)
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'platform' and c.relkind in ('r','p','m')""");
        report(pool, "database size", "select pg_database_size(current_database())");

        catalogSnapshot(pool, "WITH_" + silos.size() + "_SILOS");

        out("");
        out("== G. JDBC metadata on JVM start (pgjdbc getColumns, unfiltered) ==");
        for (int run = 1; run <= 3; run++) {
            try (Connection c = pool.getConnection()) {
                DatabaseMetaData md = c.getMetaData();
                long t0 = System.nanoTime();
                int rows = 0;
                try (ResultSet rs = md.getColumns(null, null, "%", "%")) {
                    while (rs.next()) {
                        rows++;
                    }
                }
                out("run %d unfiltered getColumns: rows=%d ms=%.1f", run, rows, (System.nanoTime() - t0) / 1_000_000.0);
            }
        }
        for (int run = 1; run <= 2; run++) {
            try (Connection c = pool.getConnection()) {
                DatabaseMetaData md = c.getMetaData();
                long t0 = System.nanoTime();
                int rows = 0;
                try (ResultSet rs = md.getColumns(null, "tenant_pool", "%", "%")) {
                    while (rs.next()) {
                        rows++;
                    }
                }
                out("run %d schema-filtered getColumns(tenant_pool): rows=%d ms=%.1f",
                        run, rows, (System.nanoTime() - t0) / 1_000_000.0);
            }
        }
    }

    private static void catalogSnapshot(HikariDataSource pool, String label) throws Exception {
        out("");
        out("== F. Catalog snapshot [%s] ==", label);
        report(pool, "schemas (tenant_pool + silos)",
                "select count(*) from information_schema.schemata"
                        + " where schema_name = 'tenant_pool' or schema_name ~ '^t_[0-9a-f]{32}$'");
        report(pool, "pg_class rows", "select count(*) from pg_class");
        report(pool, "pg_attribute rows", "select count(*) from pg_attribute");
        report(pool, "pg_index rows", "select count(*) from pg_index");
        report(pool, "pg_depend rows", "select count(*) from pg_depend");
        report(pool, "pg_type rows", "select count(*) from pg_type");
        report(pool, "pg_constraint rows", "select count(*) from pg_constraint");
        report(pool, "pg_attrdef rows", "select count(*) from pg_attrdef");
        report(pool, "pg_statistic rows", "select count(*) from pg_statistic");
        report(pool, "catalog bytes (pg_catalog relations)",
                "select coalesce(sum(pg_total_relation_size(c.oid)), 0) from pg_class c"
                        + " where c.relnamespace = 'pg_catalog'::regnamespace and c.relkind in ('r','p','m')");
        report(pool, "database size", "select pg_database_size(current_database())");
    }

    // ------------------------------------------------------------------ utils

    private static List<String> silos(HikariDataSource pool) throws Exception {
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

    private static UUID orgOf(HikariDataSource pool, String schema) throws Exception {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                        "select org_id from platform.tenant_placement where schema_name = '" + schema + "'")) {
            return rs.next() ? UUID.fromString(rs.getString(1)) : UUID.randomUUID();
        }
    }

    private static void rowCounts(HikariDataSource pool) throws Exception {
        report(pool, "tickets in tenant_pool", "select count(*) from tenant_pool.ticket");
        report(pool, "ticket_message in tenant_pool", "select count(*) from tenant_pool.ticket_message");
        report(pool, "distinct orgs in tenant_pool.ticket", "select count(distinct org_id) from tenant_pool.ticket");
    }

    private static void report(HikariDataSource pool, String label, String sql) throws Exception {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.setQueryTimeout(0);
            try (ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                out("%-58s %s", label, rs.getString(1));
            }
        }
    }

    private static long ms(long since) {
        return (System.nanoTime() - since) / 1_000_000;
    }

    private static void out(String format, Object... args) {
        System.out.println(String.format(Locale.ROOT, format, args));
    }
}
