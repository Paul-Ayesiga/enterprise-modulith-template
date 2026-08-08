package ug.co.smsone.shared.persistence;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * The net under {@code ddl-auto: none}: every table the entity mapping expects must actually be
 * reachable once the application is up.
 *
 * <p>Hibernate no longer validates at boot, and that is deliberate — ADR 0010 §4.4. With a routed
 * DataSource, "the schema" is not one thing, so a boot-time validation has no single answer to check
 * against, and Hibernate must never be allowed to create or alter anything once tables are split across
 * schemas. What is lost is the check that used to catch an entity changed without its migration, so
 * something has to replace it.
 *
 * <p><b>Why this is a table check and not {@code SchemaManager.validate()}.</b> The obvious
 * implementation — {@code entityManagerFactory.getSchemaManager().validate()} — does not work behind
 * this DataSource, and the failure is instructive. Hibernate resolves the default schema it validates
 * against from JDBC metadata while the {@code EntityManagerFactory} is being built, and it holds that
 * answer. Behind a router whose whole purpose is that the schema depends on WHO is asking, there is no
 * correct value for it to have captured: it reported {@code missing table [api_key]} against a database
 * where {@code api_key} was plainly present, because it was looking in the schema its bootstrap
 * connection happened to be on. Pinning the axis earlier does not fix it — the answer is already
 * recorded by the time any application code can pin anything.
 *
 * <p>So this asks the question the routed way instead: on a connection with a declared axis, is every
 * mapped table resolvable through {@code search_path}? That is exactly what production code will do on
 * every query, which makes it the right question, and it stays right in Phase 2 when the tables split —
 * the same check under a tenant pin then covers the tenant tier.
 *
 * <p>It does NOT check columns or types. That is a real gap against what Hibernate used to do, and it is
 * recorded here rather than papered over: {@code TenancyTierBoundaryTest} covers column drift against
 * {@code docs/DATA_MODEL.md} in the test suite, which is where that check belongs — it needs the whole
 * schema, not a running application.
 */
@Component
class MappedSchemaValidator {

    private final JdbcTemplate jdbc;
    private final List<String> mappedTables;

    MappedSchemaValidator(JdbcTemplate jdbc, jakarta.persistence.EntityManagerFactory entityManagerFactory) {
        this.jdbc = jdbc;
        this.mappedTables = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(entity -> entity.getJavaType().getAnnotation(jakarta.persistence.Table.class))
                .filter(table -> table != null && !table.name().isBlank())
                .map(table -> table.name().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }

    @EventListener(ApplicationReadyEvent.class)
    void validateMappedTablesAreReachable() {
        // The platform axis explicitly: in Phase 1 it resolves to the schema every entity is in, so one
        // pass covers the mapping. Phase 2 adds a second pass under a tenant pin for the tenant tier.
        TenantContext.runAsPlatform(this::check);
    }

    private void check() {
        var missing = new TreeSet<String>();
        for (String table : mappedTables) {
            // to_regclass resolves through the CURRENT search_path and returns null rather than throwing,
            // which is what makes it usable for a probe: it answers the same question the ORM's own SQL
            // will ask on the next request, on a connection routed the same way.
            Boolean present = jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table);
            if (!Boolean.TRUE.equals(present)) {
                missing.add(table);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Mapped tables are not reachable on the platform axis: " + missing
                            + ". Hibernate no longer validates at boot (ddl-auto: none, ADR 0010 §4.4) and"
                            + " never creates or alters anything, so this is the check that would have"
                            + " failed startup — either a migration is missing, or an entity was mapped to"
                            + " a table that no schema on this search_path holds.");
        }
    }
}
