package ug.co.smsone.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ug.co.smsone.shared.security.PlatformRole;

/**
 * Provisioning behaviour. The {@code gate-enabled} flag itself is read by {@code @ConditionalOnProperty}.
 *
 * <p>{@code defaultRealmRole} is the baseline realm role every provisioned account receives (blank to
 * grant none). It must never be a platform role: provisioning is invite-driven and reachable by any
 * org member holding {@code member:invite}, so a misconfiguration here would let a tenant mint
 * platform operators. Rejected at startup rather than trusted to review.
 */
@ConfigurationProperties(prefix = "app.provisioning")
record ProvisioningProperties(Duration inviteLifespan, String redirectUri, String appClientId,
        String defaultRealmRole) {

    ProvisioningProperties {
        if (inviteLifespan == null || inviteLifespan.isZero() || inviteLifespan.isNegative()) {
            inviteLifespan = Duration.ofHours(12);
        }
        if (appClientId == null || appClientId.isBlank()) {
            appClientId = "smsone-web";
        }
        defaultRealmRole = defaultRealmRole == null ? "" : defaultRealmRole.trim();
        if (PlatformRole.isPlatformRole(defaultRealmRole)) {
            throw new IllegalArgumentException(
                    "app.provisioning.default-realm-role must not be a platform role, but was '"
                            + defaultRealmRole + "' — provisioning would mint platform operators.");
        }
    }

    boolean grantsDefaultRealmRole() {
        return !defaultRealmRole.isEmpty();
    }
}
