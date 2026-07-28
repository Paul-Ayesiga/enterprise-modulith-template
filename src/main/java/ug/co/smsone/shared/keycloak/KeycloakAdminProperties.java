package ug.co.smsone.shared.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for Keycloak Admin REST calls (user + organization provisioning). The app
 * authenticates as a confidential service-account client via {@code client_credentials}.
 */
@ConfigurationProperties(prefix = "app.keycloak-admin")
public record KeycloakAdminProperties(String baseUrl, String realm, String clientId, String clientSecret) {

    public KeycloakAdminProperties {
        if (realm == null || realm.isBlank()) {
            realm = "smsone";
        }
    }

    public String tokenUri() {
        return baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String adminBaseUri() {
        return baseUrl + "/admin/realms/" + realm;
    }
}
