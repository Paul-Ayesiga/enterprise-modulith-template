package ug.co.smsone.webhooks.internal;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-way, idempotent startup hop for rows created before secrets were encrypted at rest: any
 * plaintext secret (no {@code enc:v1:} prefix) is re-stored encrypted. Not a Flyway migration
 * because SQL cannot run the cipher; raw JDBC (soft-deleted rows included) because a restored
 * subscription must come back with an encrypted secret too.
 */
@Component
class WebhookSecretEncryptionMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecretEncryptionMigrator.class);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    WebhookSecretEncryptionMigrator(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<UUID> plaintextRows = jdbc.queryForList(
                "select id from webhook_subscription where secret not like 'enc:v1:%'", UUID.class);
        for (UUID id : plaintextRows) {
            String secret = jdbc.queryForObject(
                    "select secret from webhook_subscription where id = ?", String.class, id);
            jdbc.update("update webhook_subscription set secret = ? where id = ?",
                    cipher.encrypt(secret), id);
        }
        if (!plaintextRows.isEmpty()) {
            log.info("Encrypted {} pre-existing webhook signing secrets at rest", plaintextRows.size());
        }
    }
}
