package ug.co.smsone.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The silo-name deriver and its guard, written a phase before anything routes to a silo.
 *
 * <p>The guard is not defensive programming for its own sake: a {@code search_path} cannot be a bound
 * parameter, so the schema name is interpolated straight into the statement
 * {@code TenantRoutingDataSource} issues on every borrow. This regex is the entire boundary between a
 * schema name and SQL injection, which is why it is tested before there is a caller.
 */
class TenantSchemasTest {

    @Test
    void aSiloSchemaIsDerivedFromTheOrganizationIdWithNoDatabaseRead() {
        UUID orgId = UUID.fromString("2f1c8b9e-4a6d-4f3b-9c21-7d0e5a8b6c34");

        assertThat(TenantSchemas.siloSchema(orgId)).isEqualTo("t_2f1c8b9e4a6d4f3b9c217d0e5a8b6c34");
    }

    @Test
    void everyDerivedNameSatisfiesTheGuardAndFitsPostgresIdentifierLimits() {
        // UUID.toString() is specified lower-case, but the whole design rests on that, so prove it
        // over real values rather than trusting the sentence.
        Stream.generate(UUID::randomUUID).limit(1_000).forEach(orgId -> {
            String schema = TenantSchemas.siloSchema(orgId);
            assertThat(schema).hasSize(34).matches("t_[0-9a-f]{32}");
            assertThat(TenantSchemas.requireSiloSchema(schema)).isEqualTo(schema);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "T_2F1C8B9E4A6D4F3B9C217D0E5A8B6C34",              // upper case: not what the deriver mints
        "t_2f1c8b9e-4a6d-4f3b-9c21-7d0e5a8b6c34",          // the dashes left in
        "t_2f1c8b9e4a6d4f3b9c217d0e5a8b6c3",               // 31 hex digits
        "t_2f1c8b9e4a6d4f3b9c217d0e5a8b6c344",             // 33 hex digits
        "tenant_pool",                                      // a real schema, but not a silo
        "public",
        "t_2f1c8b9e4a6d4f3b9c217d0e5a8b6c34, public",      // path smuggling
        "t_2f1c8b9e4a6d4f3b9c217d0e5a8b6c34; drop schema public cascade",
        "t_2f1c8b9e4a6d4f3b9c217d0e5a8b6c34\ndrop schema public",
        "\"t_2f1c8b9e4a6d4f3b9c217d0e5a8b6c34\"",          // pre-quoted: quoting is ours to add
        "t_",
        ""
    })
    void anythingTheRegexDoesNotMatchNeverReachesASetStatement(String candidate) {
        assertThatThrownBy(() -> TenantSchemas.requireSiloSchema(candidate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullSchemaNameIsRejectedLikeAnyOtherNonMatch() {
        assertThatThrownBy(() -> TenantSchemas.requireSiloSchema(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absenceResolvesToTheEmptySchemaAndNeverToATenantOrThePlatform() {
        assertThat(TenantSchemas.searchPathFor(Tenant.ABSENT)).isEqualTo("no_tenant");
    }

    @Test
    void everyDeclaredAxisResolvesToTodaysSchemaWhileTheAdrIsAtPhaseOne() {
        // Phase 1 moves no table: the tenant and platform axes are the same string, and the only
        // behaviour that changed is absence. When Phase 2 splits them this test splits with it.
        String tenantPath = TenantSchemas.searchPathFor(Tenant.of(UUID.randomUUID()));

        assertThat(tenantPath).isEqualTo("public, ext");
        assertThat(TenantSchemas.searchPathFor(Tenant.PLATFORM)).isEqualTo(tenantPath);
        assertThat(tenantPath).contains(TenantSchemas.EXTENSIONS); // pg_trgm resolves, or search dies
    }
}
