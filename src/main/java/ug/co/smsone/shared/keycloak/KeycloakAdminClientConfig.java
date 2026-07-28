package ug.co.smsone.shared.keycloak;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds {@code keycloakAdminRestClient} — a {@link RestClient} rooted at
 * {@code /admin/realms/{realm}} that attaches a fresh service-account bearer token to every request.
 * Injected by the identity/organization Keycloak gateways. Connect/read timeouts always apply, and a
 * 401 (token rejected after a secret rotation or Keycloak restart) refreshes the cached token and
 * retries once instead of failing every call until local expiry.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KeycloakAdminProperties.class)
class KeycloakAdminClientConfig {

    @Bean
    RestClient keycloakAdminRestClient(KeycloakAdminProperties properties, KeycloakServiceAccountTokens tokens) {
        return RestClient.builder()
                .baseUrl(properties.adminBaseUri())
                .requestFactory(timedRequestFactory(properties))
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.bearer());
                    ClientHttpResponse response = execution.execute(request, body);
                    if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                        response.close();
                        tokens.invalidate();
                        request.getHeaders().setBearerAuth(tokens.bearer());
                        return execution.execute(request, body);
                    }
                    return response;
                })
                .build();
    }

    static ClientHttpRequestFactory timedRequestFactory(KeycloakAdminProperties properties) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
