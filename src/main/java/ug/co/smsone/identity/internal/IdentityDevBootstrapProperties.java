package ug.co.smsone.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dev-only seed of the platform admin's {@code person}. The {@code enabled} flag gates the runner. */
@ConfigurationProperties(prefix = "app.identity.dev-bootstrap")
record IdentityDevBootstrapProperties(String email) {
}
