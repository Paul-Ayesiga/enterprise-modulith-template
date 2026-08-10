package ug.co.smsone.subscription.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.cache.TwoLevelCacheManager;

/**
 * The trial-on-signup guard in isolation: {@code startTrialForNewOrg} is a no-op when the org already
 * has a subscription, so a straight-to-paid assignment is never overridden. The trial-start path
 * itself is exercised against a real DB by the subscription integration tests.
 */
class SubscriptionServiceTrialTest {

    @Test
    void startTrialForNewOrgSkipsAnOrgThatAlreadyHasASubscription() {
        OrgSubscriptionRepository subscriptions = mock(OrgSubscriptionRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        UUID orgId = UUID.randomUUID();
        given(subscriptions.findByOrgId(orgId)).willReturn(Optional.of(mock(OrgSubscription.class)));

        new SubscriptionService(subscriptions, plans, mock(ug.co.smsone.subscription.PlanCatalog.class),
                mock(PlanCatalogCache.class), mock(EntitlementResolver.class),
                mock(ApplicationEventPublisher.class), mock(AuditLog.class),
                mock(MeterRegistry.class), Clock.systemUTC(),
                mock(TwoLevelCacheManager.class), mock(TransactionTemplate.class))
                .startTrialForNewOrg(orgId, "PRO", 30);

        // Short-circuited before touching the catalog or writing anything.
        verify(plans, never()).findByCode(any());
        verify(subscriptions, never()).save(any());
    }
}
