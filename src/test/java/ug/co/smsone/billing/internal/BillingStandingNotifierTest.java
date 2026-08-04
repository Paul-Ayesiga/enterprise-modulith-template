package ug.co.smsone.billing.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import ug.co.smsone.notification.NotificationRequest;
import ug.co.smsone.notification.Notifications;
import ug.co.smsone.organization.OrgContacts;
import ug.co.smsone.subscription.SubscriptionChanged;

/** Dunning's voice: PAST_DUE and PAUSED reach the owners with the org context; nothing else does. */
class BillingStandingNotifierTest {

    private final OrgContacts contacts = mock(OrgContacts.class);
    private final Notifications notifications = mock(Notifications.class);
    private final BillingStandingNotifier notifier =
            new BillingStandingNotifier(provider(contacts), provider(notifications));

    @Test
    void pausedReachesTheOwnersWithTheOrgContext() {
        UUID orgId = UUID.randomUUID();
        given(contacts.ownerEmails(orgId)).willReturn(List.of("owner@acme.test"));

        notifier.on(new SubscriptionChanged(orgId, "PRO", "PAUSED", Instant.now()));

        ArgumentCaptor<NotificationRequest> sent = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).dispatch(sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().subject()).contains("paused");
        org.assertj.core.api.Assertions.assertThat(sent.getValue().metadata()).containsEntry("orgId", orgId.toString());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().recipients()).hasSize(1);
    }

    @Test
    void ordinaryPlanChangesStaySilent() {
        notifier.on(new SubscriptionChanged(UUID.randomUUID(), "PRO", "ACTIVE", Instant.now()));
        verify(notifications, never()).dispatch(any());
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }
}
