package ug.co.smsone.compliance.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import ug.co.smsone.compliance.internal.TenantExtractionService.RoutedTable;

/**
 * <strong>ADR 0010 §6 item 3: a person projection for every {@code person_id} the tenant references —
 * copied, not moved.</strong>
 *
 * <h2>Why a projection at all, in one paragraph</h2>
 *
 * <p>§2.2 settles it: {@code person} is global, 463 MB of person graph belongs to no tenant, and two
 * uniqueness constraints make a faithful copy actively illegal —
 * {@code uq_external_identity_subject_live} is one-login-one-human and
 * {@code uq_person_contact_verified_live} is one-verified-address-one-human, both platform-wide. Copy
 * either and two systems are in violation of a rule neither can see the other breaking. So what
 * travels is id, name, status and one <em>unverified</em> display contact, and writes to
 * {@code person}, {@code person_contact} and {@code external_identity} stay platform-only forever.
 *
 * <p>{@code person.id} does not change on extraction, which is what makes every {@code created_by} on
 * the far side still mean somebody. That is the dividend of this schema having no sequences and no
 * identity columns: every key is a UUID, so rows move out and back with no remapping (§2.2).
 *
 * <h2>WHICH people — derived, and the derivation is guarded</h2>
 *
 * <p>The set is "every {@code person_id} referenced anywhere in the schema", and the honest way to get
 * it is to ask the catalogue which columns hold one. That is a NAMING CONVENTION —
 * {@code person_id}, {@code *_person_id}, {@code created_by}, {@code updated_by} — and AGENTS §1 states
 * it as the schema's own rule ("cross-module links are soft refs: {@code membership.person_id uuid},
 * {@code created_by}/{@code updated_by} uuid"). A convention is only as good as what happens when
 * somebody breaks it, and here breaking it is silent: a new {@code ticket.escalated_to} column would
 * simply not be projected, and the far side would show a blank name on the one row an auditor asked
 * about.
 *
 * <p><b>So the convention is enforced rather than trusted.</b>
 * {@link #requireEveryUuidColumnIsAccountedFor()} classifies every {@code uuid} column as one of: a primary key, the
 * {@code org_id} discriminator, the child side of an intra-schema foreign key, a person reference by
 * convention, or a <em>declared</em> non-person soft ref. Anything else fails the bundle, naming the
 * column. The declared list is a list of EXCEPTIONS, and that is the whole difference from a hand list
 * of inclusions: forgetting an exception fails loudly, where forgetting an inclusion ships a bundle
 * that is quietly short.
 *
 * <h2>And the people the PLATFORM half of the bundle names</h2>
 *
 * <p>The tenant schema is not the only source. §6 item 5 puts the org's {@code impersonation_session}
 * rows in the bundle so its audit rows can resolve their stated reason — and those rows name an actor,
 * a target and whoever ended the session, none of whom need ever have appeared in the tenant's own
 * tables. {@code organization} and {@code external_organization} carry {@code created_by} /
 * {@code updated_by} for the same reason. Projecting only what the tenant schema references would ship
 * a support session whose operator is a bare UUID, which is precisely the audit question the item
 * exists to answer.
 */
@Component
class PersonProjector {

    /**
     * The columns {@code person} takes with it. §2.2 names exactly these — "id,
     * {@code formatted_name} / {@code given_name} / {@code family_name}, {@code status}, and the one
     * contact the org actually uses" — plus the three the table declares {@code not null}
     * ({@code invited_at}, {@code version}, {@code created_at}), without which the row cannot be
     * inserted on the far side at all.
     *
     * <p>What is deliberately NOT here: {@code middle_name}, the honorifics, {@code preferred_name},
     * {@code activated_at}, {@code disabled_at} — nothing else about the human travels — and
     * {@code created_by} / {@code updated_by}, which name people who may not be in the projection and
     * whose absence would make the projection's own completeness question recursive.
     */
    private static final List<String> PERSON_COLUMNS = List.of(
            "id", "status", "invited_at", "formatted_name", "given_name", "family_name",
            "version", "created_at", "deleted_at");

    /**
     * The contact projection. {@code verified_at} is <b>absent by construction, not nulled by a
     * statement</b>: it is not in this list, so the insert on the far side leaves it NULL and the row
     * falls outside {@code uq_person_contact_verified_live}, whose predicate is
     * {@code where verified_at is not null and deleted_at is null} (verified against the live index
     * definition, not assumed). That is §2.2's "copy contacts as unverified display projections or not
     * at all", expressed as a column list rather than as a warning.
     */
    private static final List<String> CONTACT_COLUMNS = List.of(
            "id", "person_id", "kind", "contact_value", "label", "is_primary", "version", "created_at");

    /** The naming convention AGENTS §1 states, as the predicate that derives the projection's inputs. */
    private static boolean namesAPerson(String column) {
        return column.equals("person_id") || column.endsWith("_person_id")
                || column.equals("created_by") || column.equals("updated_by");
    }

    /**
     * The {@code uuid} columns of the tenant tier that point at something other than a person and are
     * not otherwise classifiable. Every entry is a soft ref the FK rules removed or never allowed, and
     * every one of them is a question somebody had to answer once.
     *
     * <p>Kept as {@code table.column} → why, and checked in both directions by
     * {@link #requireEveryUuidColumnIsAccountedFor}: an unlisted stray fails, and a listed column that
     * no longer exists fails too, so a dropped column cannot leave a stale exemption behind that
     * silently covers a future column of the same name.
     *
     * <p>{@code user_device_trust.device_id} is a cross-tier soft ref and is deliberately NOT here: it
     * is part of that table's primary key {@code (device_id, org_id)} (V51), so the key arm classifies
     * it. Worth saying out loud because it is the one column where the classifier's answer is right for
     * an incidental reason.
     */
    private static final Map<String, String> NON_PERSON_UUID_COLUMNS = Map.of(
            "audit_log.impersonation_id",
            "soft ref to platform.impersonation_session.id — §6 item 5 carries those rows so this"
                    + " resolves on the far side",
            "billing_account.kb_account_id",
            "the external billing system's own account id; it names no row in this database",
            "exchange_schedule.last_job_id",
            "soft ref to exchange_job.id in the same schema — a pointer at the last run, deliberately"
                    + " not a foreign key so trimming job history cannot break a schedule",
            "org_subscription.plan_id",
            "soft ref to platform.plan.id — one of ADR 0010 §6's five cut boundary FKs; the catalogue"
                    + " snapshot (§6 item 4) is what makes it resolve after extraction");

    private final JdbcTemplate jdbc;

    PersonProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every {@code uuid} column of the tenant schema, classified. Run on the TENANT axis — it reads
     * {@code current_schema()}, which is the whole reason it works identically for a pooled tenant and
     * a siloed one.
     *
     * @throws IllegalStateException naming any column nobody has classified
     */
    void requireEveryUuidColumnIsAccountedFor() {
        Set<String> keyed = columnsOfConstraints("p");
        Set<String> referencing = columnsOfConstraints("f");
        List<String> strays = new ArrayList<>();
        Set<String> present = new TreeSet<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select c.table_name, c.column_name
                  from information_schema.columns c
                  join information_schema.tables t
                    on t.table_schema = c.table_schema and t.table_name = c.table_name
                 where c.table_schema = current_schema()
                   and t.table_type = 'BASE TABLE'
                   and c.data_type = 'uuid'
                 order by c.table_name, c.ordinal_position
                """)) {
            String qualified = row.get("table_name") + "." + row.get("column_name");
            String column = (String) row.get("column_name");
            if (NON_PERSON_UUID_COLUMNS.containsKey(qualified)) {
                present.add(qualified);
                continue;
            }
            if (column.equals("org_id") || namesAPerson(column)
                    || keyed.contains(qualified) || referencing.contains(qualified)) {
                continue;
            }
            strays.add(qualified);
        }
        if (!strays.isEmpty()) {
            throw new IllegalStateException("tenant-tier uuid column(s) " + strays + " are neither a key,"
                    + " nor org_id, nor a foreign key, nor a person reference by the naming convention"
                    + " (person_id / *_person_id / created_by / updated_by), and nothing has declared"
                    + " what they point at. The person projection derives its input set from that"
                    + " convention (ADR 0010 §6 item 3), so a column that quietly breaks it is a human"
                    + " the extracted deployment renders as a bare UUID — on exactly the rows an auditor"
                    + " asks about. Either rename it to follow the convention, or declare it in"
                    + " PersonProjector.NON_PERSON_UUID_COLUMNS with what it points at.");
        }
        List<String> stale = NON_PERSON_UUID_COLUMNS.keySet().stream()
                .filter(column -> !present.contains(column)).sorted().toList();
        if (!stale.isEmpty()) {
            throw new IllegalStateException("PersonProjector declares " + stale + " as non-person uuid"
                    + " column(s) the tenant schema no longer has. A stale exemption is worse than none:"
                    + " it silently covers the next column that takes the name.");
        }
    }

    /**
     * The person ids the tenant's own rows reference, read on the TENANT axis with the extraction's own
     * predicates so the projection cannot disagree with the dump about which rows are this tenant's.
     *
     * <p>One statement per table rather than per column: the person-bearing columns of a table are
     * unnested into a single set, so {@code audit_log}'s four are one scan and not four.
     */
    Set<UUID> personIdsInTheTenantSchema(List<RoutedTable> plan, UUID orgId) {
        Map<String, List<String>> personColumns = personColumnsByTable();
        Set<UUID> people = new LinkedHashSet<>();
        for (RoutedTable table : plan) {
            List<String> columns = personColumns.get(table.table());
            if (columns == null || columns.isEmpty()) {
                continue;
            }
            String unnested = columns.stream().map(column -> "t." + column)
                    .reduce((a, b) -> a + ", " + b).orElseThrow();
            // The predicate comes from RoutedTable, so a child table with no org_id of its own is still
            // scoped through the same FK chain the dump uses. `t` is the alias those predicates are
            // written against — see TenantExtractionService.ROW_ALIAS.
            String sql = "select distinct p from (select unnest(array[" + unnested + "]) as p from "
                    + table.table() + " t where " + table.predicate() + ") s where p is not null";
            // One argument per '?' in the predicate. RoutedTable's chained form binds the organization
            // exactly once however deep the foreign-key walk went, so this is one today — counted rather
            // than assumed, because the day a predicate binds twice a fixed-arity call would bind the
            // wrong number and fail at the driver instead of here.
            Object[] arguments = new Object[(int) table.predicate().chars().filter(c -> c == '?').count()];
            Arrays.fill(arguments, orgId);
            people.addAll(jdbc.queryForList(sql, UUID.class, arguments));
        }
        return people;
    }

    /**
     * The person ids named by the PLATFORM rows this bundle carries — the sessions of §6 item 5 and the
     * two routing rows of item 2. Read on the PLATFORM axis, schema-qualified.
     *
     * <p>Derived from the same convention and the same catalogue, over the tables
     * {@link TenantBundlePlan} marks {@link BundleDisposition#SEEDED_FOR_THIS_ORG}, so adding a fourth
     * seeded table extends this by itself.
     */
    Set<UUID> personIdsInTheSeededPlatformRows(List<TenantBundlePlan.PlatformTable> seeded, UUID orgId) {
        Set<UUID> people = new LinkedHashSet<>();
        for (TenantBundlePlan.PlatformTable table : seeded) {
            List<String> columns = jdbc.queryForList("""
                    select column_name from information_schema.columns
                     where table_schema = ? and table_name = ? and data_type = 'uuid'
                     order by ordinal_position
                    """, String.class, TenantBundlePlan.PLATFORM_SCHEMA, table.table())
                    .stream().filter(PersonProjector::namesAPerson).toList();
            if (columns.isEmpty()) {
                continue;
            }
            String unnested = columns.stream().map(column -> "t." + column)
                    .reduce((a, b) -> a + ", " + b).orElseThrow();
            String sql = "select distinct p from (select unnest(array[" + unnested + "]) as p from "
                    + TenantBundlePlan.PLATFORM_SCHEMA + "." + table.table() + " t"
                    + " where " + table.orgPredicate() + ") s where p is not null";
            people.addAll(jdbc.queryForList(sql, UUID.class, orgId));
        }
        return people;
    }

    /**
     * Emits the projection: one reduced {@code person} row per referenced human, and their primary
     * contacts as unverified display rows. Runs on the PLATFORM axis.
     *
     * <p>Read in ID batches rather than one statement per person: a tenant with 20,000 members would
     * otherwise be 20,000 round trips, and a single {@code in (…)} of that size is a query text no
     * statement cache can help with.
     *
     * @return how many rows the projection produced, per table
     */
    Map<String, Integer> project(Set<UUID> people, TenantBundler.Sink sink) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("person", 0);
        counts.put("person_contact", 0);
        List<UUID> ordered = new ArrayList<>(new TreeSet<>(people));
        for (int from = 0; from < ordered.size(); from += BATCH) {
            List<UUID> batch = ordered.subList(from, Math.min(from + BATCH, ordered.size()));
            counts.merge("person", emit(sink, "person", PERSON_COLUMNS,
                    "select " + columnList(PERSON_COLUMNS) + " from "
                            + TenantBundlePlan.PLATFORM_SCHEMA + ".person"
                            + " where id = any(?) order by id", batch), Integer::sum);
            // is_primary AND live: §2.2's "the one contact the org actually uses". The partial unique
            // index uq_person_contact_primary_live makes that at most one row per (person, kind), so
            // this cannot quietly become an unbounded second table.
            counts.merge("person_contact", emit(sink, "person_contact", CONTACT_COLUMNS,
                    "select " + columnList(CONTACT_COLUMNS) + " from "
                            + TenantBundlePlan.PLATFORM_SCHEMA + ".person_contact"
                            + " where person_id = any(?) and is_primary and deleted_at is null"
                            + " order by person_id, kind", batch), Integer::sum);
        }
        return counts;
    }

    /** How many ids one projection statement carries. Bounded for the reason {@link #project} states. */
    private static final int BATCH = 500;

    private int emit(TenantBundler.Sink sink, String table, List<String> columns, String sql,
            List<UUID> batch) {
        int[] rows = {0};
        // Typed rather than passed bare, for the reason TenantExtractionService names: JdbcTemplate.query
        // has a ResultSetExtractor overload of the same shape, and naming the type is what keeps the
        // row-by-row form from resolving to the whole-result one.
        RowCallbackHandler handler = resultSet -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : columns) {
                row.put(column, resultSet.getObject(column));
            }
            rows[0]++;
            sink.row(BundlePart.PERSON_PROJECTION, TenantBundlePlan.PLATFORM_SCHEMA, table, row);
        };
        // The whole batch as ONE bound uuid[] against `= any(?)`, not an in-list built by string: the
        // query text is then identical for every batch and the statement cache can actually hold it.
        // Verified against pgjdbc rather than assumed — a Java UUID[] is encoded as a uuid[] array.
        jdbc.query(sql, handler, new Object[] {batch.toArray(UUID[]::new)});
        return rows[0];
    }

    private static String columnList(List<String> columns) {
        return String.join(", ", columns);
    }

    /** The person-bearing columns of every tenant table, from the catalogue on the tenant axis. */
    private Map<String, List<String>> personColumnsByTable() {
        Map<String, List<String>> columns = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select c.table_name, c.column_name
                  from information_schema.columns c
                  join information_schema.tables t
                    on t.table_schema = c.table_schema and t.table_name = c.table_name
                 where c.table_schema = current_schema()
                   and t.table_type = 'BASE TABLE'
                   and c.data_type = 'uuid'
                 order by c.table_name, c.ordinal_position
                """)) {
            String column = (String) row.get("column_name");
            if (namesAPerson(column)) {
                columns.computeIfAbsent((String) row.get("table_name"), table -> new ArrayList<>())
                        .add(column);
            }
        }
        return columns;
    }

    /** {@code table.column} for every column participating in a constraint of the given type. */
    private Set<String> columnsOfConstraints(String type) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                select child.relname || '.' || a.attname
                  from pg_constraint con
                  join pg_class child on child.oid = con.conrelid
                  join pg_namespace n on n.oid = child.relnamespace
                  join unnest(con.conkey) as k(attnum) on true
                  join pg_attribute a on a.attrelid = child.oid and a.attnum = k.attnum
                 where con.contype = ? and n.nspname = current_schema()
                """, String.class, type));
    }
}
