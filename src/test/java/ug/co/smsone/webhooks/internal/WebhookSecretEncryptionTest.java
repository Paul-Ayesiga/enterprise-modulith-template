package ug.co.smsone.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Secrets at rest: new subscriptions store only ciphertext (the delivery test proves the sender
 * decrypts before signing), and the startup migrator rewrites any pre-encryption plaintext row —
 * idempotently, so a second boot changes nothing.
 */
class WebhookSecretEncryptionTest extends AbstractIntegrationTest {

    @Autowired
    private WebhookSecretEncryptionMigrator migrator;

    @Autowired
    private SecretCipher cipher;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theMigratorEncryptsPreExistingPlaintextRowsIdempotently() {
        UUID id = UUID.randomUUID();
        String plaintext = "whsec_legacy_" + UUID.randomUUID();
        jdbc.update("insert into webhook_subscription (id, org_id, url, secret, event_types, status, "
                + "version, created_at) "
                + "values (?, ?, 'https://hooks.example.com/legacy', ?, 'org.member.added', 'ACTIVE', 0, now())",
                id, UUID.randomUUID(), plaintext);

        migrator.run(null);
        String stored = jdbc.queryForObject(
                "select secret from webhook_subscription where id = ?", String.class, id);
        assertThat(stored).startsWith(SecretCipher.PREFIX);
        assertThat(cipher.decrypt(stored)).as("the signing plaintext survives the hop").isEqualTo(plaintext);

        migrator.run(null); // second boot: already-encrypted rows are untouched
        assertThat(jdbc.queryForObject(
                "select secret from webhook_subscription where id = ?", String.class, id))
                .isEqualTo(stored);
    }

    @Test
    void cipherRoundTripsAndRefusesTampering() {
        String secret = "whsec_" + UUID.randomUUID();
        String encrypted = cipher.encrypt(secret);
        assertThat(encrypted).startsWith(SecretCipher.PREFIX).isNotEqualTo(cipher.encrypt(secret));
        assertThat(cipher.decrypt(encrypted)).isEqualTo(secret);
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }
}
