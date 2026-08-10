package ug.co.smsone.shared.persistence;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.tenancy.datasources.*} — the named databases one deployment may route tenants to
 * (ADR 0011 §4.1). Empty on every existing deployment, and an empty map is the shipped default: the
 * registry then holds nothing but the primary pool and the routing path is byte-for-byte what it was
 * before this record existed.
 *
 * <pre>
 * app.tenancy.datasources.analytics-eu.url:      ${TENANT_DS_ANALYTICS_EU_URL}
 * app.tenancy.datasources.analytics-eu.username: ${TENANT_DS_ANALYTICS_EU_USER}
 * app.tenancy.datasources.analytics-eu.password: ${TENANT_DS_ANALYTICS_EU_PASSWORD}
 * app.tenancy.datasources.analytics-eu.pool.maximum-pool-size: 8
 * </pre>
 *
 * <p>Everything dangerous about this block is validated here, in the compact constructors, so a bad
 * value fails at startup and not at 04:00 (the house rule {@code SoftDeleteProperties} states):
 *
 * <ul>
 *   <li><strong>{@code primary} is reserved and may not appear in the map.</strong> The platform pool
 *       is {@code spring.datasource}, and a second definition of it would be two truths about one
 *       database — the same URL with different pool arithmetic, or worse, a different URL.</li>
 *   <li><strong>Names match {@link #NAME}.</strong> The name is a map key and a
 *       {@code tenant_placement.datasource_name} value, never SQL — the pattern is about keeping the
 *       registry greppable and the config keys typo-resistant, not about injection.</li>
 *   <li><strong>{@code url}, {@code username} and {@code password} are required.</strong> A remote a
 *       tenant is placed on with half a credential is a per-tenant outage discovered by the tenant;
 *       failing the boot names the key instead. Use {@code ${ENV_VAR}} indirection for the password,
 *       never a literal — this record cannot verify that, so it is stated here instead.</li>
 *   <li><strong>At most {@link #MAX_DATASOURCES} names</strong> (ADR 0011 §10 Q3's default). Each name
 *       is a Hikari pool per pod — N remotes × {@code maximum-pool-size} × pods on top of primary's
 *       own — and past ten the budget stops being a number and becomes a spreadsheet nobody has
 *       written. The refusal lives here, loudly, like the silo ceiling lives in the promoter.</li>
 * </ul>
 */
@ConfigurationProperties("app.tenancy")
public record TenantDataSourceProperties(Map<String, Definition> datasources) {

    /** ADR 0011 §4.1 verbatim. Lower-case, starts with a letter, at most 32 characters. */
    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_-]{0,31}$");

    /** The reserved name: {@code tenant_placement.datasource_name}'s default, meaning the platform pool. */
    public static final String PRIMARY = "primary";

    /** ADR 0011 §10 Q3's default cap, held until somebody measures a bigger fleet. */
    public static final int MAX_DATASOURCES = 10;

    public TenantDataSourceProperties {
        Map<String, Definition> validated = new LinkedHashMap<>();
        if (datasources != null) {
            if (datasources.size() > MAX_DATASOURCES) {
                throw new IllegalStateException("app.tenancy.datasources names " + datasources.size()
                        + " databases and the cap is " + MAX_DATASOURCES + " (ADR 0011 §10 Q3): each name"
                        + " is a connection pool per pod, and past ten the arithmetic needs measuring,"
                        + " not another entry.");
            }
            datasources.forEach((name, definition) -> {
                if (PRIMARY.equals(name)) {
                    throw new IllegalStateException("app.tenancy.datasources." + PRIMARY + " is reserved:"
                            + " the platform pool is spring.datasource, and a second definition of it"
                            + " would be two truths about one database (ADR 0011 §4.1). Remove the entry.");
                }
                if (name == null || !NAME.matcher(name).matches()) {
                    throw new IllegalStateException("'" + name + "' is not a legal tenant datasource name"
                            + " (expected " + NAME.pattern() + ", ADR 0011 §4.1) — it is what"
                            + " tenant_placement.datasource_name selects, so a name that cannot be typed"
                            + " the same way twice is a per-tenant outage waiting for a typo.");
                }
                if (definition == null) {
                    throw new IllegalStateException("app.tenancy.datasources." + name + " has no body —"
                            + " url, username and password are required.");
                }
                definition.requireComplete(name);
                validated.put(name, definition);
            });
        }
        datasources = Map.copyOf(validated);
    }

    /** One named database. {@code pool} may be omitted wholesale; every number in it has a default. */
    public record Definition(String url, String username, String password, Pool pool) {

        public Definition {
            pool = pool == null ? new Pool(null, null, null) : pool;
        }

        private void requireComplete(String name) {
            requireText(url, name, "url");
            requireText(username, name, "username");
            requireText(password, name, "password");
        }

        private static void requireText(String value, String name, String key) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("app.tenancy.datasources." + name + "." + key
                        + " is required — a tenant placed on '" + name + "' would otherwise fail at its"
                        + " first borrow instead of at this deployment's startup (ADR 0011 §4.1).");
            }
        }
    }

    /**
     * Pool numbers, each with the ADR's default and a reason:
     *
     * <ul>
     *   <li>{@code maximum-pool-size} 8 — half the primary's 16; a remote serves one database's worth
     *       of tenants, not the fleet (ADR 0011 §8 item 6 is the budget arithmetic).</li>
     *   <li>{@code minimum-idle} 0 — a remote nobody routes to holds no connections, so a configured
     *       name may safely lead its first placement by a release.</li>
     *   <li>{@code connection-timeout} 5 s — this number doubles as the "unreachable" detector
     *       (ADR 0011 §2.2): a borrow that cannot complete inside it is a connection-shaped failure,
     *       and it must stay well under any request or lease budget that waits on it.</li>
     * </ul>
     */
    public record Pool(Integer maximumPoolSize, Integer minimumIdle, Duration connectionTimeout) {

        public Pool {
            maximumPoolSize = maximumPoolSize == null ? 8 : maximumPoolSize;
            minimumIdle = minimumIdle == null ? 0 : minimumIdle;
            connectionTimeout = connectionTimeout == null ? Duration.ofSeconds(5) : connectionTimeout;
            if (maximumPoolSize < 1) {
                throw new IllegalStateException("maximum-pool-size must be at least 1, was "
                        + maximumPoolSize + " — a pool that can lend nothing is a per-tenant outage"
                        + " spelled as a number.");
            }
            if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
                throw new IllegalStateException("minimum-idle must be between 0 and maximum-pool-size ("
                        + maximumPoolSize + "), was " + minimumIdle);
            }
            if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
                throw new IllegalStateException("connection-timeout must be positive, was "
                        + connectionTimeout + " — it is also the 'unreachable' detector (ADR 0011 §2.2),"
                        + " and a zero one asks Hikari for its 30 s default, which would stall a request"
                        + " for six times the number every staleness contract is written against.");
            }
        }
    }
}
