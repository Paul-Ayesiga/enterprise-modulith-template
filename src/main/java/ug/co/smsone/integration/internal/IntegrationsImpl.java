package ug.co.smsone.integration.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.integration.Integrations;
import ug.co.smsone.shared.tenancy.CrossDatabaseWrites;
import ug.co.smsone.shared.tenancy.SplitTables;

/**
 * The resolution port: the org's own enabled integration, else the platform default, else empty.
 * Settings come back DECRYPTED (secret values through the cipher) because the caller is an in-JVM
 * consumer that needs the creds; the REST surface is what masks.
 *
 * <h2>Both homes, by name, on whatever axis the caller happens to be (ADR 0010 §2 rows 22–23)</h2>
 *
 * <p>{@code integration} is a split table and <b>null {@code org_id} means PLATFORM DEFAULT</b> — the
 * configuration every org without an override of its own runs on — not "unknown tenant". So this one
 * call spans both homes: the override in the tenant's schema, the default in {@code platform}.
 *
 * <p><b>Every read here names its schema, and that is a deliberate exception to the rule that the
 * tenant half is reached unqualified.</b> This is a PORT, and its callers are not requests: the
 * notification delivery worker resolves an org's SMS provider from a cluster-wide poll on the platform
 * axis, {@code PaymentService} resolves a gateway from inside its own transaction. Neither can pin —
 * one has no tenant on its thread, the other has already borrowed its connection — so an unqualified
 * read would resolve the platform copy for both and every org-specific provider would quietly stop
 * being used, with the platform default silently substituted. Naming the home makes the answer the
 * same from any thread. {@link SplitTables#homeOf} carries the Phase 5 obligation this creates.
 *
 * <p>JDBC rather than a second JPA mapping because of {@code integration_setting}: it is an EAGER
 * {@code @ElementCollection}, so an entity fetched from one schema would still load its settings
 * through the collection's own unqualified mapping — from a different schema — and an integration would
 * come back wearing another home's credentials.
 *
 * <h2>ADR 0011: naming the home stopped being enough, and this port fails in the harder direction</h2>
 *
 * <p>The argument above ends at "naming the home makes the answer the same from any thread". Since the
 * router can put an organization's schema in another DATABASE, a name is half an address and this port
 * has the worst of the two directions: its principal caller is
 * {@code NotificationDeliveryWorker}, pinned to PLATFORM by necessity (its status writes are
 * platform-tier), resolving a REMOTE org's SMS provider. That names {@code t_<32hex>.integration} on a
 * primary connection, where the schema does not exist — so every message to a tenant with its own
 * provider fails at the point of send, on a background thread, for exactly the tenants whose isolation
 * was the reason to move them.
 *
 * <p>{@link CrossDatabaseWrites#callInHomeOf} wraps each home's read as a unit — the {@code integration}
 * row and its {@code integration_setting} children must come from ONE home on ONE connection, which is
 * why the wrap is around {@code enabledIntegration} and not around the individual statements. Resolution
 * therefore becomes at most two borrows for a remote org (its own home, then the platform default) where
 * it was one, and stays exactly one connection and one transaction for every co-located caller — which
 * is every caller on every deployment with no remote datasource configured. Nothing about the resolution
 * ORDER changes: the override still wins, the default is still the fallback, and an absent answer is
 * still an answer.
 */
@Service
class IntegrationsImpl implements Integrations {

    private final IntegrationSecretCipher cipher;
    private final JdbcTemplate jdbc;
    private final CrossDatabaseWrites homes;

    IntegrationsImpl(IntegrationSecretCipher cipher, JdbcTemplate jdbc, CrossDatabaseWrites homes) {
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.homes = homes;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResolvedIntegration> resolve(UUID organizationId, Kind kind) {
        if (organizationId != null) {
            Optional<ResolvedIntegration> own = enabledIntegration(organizationId, kind);
            if (own.isPresent()) {
                return own;
            }
        }
        return enabledIntegration(null, kind);
    }

    /**
     * The one live, enabled integration for a scope, with its settings — or empty.
     *
     * <p>{@code deleted_at is null} is written out because nothing outside the entity mapping applies
     * {@code @SQLRestriction}, and a cancelled integration resolving as live would send traffic through
     * a provider somebody deliberately turned off. V33's two partial unique indexes
     * ({@code uq_integration_org_kind_live}, {@code uq_integration_platform_kind_live}) are what make
     * "at most one" true, so the first row is the only row.
     */
    private Optional<ResolvedIntegration> enabledIntegration(UUID orgId, Kind kind) {
        // One home, one connection, for both statements: the settings must come from the same database
        // as the integration row that owns them, and since ADR 0011 that database is not always the
        // caller's. A no-op — same connection, same transaction — whenever it is.
        return homes.callInHomeOf(orgId, () -> readEnabledIntegration(orgId, kind));
    }

    private Optional<ResolvedIntegration> readEnabledIntegration(UUID orgId, Kind kind) {
        String home = SplitTables.homeOf(orgId);
        List<Map<String, Object>> rows = orgId == null
                ? jdbc.queryForList("select id, provider from " + home + ".integration"
                        + " where deleted_at is null and org_id is null and kind = ? and enabled = true",
                        kind.name())
                : jdbc.queryForList("select id, provider from " + home + ".integration"
                        + " where deleted_at is null and org_id = ? and kind = ? and enabled = true",
                        orgId, kind.name());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> integration = rows.getFirst();
        return Optional.of(new ResolvedIntegration(String.valueOf(integration.get("provider")),
                settingsOf(home, (UUID) integration.get("id"))));
    }

    /** The settings from the SAME home as their parent — {@code integration_setting} has no scope of its own. */
    private Map<String, String> settingsOf(String home, UUID integrationId) {
        Map<String, String> settings = new LinkedHashMap<>();
        for (Map<String, Object> setting : jdbc.queryForList(
                "select setting_key, setting_value, is_secret from " + home + ".integration_setting"
                        + " where integration_id = ?", integrationId)) {
            String value = String.valueOf(setting.get("setting_value"));
            settings.put(String.valueOf(setting.get("setting_key")),
                    Boolean.TRUE.equals(setting.get("is_secret")) ? cipher.decrypt(value) : value);
        }
        return settings;
    }
}
