package ug.co.smsone.shared.keycloak;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Fetches and caches the Keycloak service-account access token ({@code client_credentials}),
 * refreshing shortly before expiry. Self-managed (no {@code keycloak-admin-client}, no
 * {@code oauth2-client}) — a tiny {@link RestClient} call keeps the template's dependency surface
 * and Jackson-3 stack clean. Thread-safe.
 */
@Component
class KeycloakServiceAccountTokens {

    private static final long REFRESH_SKEW_SECONDS = 30;

    private final RestClient tokenClient;
    private final MultiValueMap<String, String> form;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String accessToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    KeycloakServiceAccountTokens(KeycloakAdminProperties properties) {
        this.tokenClient = RestClient.create(properties.tokenUri());
        this.form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
    }

    String bearer() {
        String current = accessToken;
        if (current != null && Instant.now().isBefore(expiresAt)) {
            return current;
        }
        lock.lock();
        try {
            if (accessToken != null && Instant.now().isBefore(expiresAt)) {
                return accessToken;
            }
            Map<?, ?> response = tokenClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("access_token") instanceof String token)) {
                throw new IllegalStateException("Keycloak did not return an access_token");
            }
            long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 60L;
            this.accessToken = token;
            this.expiresAt = Instant.now().plusSeconds(Math.max(1, expiresIn - REFRESH_SKEW_SECONDS));
            return token;
        } finally {
            lock.unlock();
        }
    }
}
