package ug.co.smsone.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The three-state holder and the guard that makes ADR 0010's worst failure mode loud.
 *
 * <p>No container here on purpose — this class has no infrastructure to touch, which is the property
 * the ADR asks for (a bare holder, no Spring bean). {@code TenantRoutingDataSourceIntegrationTest}
 * proves the same guard against a real Spring transaction and a real connection.
 */
class TenantContextTest {

    @AfterEach
    void clearTheThread() {
        // JUnit reuses this platform thread across classes; a pin left behind would route someone
        // else's test. Exactly the leak the class javadoc warns about for scheduler threads.
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TenantContext.clear();
    }

    @Test
    void aThreadThatDeclaredNothingIsAbsentRatherThanSomeDefault() {
        assertThat(TenantContext.current()).isEqualTo(Tenant.ABSENT);
    }

    @Test
    void pinningATenantNamesTheOrganizationIdAndNothingElse() {
        UUID orgId = UUID.randomUUID();

        TenantContext.set(orgId);

        assertThat(TenantContext.current()).isEqualTo(new Tenant.Org(orgId));
    }

    @Test
    void theTenantAndPlatformAxesAreDistinctStates() {
        TenantContext.setPlatform();

        assertThat(TenantContext.current()).isEqualTo(Tenant.PLATFORM).isNotEqualTo(Tenant.ABSENT);
    }

    @Test
    void clearingReturnsTheThreadToTheAbsentStateAndNotToTheLastTenant() {
        TenantContext.set(UUID.randomUUID());

        TenantContext.clear();

        assertThat(TenantContext.current()).isEqualTo(Tenant.ABSENT);
    }

    @Test
    void pinningInsideAnActiveTransactionThrowsBecauseItWouldSilentlyDoNothing() {
        // The connection is chosen at borrow and a transaction has already bound one, so this call
        // could not change the schema the statements run against — it would only look like it had.
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> TenantContext.set(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BEFORE the transaction opens");
        assertThatThrownBy(TenantContext::setPlatform).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantContext.runAs(UUID.randomUUID(), () -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clearingInsideATransactionIsAllowedBecauseItRunsInFinallyBlocks() {
        TenantContext.setPlatform();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatCode(TenantContext::clear).doesNotThrowAnyException();
        assertThat(TenantContext.current()).isEqualTo(Tenant.ABSENT);
    }

    @Test
    void runAsPutsBackTheAxisItFoundIncludingAbsence() {
        UUID orgId = UUID.randomUUID();

        TenantContext.runAs(orgId, () -> assertThat(TenantContext.current()).isEqualTo(Tenant.of(orgId)));

        assertThat(TenantContext.current()).isEqualTo(Tenant.ABSENT);
    }

    @Test
    void aNestedRunAsRestoresTheOuterTenantAndNotTheAbsentState() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();

        TenantContext.set(outer);
        String innerResult = TenantContext.callAs(inner, () -> TenantContext.current().toString());

        assertThat(innerResult).contains(inner.toString());
        assertThat(TenantContext.current()).isEqualTo(Tenant.of(outer));
    }

    @Test
    void runAsRestoresTheCallersAxisEvenWhenTheBodyThrows() {
        TenantContext.setPlatform();

        assertThatThrownBy(() -> TenantContext.runAs(UUID.randomUUID(), () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(TenantContext.current()).isEqualTo(Tenant.PLATFORM);
    }

    @Test
    void runAsPlatformIsTheExplicitAxisJobsAndWorkersNeed() {
        Tenant seen = TenantContext.callAsPlatform(TenantContext::current);

        assertThat(seen).isEqualTo(Tenant.PLATFORM);
        assertThat(TenantContext.current()).isEqualTo(Tenant.ABSENT);
    }

    @Test
    void anOrgAxisWithoutAnOrganizationIdIsRejected() {
        assertThatThrownBy(() -> TenantContext.set((UUID) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organization id");
    }
}
