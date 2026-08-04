package ug.co.smsone.billing.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ug.co.smsone.organization.OrganizationRegistered;

/**
 * The listener's own contract: an {@link OrganizationRegistered} forwards the org id to
 * {@link BillingService#provision}. The remote round-trip that {@code provision} performs is covered
 * against a real Kill Bill in {@link KillBillIntegrationTest}; here we only prove the wiring, so no
 * container is needed.
 */
class OrganizationBillingListenerTest {

    @Test
    void registeringAnOrganizationProvisionsItsBillingAccount() {
        BillingService billing = mock(BillingService.class);
        UUID orgId = UUID.randomUUID();

        new OrganizationBillingListener(billing).on(new OrganizationRegistered(orgId, "acme", Instant.now()));

        verify(billing).provision(orgId);
    }
}
