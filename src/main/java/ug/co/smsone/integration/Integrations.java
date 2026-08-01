package ug.co.smsone.integration;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * How a module resolves the external provider serving an organization for a capability. Returns the
 * org's own enabled integration, else the platform default, else empty — the caller then decides
 * (a notification channel might fall back to its static config). Settings come back DECRYPTED: the
 * caller is an in-JVM consumer that needs the creds to call the provider; the REST surface masks.
 */
public interface Integrations {

    enum Kind { SMS_PROVIDER, EMAIL_PROVIDER, PAYMENT_GATEWAY }

    Optional<ResolvedIntegration> resolve(UUID organizationId, Kind kind);

    record ResolvedIntegration(String provider, Map<String, String> settings) {
    }
}
