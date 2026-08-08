package ug.co.smsone.shared.persistence;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
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
 * mapped table resolvable the way production code will resolve it? Since ADR 0010 Phase 2 that is TWO
 * questions, because the mapping now answers them differently (§2):
 *
 * <ul>
 *   <li>A PLATFORM-tier entity declares {@code @Table(schema = "platform")}, so Hibernate emits the
 *       schema on every statement and {@code search_path} never enters into it. Probed by its
 *       qualified name, which is why the platform pass would catch {@code platform.api_key} being
 *       absent but would <em>not</em> be fooled by an {@code api_key} sitting in some other schema.</li>
 *   <li>A TENANT-tier entity declares no schema at all — deliberately, because which schema it means
 *       is the tenant's, and that is decided per connection. Probed bare, on a tenant axis, which is
 *       the only axis on which the question has an answer.</li>
 * </ul>
 *
 * <p>Both passes are needed and neither substitutes for the other: run only the platform one and every
 * tenant-tier table goes unchecked; run only the tenant one and a platform entity whose schema was
 * never qualified would still pass while {@code tenant_pool} happened to hold a table of that name.
 * Getting the qualification backwards on one table reads the wrong tier silently, so the check that
 * matters is that each name resolves <em>the way its own mapping says it should</em>.
 *
 * <p>It does NOT check columns or types. That is a real gap against what Hibernate used to do, and it is
 * recorded here rather than papered over: {@code TenancyTierBoundaryTest} covers column drift against
 * {@code docs/DATA_MODEL.md} in the test suite, which is where that check belongs — it needs the whole
 * schema, not a running application.
 */
@Component
class MappedSchemaValidator {

    /**
     * The org whose axis the tenant pass borrows. It names no organization on purpose: a tenant that
     * has never been promoted resolves to the shared {@code tenant_pool}, and a UUID that is in no
     * {@code organization} row can never resolve to anything else — so this is the pooled schema's
     * own axis, expressed with the only vocabulary {@code TenantContext} has (it pins an ORG; there is
     * no "the pool" state, and inventing one belongs with the router, not here).
     *
     * <p>That makes the pass validate {@code tenant_pool} specifically, which is the right scope for a
     * boot check: it is the schema every unpromoted tenant uses and the one this binary must match to
     * serve anybody at all. Silos are a fleet-wide question that a single pod cannot answer — ADR 0010
     * Phase 4 puts it in {@code platform.tenant_placement} plus the version floor, where a partial
     * fleet is a queryable fact rather than a failed startup.
     */
    private static final UUID POOLED_TENANT_PROBE = new UUID(0L, 0L);

    private final JdbcTemplate jdbc;
    private final List<String> platformTables;
    private final List<String> tenantTables;

    MappedSchemaValidator(JdbcTemplate jdbc, MappedTables tables) {
        this.jdbc = jdbc;
        Map<String, String> mapped = tables.all();
        // Split on what the mapping itself declares, never on a list kept here — that list would be a
        // second copy of the tier assignment and the one that goes stale.
        this.platformTables = mapped.values().stream().filter(name -> name.indexOf('.') >= 0).sorted().toList();
        this.tenantTables = mapped.values().stream().filter(name -> name.indexOf('.') < 0).sorted().toList();
    }

    @EventListener(ApplicationReadyEvent.class)
    void validateMappedTablesAreReachable() {
        TenantContext.runAsPlatform(() -> check(platformTables, "platform"));
        TenantContext.runAs(POOLED_TENANT_PROBE, () -> check(tenantTables, "pooled tenant"));
    }

    private void check(List<String> tables, String axis) {
        var missing = new TreeSet<String>();
        for (String table : tables) {
            // to_regclass resolves through the CURRENT search_path and returns null rather than throwing,
            // which is what makes it usable for a probe: it answers the same question the ORM's own SQL
            // will ask on the next request, on a connection routed the same way. A qualified argument
            // skips search_path entirely — which is exactly the property the platform pass is asserting.
            Boolean present = jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table);
            if (!Boolean.TRUE.equals(present)) {
                missing.add(table);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Mapped tables are not reachable on the " + axis + " axis: " + missing
                            + ". Hibernate no longer validates at boot (ddl-auto: none, ADR 0010 §4.4) and"
                            + " never creates or alters anything, so this is the check that would have"
                            + " failed startup — either a migration is missing, or an entity was mapped to"
                            + " a table that no schema on this search_path holds, or its @Table names the"
                            + " wrong tenancy tier (ADR 0010 §2).");
        }
    }
}
