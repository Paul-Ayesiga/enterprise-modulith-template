package ug.co.smsone.webhooks.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * One-way, idempotent startup hop for rows created before secrets were encrypted at rest: any
 * plaintext secret (no {@code enc:v1:} prefix) is re-stored encrypted. Not a Flyway migration
 * because SQL cannot run the cipher; raw JDBC (soft-deleted rows included) because a restored
 * subscription must come back with an encrypted secret too.
 *
 * <p>It runs on the startup path, so its cost is boot latency — which is why the work is batched
 * ({@code BATCH} rows per SELECT, one {@code batchUpdate} per page) rather than the row-at-a-time
 * {@code 1 + 2N} it used to be, and why the common case is ONE statement: once every row carries the
 * prefix, the first page comes back empty and this returns without logging or writing anything. That
 * probe is the only permanent cost, and it is a scan — a partial index on the un-prefixed rows would
 * make it index-only, and a deployment whose rows are all encrypted can drop this runner outright.
 */
@Component
class WebhookSecretEncryptionMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecretEncryptionMigrator.class);
    /** Rows per round trip. Small enough that a boot is never blocked on one huge statement. */
    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    WebhookSecretEncryptionMigrator(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /**
     * Declares the platform axis (ADR 0010 §3.4). An {@code ApplicationRunner} runs on the boot thread
     * with no request behind it, and this one goes straight to a {@code JdbcTemplate} on the routing
     * {@code DataSource} — so without the pin its very first probe fails on
     * {@code relation "webhook_subscription" does not exist}, on every boot, before the application is
     * ready. That failure would be loud, which is the design working; the pin is what makes it not
     * happen.
     *
     * <p>PHASE 2: {@code webhook_subscription} is tenant-tier, so once the tables split this becomes a
     * pass per tenant — {@code runAs(orgId)} around the paged walk, with the keyset cursor restarting
     * inside each. A one-way migrator is exactly the shape that has to be revisited then, because
     * "every row" stops being a single query.
     */
    @Override
    public void run(ApplicationArguments args) {
        TenantContext.runAsPlatform(this::encryptLegacySecrets);
    }

    private void encryptLegacySecrets() {
        int encrypted = 0;
        UUID after = null;
        while (true) {
            List<Legacy> pending = nextPage(after);
            if (pending.isEmpty()) {
                break;
            }
            List<Object[]> rewrites = new ArrayList<>(pending.size());
            for (Legacy row : pending) {
                rewrites.add(new Object[]{cipher.encrypt(row.secret()), row.id()});
            }
            jdbc.batchUpdate("update webhook_subscription set secret = ? where id = ?", rewrites);
            encrypted += pending.size();
            after = pending.getLast().id();
        }
        if (encrypted > 0) {
            log.info("Encrypted {} pre-existing webhook signing secrets at rest", encrypted);
        }
    }

    /**
     * The next page of un-prefixed rows, walked by {@code id} rather than by re-running the bare
     * predicate.
     *
     * <p>The trap in the obvious version: because each pass rewrites exactly the rows it read, the
     * predicate looks self-consuming, so "select the first BATCH again" seems to advance. It only
     * advances while {@link SecretCipher#encrypt} keeps producing the prefix this filters on — the
     * day a cipher version changes that, the loop spins forever on the same page. Keying on the
     * ORDER instead makes termination a property of the walk, not of the cipher's output.
     */
    private List<Legacy> nextPage(UUID after) {
        String sql = "select id, secret from webhook_subscription where secret not like ?"
                + (after == null ? "" : " and id > ?")
                + " order by id limit " + BATCH;
        Object[] params = after == null
                ? new Object[]{SecretCipher.PREFIX + "%"}
                : new Object[]{SecretCipher.PREFIX + "%", after};
        return jdbc.query(sql, (rs, n) -> new Legacy(rs.getObject("id", UUID.class), rs.getString("secret")),
                params);
    }

    /** A row still holding its signing secret in the clear. */
    private record Legacy(UUID id, String secret) {
    }
}
