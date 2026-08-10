package ug.co.smsone.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Pins ADR 0011 §2's stale-while-unreachable semantics where they are decided — the holdover — with a
 * hand-stepped clock, so every boundary is exact rather than slept toward. The sibling
 * {@code ug.co.smsone.shared.security.PlatformUnreachableIntegrationTest} proves the same contract
 * end-to-end against a real Postgres whose connectivity is genuinely cut; THIS class is where the
 * arithmetic (per-entry age, the ceiling comparison, absent-replacement) is falsifiable to the second.
 */
class StaleWhileUnreachableTest {

    private static final Duration CEILING = Duration.ofMinutes(15);
    private static final String KEY = "KEYCLOAK|https://issuer|subject-1";

    /** A clock the test moves by hand — the injected {@code Clock} bean is the seam, per AGENTS §7. */
    private StepClock clock;
    private SimpleMeterRegistry meters;
    private StaleWhileUnreachable.Holdover<String> holdover;

    @BeforeEach
    void freshHoldover() {
        clock = new StepClock(Instant.parse("2026-08-09T08:00:00Z"));
        meters = new SimpleMeterRegistry();
        holdover = new StaleWhileUnreachable(clock, new IdentityStaleProperties(CEILING), meters)
                .holdoverFor("person-by-subject");
    }

    @Test
    void aLastKnownAnswerIsServedWhileTheAuthorityIsUnreachable() {
        holdover.refresh(KEY, "person-id");
        clock.advance(Duration.ofMinutes(5));

        assertThat(holdover.serveOr(KEY, connectionRefused())).isEqualTo("person-id");
        assertThat(meters.counter("smsone.cache.stale_served", "cache", "person-by-subject").count())
                .isEqualTo(1.0);
    }

    @Test
    void ageIsMeasuredFromTheEntrysOwnRefreshNotFromTheOutageStart() {
        // Confirmed at t0, unrefreshed for 14 minutes while the authority was perfectly healthy (a
        // cache HIT runs no loader, so nothing advanced the instant) — the outage begins NOW.
        holdover.refresh(KEY, "person-id");
        clock.advance(Duration.ofMinutes(14));

        // One minute of grace left, not fifteen: §2.3's whole point.
        assertThat(holdover.serveOr(KEY, connectionRefused())).isEqualTo("person-id");
        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> holdover.serveOr(KEY, connectionRefused()))
                .isInstanceOf(PlatformUnreachableException.class);
    }

    @Test
    void atTheCeilingTheDenialIsThe503ShapeNeverAnEmptyAnswer() {
        holdover.refresh(KEY, "person-id");
        clock.advance(CEILING); // age == ceiling: "at the ceiling, DENY" — the boundary itself denies

        assertThatThrownBy(() -> holdover.serveOr(KEY, connectionRefused()))
                .isInstanceOfSatisfying(PlatformUnreachableException.class, denial -> {
                    assertThat(denial.errorCode()).isEqualTo(ug.co.smsone.shared.error.ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(denial.retryAfterSeconds()).isPositive();
                });
        assertThat(meters.counter("smsone.cache.stale_ceiling_denials", "cache", "person-by-subject")
                .count()).isEqualTo(1.0);
    }

    @Test
    void anAuthoritativeAbsentReplacesTheEntryAndIsServedAsAbsent() {
        // The erasure trap (§2.2): the subject resolved to a person, the person was erased, the next
        // authoritative read answered "nobody". The stale path must repeat the erasure, not undo it.
        holdover.refresh(KEY, "person-id");
        clock.advance(Duration.ofMinutes(1));
        holdover.refresh(KEY, null);
        clock.advance(Duration.ofMinutes(1));

        assertThat(holdover.serveOr(KEY, connectionRefused())).isNull();
    }

    @Test
    void aQueryShapedFailureRethrowsInsteadOfServingStale() {
        holdover.refresh(KEY, "person-id");
        RuntimeException missingRelation = new org.springframework.jdbc.BadSqlGrammarException(
                "select", "select 1", new SQLException("relation does not exist", "42P01"));

        // Serving stale over a bug converts a loud failure into a silently wrong answer (§2.2).
        assertThatThrownBy(() -> holdover.serveOr(KEY, missingRelation)).isSameAs(missingRelation);
        assertThat(meters.counter("smsone.cache.stale_served", "cache", "person-by-subject").count())
                .isZero();
    }

    @Test
    void connectionShapedFailuresAreRecognizedThroughTheWrappingChain() {
        holdover.refresh(KEY, "person-id");

        // The shape a JPA repository read actually produces during an outage: Spring's transaction
        // wrapper around Hikari's borrow timeout around the socket-level refusal.
        RuntimeException viaTransaction = new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available"));
        assertThat(holdover.serveOr(KEY, viaTransaction)).isEqualTo("person-id");

        RuntimeException viaTranslation = new DataAccessResourceFailureException("borrow failed",
                new SQLException("connection refused"));
        assertThat(holdover.serveOr(KEY, viaTranslation)).isEqualTo("person-id");

        // SQLState class 08 — the driver's own word for "connection exception" — even when the
        // exception TYPE is a plain SQLException nobody special-cased.
        RuntimeException viaSqlState = new RuntimeException("wrapped",
                new SQLException("terminated", "08006"));
        assertThat(holdover.serveOr(KEY, viaSqlState)).isEqualTo("person-id");
    }

    @Test
    void nothingHeldDeniesRatherThanGuessing() {
        assertThatThrownBy(() -> holdover.serveOr("KEYCLOAK|https://issuer|never-resolved",
                connectionRefused()))
                .isInstanceOf(PlatformUnreachableException.class);
    }

    @Test
    void forgetDropsTheEntrySoAReLinkedSubjectCannotServeItsOldAnswerThroughAnOutage() {
        holdover.refresh(KEY, "person-id");
        holdover.forget(KEY);

        assertThatThrownBy(() -> holdover.serveOr(KEY, connectionRefused()))
                .isInstanceOf(PlatformUnreachableException.class);
    }

    @Test
    void aFreshRefreshAfterStaleServiceResetsTheServedAgeGauge() {
        holdover.refresh(KEY, "person-id");
        clock.advance(Duration.ofMinutes(10));
        holdover.serveOr(KEY, connectionRefused());
        assertThat(meters.get("smsone.cache.stale_oldest_served_age_seconds")
                .tag("cache", "person-by-subject").gauge().value())
                .isEqualTo(Duration.ofMinutes(10).toSeconds());

        holdover.refresh(KEY, "person-id"); // recovery
        assertThat(meters.get("smsone.cache.stale_oldest_served_age_seconds")
                .tag("cache", "person-by-subject").gauge().value()).isZero();
    }

    @Test
    void theCeilingCannotBeRaisedPastTheAdrsPromise() {
        // ADR 0011 §4.1: lower is legal, higher needs the ADR reopened — so higher fails startup.
        assertThatThrownBy(() -> new IdentityStaleProperties(Duration.ofMinutes(16)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADR 0011");
        assertThatThrownBy(() -> new IdentityStaleProperties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new IdentityStaleProperties(Duration.ofMinutes(2)).ceiling())
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(new IdentityStaleProperties(null).ceiling()).isEqualTo(CEILING);
    }

    private static RuntimeException connectionRefused() {
        return new DataAccessResourceFailureException("could not connect",
                new java.net.ConnectException("Connection refused"));
    }

    /** Steps only when told to — {@code Clock.fixed} that the test can move. */
    private static final class StepClock extends Clock {

        private Instant now;

        private StepClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
