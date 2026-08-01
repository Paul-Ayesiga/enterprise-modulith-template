package ug.co.smsone.integration.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Hub config — just the at-rest encryption key for secret settings. */
@ConfigurationProperties(prefix = "app.integration")
record IntegrationProperties(String secretEncryptionKey) {

    IntegrationProperties {
        if (secretEncryptionKey == null || secretEncryptionKey.isBlank()) {
            throw new IllegalStateException(
                    "app.integration.secret-encryption-key must not be blank (set INTEGRATION_SECRET_KEY)");
        }
    }
}
