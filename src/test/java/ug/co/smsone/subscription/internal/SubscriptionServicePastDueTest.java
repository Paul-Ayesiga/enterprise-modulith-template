package ug.co.smsone.subscription.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.cache.TwoLevelCacheManager;
import ug.co.smsone.subscription.SubscriptionChanged;

/** Dunning's teeth in isolation: a lapsed PAST_DUE row pauses and announces PAUSED. */
class SubscriptionServicePastDueTest {

    @Test
    void lapsedPastDuePausesAndPublishes() {
        OrgSubscriptionRepository repo = mock(OrgSubscriptionRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        UUID orgId = UUID.randomUUID();
        OrgSubscription lapsed = OrgSubscription.of(orgId, UUID.randomUUID());
        lapsed.markStatus(OrgSubscription.Status.PAST_DUE);
        given(repo.findByStatusAndUpdatedAtBefore(any(), any())).willReturn(List.of(lapsed));
        given(plans.findById(any())).willReturn(Optional.empty());

        SubscriptionService service = new SubscriptionService(repo, plans,
                mock(EntitlementResolver.class), events, mock(AuditLog.class),
                new SimpleMeterRegistry(), Clock.systemUTC(),
                mock(TwoLevelCacheManager.class), mock(TransactionTemplate.class));

        assertThat(service.pauseLapsedPastDue(Duration.ofDays(7))).isEqualTo(1);
        assertThat(lapsed.getStatus()).isEqualTo(OrgSubscription.Status.PAUSED);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(((SubscriptionChanged) published.getValue()).status()).isEqualTo("PAUSED");
    }
}
