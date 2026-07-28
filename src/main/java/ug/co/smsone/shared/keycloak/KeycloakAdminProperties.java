package ug.co.smsone.shared.keycloak;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for Keycloak Admin REST calls (user + organization provisioning). The app
 * authenticates as a confidential service-account client via {@code client_credentials}. Timeouts
 * are mandatory: admin calls run inside user-facing requests, so a hung Keycloak must fail fast,
 * never pin request threads (or, worse, DB connections) indefinitely.
 */
@ConfigurationProperties(prefix = "app.keycloak-admin")
public record KeycloakAdminProperties(String baseUrl, String realm, String clientId, String clientSecret,
        Duration connectTimeout, Duration readTimeout) {

    public KeycloakAdminProperties {
        if (realm == null || realm.isBlank()) {
            realm = "smsone";
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            readTimeout = Duration.ofSeconds(15);
        }
    }

    public String tokenUri() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String adminBaseUri() {
        return baseUrl + "/admin/realms/" + realm;
    }
}
