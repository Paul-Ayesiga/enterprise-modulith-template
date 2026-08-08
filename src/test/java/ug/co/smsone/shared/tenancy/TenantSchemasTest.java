package ug.co.smsone.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The whole routing decision as a pure function: which {@code search_path} each {@link Tenant} state
 * resolves to, plus the silo-name deriver and its guard.
 *
 * <p><b>Since Phase 2 the mapping is real.</b> Through Phase 1 all three states landed in the one
 * schema the tables occupied and only absence behaved differently; now a platform axis reaches
 * {@code platform}, a tenant axis reaches {@code tenant_pool}, and no axis reaches the empty
 * {@code no_tenant}. Nothing else in the system decides where a statement lands, so a wrong answer here
 * is a misrouted write everywhere at once — which is why these assertions are on literal strings rather
 * than on the constants the method is assembled from.
 *
 * <p>The guard is not defensive programming for its own sake: a {@code search_path} cannot be a bound
 * parameter, so the schema name is interpolated straight into the statement
 * {@code TenantRoutingDataSource} issues on every borrow. This regex is the entire boundary between a
 * schema name and SQL injection, and Phase 5 is what starts feeding it derived names — so it stays
 * tested a phase before it has a caller, not after.
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
        // Every real schema in the database. None of them is a silo, and Phase 5 resolves silo names
        // out of `platform.tenant_placement` — a row holding any of these must be refused rather than
        // pointed at, because each one would route a promoted tenant somewhere it does not live.
        "tenant_pool",
        "platform",
        "no_tenant",
        "ext",
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

    /**
     * The Phase 2 mapping, spelled out. Through Phase 1 the two axes produced the SAME string and the
     * only behaviour that had changed was absence; the assertion that mattered then was equality. Since
     * the tables moved, the assertion that matters is the opposite one — these three strings are the
     * whole of the routing decision, and every misroute this design can produce is a wrong answer here.
     *
     * <p>Literals rather than the constants the method is built from. Asserting
     * {@code searchPathFor(PLATFORM)} equals {@code PLATFORM + ", " + EXTENSIONS} would be the method
     * restated, and would keep passing after a rename that left the migrations pointing at the old
     * schema. The names are written out so a change to one has to be made twice, deliberately.
     */
    @Test
    void eachAxisResolvesToItsOwnSchemaAndTheTwoAreNotTheSamePlace() {
        assertThat(TenantSchemas.searchPathFor(Tenant.of(UUID.randomUUID())))
                .isEqualTo("tenant_pool, ext");
        assertThat(TenantSchemas.searchPathFor(Tenant.PLATFORM)).isEqualTo("platform, ext");

        // The Phase 1 test asserted these were equal. That they are NOT is the whole of Phase 2: a
        // platform axis that still reached the tenant schema would read the pool for every job in the
        // system and nothing would fail until the first tenant was promoted out of it.
        assertThat(TenantSchemas.searchPathFor(Tenant.PLATFORM))
                .isNotEqualTo(TenantSchemas.searchPathFor(Tenant.of(UUID.randomUUID())));
    }

    /**
     * Two properties of the paths above that are easy to lose in a rename and expensive to lose in
     * production, so they are asserted as themselves rather than left implicit in the strings.
     *
     * <p>{@code ext} on both: pg_trgm was moved out of {@code public} by V54, so {@code word_similarity}
     * and the {@code %} operator resolve only because that schema is on the path. Drop it and the
     * failure is not a missing function so much as {@code idx_search_title_trgm} silently becoming
     * unusable.
     *
     * <p>{@code public} on neither: it holds nothing after Phase 2, and a path that still named it would
     * let a table land there and keep working — right up until the tenant that depends on it is lifted
     * onto a database of its own and discovers the dependency it was never supposed to have.
     */
    @Test
    void everyPathCarriesTheExtensionSchemaAndNoneOfThemCarriesPublic() {
        Tenant[] axes = {Tenant.of(UUID.randomUUID()), Tenant.PLATFORM, Tenant.ABSENT};

        for (Tenant axis : axes) {
            assertThat(TenantSchemas.searchPathFor(axis))
                    .describedAs("search_path for %s", axis)
                    .doesNotContain("public");
        }
        // Absence is the exception and must stay one: work with no axis has no business resolving
        // trigram operators, so `ext` is deliberately absent from that one path.
        assertThat(TenantSchemas.searchPathFor(Tenant.of(UUID.randomUUID())))
                .contains(TenantSchemas.EXTENSIONS);
        assertThat(TenantSchemas.searchPathFor(Tenant.PLATFORM)).contains(TenantSchemas.EXTENSIONS);
        assertThat(TenantSchemas.searchPathFor(Tenant.ABSENT))
                .doesNotContain(TenantSchemas.EXTENSIONS);
    }
}
