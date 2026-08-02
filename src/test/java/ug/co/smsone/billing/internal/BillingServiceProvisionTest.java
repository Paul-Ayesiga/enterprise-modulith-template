package ug.co.smsone.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.subscription.Subscriptions;

/**
 * provision()'s concurrency contract in isolation: when a parallel provision (the auto-provision
 * listener racing a manual admin call) wins the unique-{@code org_id} insert, the loser resolves to
 * the winner's row instead of surfacing the DataIntegrityViolation as a 500. The happy-path remote
 * round-trip is covered against a real Kill Bill in {@link KillBillIntegrationTest}.
 */
class BillingServiceProvisionTest {

    @Test
    void concurrentProvisionResolvesToTheWinnerInsteadOf500() {
        BillingAccountRepository accounts = mock(BillingAccountRepository.class);
        KillBillGateway killBill = mock(KillBillGateway.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        UUID orgId = UUID.randomUUID();
        UUID kbAccountId = UUID.randomUUID();
        BillingAccount winner = BillingAccount.link(orgId, kbAccountId);

        given(killBill.ensureAccount(any(), any())).willReturn(kbAccountId);
        // Outer existence check misses; after losing the insert race, the winner's row is present.
        given(accounts.findByOrgId(orgId)).willReturn(Optional.empty(), Optional.of(winner));
        // The row write races and loses the unique org_id constraint.
        given(tx.execute(any())).willThrow(new DataIntegrityViolationException("uq_billing_account_org"));

        BillingService service = new BillingService(accounts, killBill,
                mock(KillBillProperties.class), mock(Subscriptions.class), tx, mock(AuditLog.class));

        assertThat(service.provision(orgId)).isSameAs(winner);
    }
}
