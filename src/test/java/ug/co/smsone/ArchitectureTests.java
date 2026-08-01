package ug.co.smsone;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
                String expected = "update " + table.get().name()
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
}
