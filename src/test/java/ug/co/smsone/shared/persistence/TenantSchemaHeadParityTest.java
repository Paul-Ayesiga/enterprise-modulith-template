package ug.co.smsone.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The sibling assertion ADR 0010 §4.4 asks for next to {@code FlywayBaselineTest}: <em>every tenant
 * schema's {@code flyway_schema_history} is at the same version as {@code tenant_pool}'s</em>.
 *
 * <p><b>Why it needs its own test.</b> A silo stuck two migrations behind is the failure mode
 * per-tenant migration introduces and nothing else in the suite would notice it. The application does
 * not read a silo's history; Hibernate no longer validates at boot ({@code ddl-auto: none}); and
 * {@code MappedSchemaValidator} probes {@code tenant_pool} only, because a fleet-wide question is one a
 * single pod cannot answer. The runner records what it did in {@code platform.tenant_placement}, but a
 * registry is a claim — this is the check against the database itself.
 *
 * <p>It also holds the other half of Phase 4's retirement work: {@code db/migration/bootstrap} and its
 * entry in {@code spring.flyway.locations} are gone, and the boot-time Flyway carries the platform
 * sequence and nothing else. Asserted against the resolved {@code Flyway} bean rather than against the
 * YAML text, so it is the configuration the application actually runs with.
 */
class TenantSchemaHeadParityTest extends AbstractIntegrationTest {

    private static final String TENANT_SCHEMAS =
            "select schema_name from information_schema.schemata"
                    + " where schema_name = 'tenant_pool' or schema_name ~ '^t_[0-9a-f]{32}$'"
                    + " order by schema_name";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void everyTenantSchemaIsAtTheSameVersionAsTenantPool() {
        String pool = head(TenantSchemas.TENANT_POOL);

        assertThat(pool)
                .describedAs("tenant_pool has no flyway_schema_history at all — the runner never built it,"
                        + " and every unqualified tenant table in the application resolves here")
                .isNotNull();

        List<String> schemas = jdbcTemplate.queryForList(TENANT_SCHEMAS, String.class);
        assertThat(schemas).contains(TenantSchemas.TENANT_POOL);
        assertThat(schemas).allSatisfy(schema -> assertThat(head(schema))
                .describedAs("%s is not at the version tenant_pool is at (%s). A silo behind the pool is"
                        + " the failure mode per-tenant migration introduces, and the binary's floor check"
                        + " turns it into 503s for that tenant alone — run the migration Job.", schema, pool)
                .isEqualTo(pool));
    }

    /**
     * The retirement of the Phase 2 scaffold, asserted rather than trusted. The generated
     * {@code V9999__tenant_pool.sql} replayed the whole tenant sequence into {@code tenant_pool} from
     * inside the platform run; leaving it in place beside the runner would mean two writers for one
     * schema, with the scaffold's transaction and the runner's history table disagreeing about what has
     * been applied.
     */
    @Test
    void bootTimeFlywayCarriesThePlatformSequenceAndNothingElse() {
        List<String> locations = Arrays.stream(flyway.getConfiguration().getLocations())
                .map(Location::getDescriptor)
                .toList();

        assertThat(locations)
                .describedAs("the tenant sequence must not run at boot: the Helm chart gives a pod ~105 s"
                        + " before the kubelet kills it and Flyway runs before the servlet container"
                        + " serves (ADR 0010 §4.2)")
                .containsExactly("classpath:" + TenantMigrationRunner.PLATFORM_LOCATION);
        assertThat(flyway.getConfiguration().getDefaultSchema())
                .isEqualTo(TenantMigrationRunner.PLATFORM_HISTORY_SCHEMA);
        assertThat(flyway.getConfiguration().getSchemas())
                .containsExactlyInAnyOrderElementsOf(TenantMigrationRunner.PLATFORM_MANAGED_SCHEMAS);
        assertThat(Files.exists(Path.of("src", "main", "resources", "db", "migration", "bootstrap")))
                .describedAs("the scaffold's own header says to delete this directory when the runner"
                        + " lands — it has landed")
                .isFalse();
    }

    private String head(String schema) {
        return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                TenantMigrationRunner.historyHead(connection, schema));
    }
}
