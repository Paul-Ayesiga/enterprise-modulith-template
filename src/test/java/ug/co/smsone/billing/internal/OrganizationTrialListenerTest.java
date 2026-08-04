package ug.co.smsone.billing.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ug.co.smsone.organization.OrganizationRegistered;
import ug.co.smsone.subscription.Subscriptions;

/**
 * The listener's contract: an {@link OrganizationRegistered} drives the configured trial through the
 * {@link Subscriptions} port. The "already assigned → no-op" guard lives in the port impl and is
 * covered by {@code subscription.internal.SubscriptionServiceTrialTest}.
 */
class OrganizationTrialListenerTest {

    @Test
    void aNewOrgAppliesTheConfiguredSignupTrial() {
        Subscriptions subscriptions = mock(Subscriptions.class);
        UUID orgId = UUID.randomUUID();

        new OrganizationTrialListener(subscriptions, new TrialOnSignupProperties(true, "PRO", 30))
                .on(new OrganizationRegistered(orgId, "acme", Instant.now()));

        verify(subscriptions).startTrialForNewOrg(orgId, "PRO", 30);
    }
}
