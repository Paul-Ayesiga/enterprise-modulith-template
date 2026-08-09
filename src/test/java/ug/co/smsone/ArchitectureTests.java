package ug.co.smsone;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Optional;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

@AnalyzeClasses(packages = "ug.co.smsone", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    /**
     * <strong>One mint, and it is the one that reads the reservations.</strong> ADR 0010 §6 item 8 has
     * an extraction revoke a departing tenant's API keys and reserve their prefixes permanently (V59) —
     * and the reason a second table was needed at all is that revoking is a SOFT delete while
     * {@code uq_api_key_prefix_live} is partial on {@code deleted_at is null}, so a revoked key hands its
     * prefix straight back to a 48-bit space.
     *
     * <p>A reservation table that the mint path does not consult is a table with a primary key and no
     * effect, and the failure of forgetting to consult it is silent by construction: the platform
     * re-mints a departed tenant's prefix for another organization, and a stale credential from the old
     * tenant starts resolving against the new tenant's row on {@code findByPrefix} — the one lookup that
     * runs before any tenant is known, which is exactly why {@code api_key} is platform-tier. The hash
     * still has to match, so it is a cross-tenant lookup collision rather than an authentication bypass,
     * and it would surface as an inexplicable 401 rather than as anything anyone could trace.
     *
     * <p>So {@code ApiKeyHashing.mint()} stays a pure function with exactly one caller, and a fourth key
     * path added next year cannot quietly go around the reservation: it fails here, at compile-time cost,
     * naming the class that skipped it. Named as strings because both classes are package-private, which
     * is itself the point — nothing outside {@code apikeys.internal} can reach either.
     */
    @ArchTest
    static final ArchRule onlyTheReservationAwareMintCallsApiKeyHashingMint =
            noClasses().that()
                    .doNotHaveFullyQualifiedName("ug.co.smsone.apikeys.internal.ApiKeyPrefixReservations")
                    .should().callMethod("ug.co.smsone.apikeys.internal.ApiKeyHashing", "mint");

    /**
     * Hibernate resolves neither {@code @SQLDelete} nor {@code @SQLRestriction} from a mapped
     * superclass, so extending {@link SoftDeletableEntity} buys an entity nothing on its own. Getting
     * this wrong is silent in both directions and invisible in review: without {@code @SQLDelete} a
     * {@code delete} is a real DELETE (the row is gone, and the partial unique indexes make it look
     * like it worked), and without {@code @SQLRestriction} every finder keeps returning deleted rows.
     *
     * <p>Behavioural tests cover one entity each; this is what holds the other six — and the eighth
     * that someone adds next year.
     */
    @ArchTest
    static final ArchRule softDeletableEntitiesDeclareTheirOwnHibernateAnnotations =
            classes().that().areAssignableTo(SoftDeletableEntity.class)
                    .and().areAnnotatedWith(Entity.class) // the concrete rows, not the mapped superclass
                    .should(declareSoftDeleteAnnotationsMatchingTheirTable());

    private static ArchCondition<JavaClass> declareSoftDeleteAnnotationsMatchingTheirTable() {
        return new ArchCondition<>("declare @SQLRestriction and an @SQLDelete naming their own table") {
            @Override
            public void check(JavaClass entity, ConditionEvents events) {
                Optional<SQLRestriction> restriction = entity.tryGetAnnotationOfType(SQLRestriction.class);
                if (restriction.isEmpty() || !"deleted_at is null".equals(restriction.get().value())) {
                    events.add(SimpleConditionEvent.violated(entity, entity.getName()
                            + " must declare @SQLRestriction(\"deleted_at is null\"), or deleted rows stay"
                            + " visible to every query"));
                }
                Optional<Table> table = entity.tryGetAnnotationOfType(Table.class);
                if (table.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(entity,
                            entity.getName() + " must declare @Table so its @SQLDelete can be verified"));
                    return;
                }
                // Both halves of the version clause are load-bearing. The predicate stops a stale
                // delete from winning; the increment stops a concurrent stale UPDATE from writing
                // deleted_at = null back over the delete, which no missing row would ever reveal.
                //
                // The table is named the way the entity's own @Table names it, schema and all. Hibernate
                // does not rewrite @SQLDelete — it ships the string verbatim — so a PLATFORM-tier entity
                // whose delete said `update person` while its mapping said `platform.person` would soft-
                // delete through search_path, hit tenant_pool, and update nothing. The repository call
                // returns normally either way, which is precisely the class of failure this rule exists
                // to make impossible (ADR 0010 §2).
                String expected = "update " + qualifiedName(table.get())
                        + " set deleted_at = now(), version = version + 1 where id = ? and version = ?";
                Optional<SQLDelete> delete = entity.tryGetAnnotationOfType(SQLDelete.class);
                if (delete.isEmpty() || !expected.equals(delete.get().sql())) {
                    events.add(SimpleConditionEvent.violated(entity, entity.getName()
                            + " must declare @SQLDelete(sql = \"" + expected + "\") but was "
                            + delete.map(SQLDelete::sql).orElse("<missing>")));
                }
            }
        };
    }

    /** {@code platform.person} for a platform-tier mapping, plain {@code ticket} for a tenant one. */
    private static String qualifiedName(Table table) {
        return table.schema().isBlank() ? table.name() : table.schema() + "." + table.name();
    }
}
