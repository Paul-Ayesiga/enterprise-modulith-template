package ug.co.smsone.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provisioning behaviour. The {@code gate-enabled} flag itself is read by {@code @ConditionalOnProperty}. */
@ConfigurationProperties(prefix = "app.provisioning")
record ProvisioningProperties(TempCredentialMode tempCredentialMode, Duration inviteLifespan,
        String redirectUri, String appClientId) {

    ProvisioningProperties {
        if (tempCredentialMode == null) {
            tempCredentialMode = TempCredentialMode.EMAIL;
        }
        if (inviteLifespan == null || inviteLifespan.isZero() || inviteLifespan.isNegative()) {
            inviteLifespan = Duration.ofHours(12);
        }
        if (appClientId == null || appClientId.isBlank()) {
            appClientId = "smsone-web";
        }
    }

    /** EMAIL = Keycloak emails an action link (admin never sees a password). TEMP_PASSWORD = set a one-time password. */
    enum TempCredentialMode {
        EMAIL,
        TEMP_PASSWORD
    }
}
