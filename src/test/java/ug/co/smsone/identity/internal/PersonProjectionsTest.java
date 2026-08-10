package ug.co.smsone.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.directory.PersonProjection;
import ug.co.smsone.shared.directory.PersonProjections;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The {@link PersonProjections} port: <b>a page of people costs the same number of statements as one
 * person.</b>
 *
 * <p>That sentence is the deliverable, not the response shape. The sideload this port exists for
 * ({@code ?include=person} on the member listings) is only a fix for the client's N+1 if the server
 * side of it is one batched read — a loop behind a compound document is the identical problem with a
 * nicer envelope, and every assertion about the JSON would still pass. So the measurement is the
 * test: statements for twenty-five ids, compared against statements for one, with no magic constant
 * on either side.
 *
 * <p><b>Hibernate's own counter, switched on at runtime.</b> {@code Statistics.setStatisticsEnabled}
 * exists precisely so a test can measure without {@code hibernate.generate_statistics=true} in the
 * properties — which would fork a second Spring context (and a second migration run) for one boolean.
 * It counts JDBC statement PREPARES, which is the number an N+1 moves and the number a batch does
 * not; entity load counts would read 25 either way and prove nothing.
 */
class PersonProjectionsTest extends AbstractIntegrationTest {

    /** Comfortably more than any plausible fixed overhead, so "flat" cannot be confused with "small". */
    private static final int PAGE = 25;

    @Autowired
    private PersonProjections projections;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private boolean wasEnabled;

    @BeforeEach
    void enableStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void restoreStatistics() {
        // The SessionFactory is shared by every test in this cached context, so leaving the counters on
        // would tax the rest of the run for the benefit of this file alone.
        statistics.setStatisticsEnabled(wasEnabled);
    }

    @Test
    void oneBatchCostsTheSameNumberOfStatementsAsOnePerson() {
        List<UUID> people = seedPeople(PAGE);

        // Warm first. The very first call through a query pays for metadata Hibernate then keeps, and
        // charging that to whichever measurement happens to run first is how a flat cost looks like a
        // rising one.
        projections.projectionsOf(Set.of(people.getFirst()));

        long forOne = statementsDuring(() -> projections.projectionsOf(Set.of(people.getFirst())));
        long forAPage = statementsDuring(() -> projections.projectionsOf(new LinkedHashSet<>(people)));

        assertThat(forAPage)
                .describedAs("%d people must cost exactly what 1 person costs — the port reads person "
                        + "and person_contact once each with an `in (…)`. A difference here means "
                        + "something inside projectionsOf went back per person, which is the N+1 the "
                        + "sideload exists to remove, moved from the client to the server.", PAGE)
                .isEqualTo(forOne);
        assertThat(forAPage)
                .describedAs("and it is a batch rather than a loop that happens to be cheap")
                .isLessThan(PAGE);
    }

    @Test
    void anEmptyRequestTouchesNoDatabaseAtAll() {
        assertThat(statementsDuring(() -> projections.projectionsOf(Set.of()))).isZero();
        assertThat(projections.projectionsOf(Set.of())).isEmpty();
        assertThat(projections.projectionsOf(null)).isEmpty();
    }

    @Test
    void itProjectsTheColumnsAdr0010SaysMayTravelAndNoOthers() {
        // The record IS the column set, shared with compliance.internal.PersonProjector's bundle
        // writer. Widening it here without widening ADR 0010 §2.2 — or the other way round — is how an
        // extracted tenant's member list stops agreeing with the bundle it was restored from, so the
        // list is asserted rather than merely commented.
        assertThat(PersonProjection.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("personId", "formattedName", "givenName", "familyName", "status", "email");

        UUID personId = seedPerson("Dr. Ada Lovelace", "Ada", "Lovelace");

        PersonProjection projection = projections.projectionsOf(Set.of(personId)).get(personId);

        assertThat(projection.personId()).isEqualTo(personId);
        assertThat(projection.formattedName()).isEqualTo("Dr. Ada Lovelace");
        assertThat(projection.givenName()).isEqualTo("Ada");
        assertThat(projection.familyName()).isEqualTo("Lovelace");
        assertThat(projection.status()).isEqualTo("ACTIVE");
        assertThat(projection.email()).isEqualTo(emailOf(personId));
    }

    @Test
    void aPersonWithNoNameAndNoAddressProjectsNullsRatherThanVanishing() {
        // A provisioned human nobody supplied a name for is an ordinary state (PersonName's javadoc),
        // and the caller has to be able to render the row. Dropping them from the map instead would be
        // indistinguishable from "erased" at every call site.
        UUID personId = EdgeSeed.person(jdbc, "kc-" + UUID.randomUUID());

        PersonProjection projection = projections.projectionsOf(Set.of(personId)).get(personId);

        assertThat(projection).isNotNull();
        assertThat(projection.formattedName()).isNull();
        assertThat(projection.givenName()).isNull();
        assertThat(projection.familyName()).isNull();
        assertThat(projection.email()).isNull();
    }

    @Test
    void anErasedOrUnknownPersonIsAbsentAndTakesNobodyElseWithThem() {
        UUID live = seedPerson("Live One", "Live", "One");
        UUID erased = seedPerson("Gone Away", "Gone", "Away");
        jdbc.update("update person set deleted_at = now() where id = ?", erased);
        UUID neverExisted = UUID.randomUUID();

        Map<UUID, PersonProjection> resolved =
                projections.projectionsOf(new LinkedHashSet<>(List.of(live, erased, neverExisted)));

        assertThat(resolved).containsOnlyKeys(live);
        assertThat(resolved.get(live).formattedName())
                .describedAs("an absent id must not cost the ids beside it their answer")
                .isEqualTo("Live One");
    }

    private long statementsDuring(Runnable work) {
        long before = statistics.getPrepareStatementCount();
        work.run();
        return statistics.getPrepareStatementCount() - before;
    }

    private List<UUID> seedPeople(int count) {
        List<UUID> people = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            people.add(seedPerson("Person " + index, "Person", String.valueOf(index)));
        }
        return people;
    }

    /**
     * A person with all three projected name components and a verified primary address. Written with
     * the harness's PLATFORM pin: {@code person} and {@code person_contact} are platform-tier and
     * unqualified here, exactly as {@link EdgeSeed} writes them.
     */
    private UUID seedPerson(String formattedName, String givenName, String familyName) {
        UUID personId = EdgeSeed.personWithEmail(jdbc, "kc-" + UUID.randomUUID(),
                UUID.randomUUID() + "@projection.test");
        jdbc.update("update person set formatted_name = ?, given_name = ?, family_name = ? where id = ?",
                formattedName, givenName, familyName, personId);
        return personId;
    }

    private String emailOf(UUID personId) {
        return jdbc.queryForObject(
                "select contact_value from person_contact where person_id = ? and deleted_at is null",
                String.class, personId);
    }
}
