package ug.co.smsone.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import ug.co.smsone.integration.Integrations;
import ug.co.smsone.notification.NotificationMessage;

/**
 * The org-aware routing contract: the delivery's orgId picks the org's hub-configured provider (a
 * database choice, org override over platform default); no hub entry falls back to the Speeda env
 * path; a provider with no shipped adapter fails loudly naming the known ones.
 */
class SmsChannelRouterTest {

    private record StubGateway(String provider, AtomicReference<Map<String, String>> seen) implements SmsGateway {
        @Override
        public void send(NotificationMessage message, Map<String, String> settings) {
            seen.set(settings);
        }
    }

    private static NotificationMessage forOrg(UUID orgId) {
        return new NotificationMessage("256772123456", "s", "b",
                orgId == null ? Map.of() : Map.of("orgId", orgId.toString()));
    }

    @Test
    void routesToTheOrgsConfiguredProviderWithItsSettings() {
        UUID orgId = UUID.randomUUID();
        AtomicReference<Map<String, String>> speedaSeen = new AtomicReference<>();
        AtomicReference<Map<String, String>> otherSeen = new AtomicReference<>();
        Integrations hub = (org, kind) -> {
            assertThat(org).isEqualTo(orgId); // the delivery's org drives resolution
            return Optional.of(new Integrations.ResolvedIntegration("acme-sms", Map.of("apiKey", "k1")));
        };
        SmsChannelRouter router = new SmsChannelRouter(List.of(
                new StubGateway("speedamobile", speedaSeen), new StubGateway("acme-sms", otherSeen)),
                provider(hub));

        router.send(forOrg(orgId));

        assertThat(otherSeen.get()).containsEntry("apiKey", "k1");
        assertThat(speedaSeen.get()).isNull();
    }

    @Test
    void noHubEntryFallsBackToTheSpeedaEnvPath() {
        AtomicReference<Map<String, String>> speedaSeen = new AtomicReference<>();
        Integrations hub = (org, kind) -> Optional.empty();
        SmsChannelRouter router = new SmsChannelRouter(
                List.of(new StubGateway("speedamobile", speedaSeen)), provider(hub));

        router.send(forOrg(null));

        assertThat(speedaSeen.get()).isEmpty(); // empty settings = "use your env fallback"
    }

    @Test
    void anUnshippedProviderFailsLoudlyNamingTheKnownOnes() {
        Integrations hub = (org, kind) -> Optional.of(
                new Integrations.ResolvedIntegration("twilio", Map.of()));
        SmsChannelRouter router = new SmsChannelRouter(
                List.of(new StubGateway("speedamobile", new AtomicReference<>())), provider(hub));

        assertThatThrownBy(() -> router.send(forOrg(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("twilio")
                .hasMessageContaining("speedamobile");
    }

    private static ObjectProvider<Integrations> provider(Integrations hub) {
        return new ObjectProvider<>() {
            @Override
            public Integrations getObject() {
                return hub;
            }

            @Override
            public Integrations getIfAvailable() {
                return hub;
            }
        };
    }
}
