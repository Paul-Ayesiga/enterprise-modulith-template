package ug.co.smsone.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provisioning behaviour. The {@code gate-enabled} flag itself is read by {@code @ConditionalOnProperty}. */
@ConfigurationProperties(prefix = "app.provisioning")
record ProvisioningProperties(Duration inviteLifespan, String redirectUri, String appClientId) {

    ProvisioningProperties {
        if (inviteLifespan == null || inviteLifespan.isZero() || inviteLifespan.isNegative()) {
            inviteLifespan = Duration.ofHours(12);
        }
        if (appClientId == null || appClientId.isBlank()) {
            appClientId = "smsone-web";
        }
    }
}
