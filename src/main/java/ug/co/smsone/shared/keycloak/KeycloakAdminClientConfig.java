package ug.co.smsone.shared.keycloak;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds {@code keycloakAdminRestClient} — a {@link RestClient} rooted at
 * {@code /admin/realms/{realm}} that attaches a fresh service-account bearer token to every request.
 * Injected by the identity/organization Keycloak gateways.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeycloakAdminProperties.class)
class KeycloakAdminClientConfig {

    @Bean
    RestClient keycloakAdminRestClient(KeycloakAdminProperties properties, KeycloakServiceAccountTokens tokens) {
        return RestClient.builder()
                .baseUrl(properties.adminBaseUri())
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.bearer());
                    return execution.execute(request, body);
                })
                .build();
    }
}
