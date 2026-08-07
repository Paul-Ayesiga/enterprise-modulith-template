package ug.co.smsone.billing.internal;

import io.swagger.v3.oas.annotations.Hidden;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.security.GatewaySecretVerifier;
import ug.co.smsone.shared.security.OrgLookup;

/**
 * The usage-report seam: the gateway flushes per-consumer request counts here every minute;
 * they land as per-org, per-day upserts in the api_usage_daily ledger. Internal (permit-listed
 * path, shared-secret authenticated, hidden from the spec) — the same contract as the other
 * gateway seams. Non-UUID consumers are skipped: only org-attributable usage can ever bill.
 *
 * <p><b>This is the one tenant column in the schema whose writer lives in another deployable</b>, and
 * the consumer ids it posts are NOT ours. The gateway has no name for a tenant except the one its own
 * token carries — the Keycloak organization id — so each consumer is resolved through
 * {@code external_organization} ({@link OrgLookup}) before the upsert. V44 states why resolving on
 * THIS side beats flipping the gateway in lockstep: a lockstep deploy has a window in which the two
 * deployables disagree about what a tenant is called, and the disagreement is silent on both sides —
 * a well-formed UUID that matches no organization row inserts perfectly happily here, because
 * {@code org_id} is a soft ref with no cross-module FK. Usage would then split across two key spaces
 * and Kill Bill would under-bill with nothing anywhere failing to notice. Resolving at the seam also
 * covers the reports already in flight across a cutover, which a lockstep deploy cannot.
 */
@Hidden
@RestController
@RequestMapping("/internal/gateway/usage-report")
class GatewayUsageReportController {

    private final GatewaySecretVerifier secretVerifier;
    private final OrgLookup organizations;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String issuer;

    /**
     * The issuer comes from the resource-server property — the same one {@code OrgResolver} and
     * {@code PersonResolver} read, and for the same reason: {@code external_organization.issuer} must
     * be byte-identical to the {@code iss} the edge accepts, and a second key drifting from it would
     * silently resolve every consumer to nothing. There is no token on this request to take it from;
     * the gateway authenticates with a shared secret, and its consumers all come from this realm.
     */
    GatewayUsageReportController(GatewaySecretVerifier secretVerifier, OrgLookup organizations,
            JdbcTemplate jdbc, Clock clock,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        this.secretVerifier = secretVerifier;
        this.organizations = organizations;
        this.jdbc = jdbc;
        this.clock = clock;
        this.issuer = issuer;
    }

    record UsageReport(Map<String, Long> counts) {
    }

    @PostMapping
    void report(@RequestHeader(name = "X-Gateway-Secret", required = false) String presentedSecret,
            @RequestBody UsageReport report) {
        secretVerifier.verify(presentedSecret);
        if (report == null || report.counts() == null || report.counts().isEmpty()) {
            return;
        }
        Date day = Date.valueOf(LocalDate.now(clock));
        report.counts().forEach((consumer, requests) -> {
            UUID orgId = resolve(consumer);
            if (orgId == null || requests == null || requests <= 0) {
                return;
            }
            jdbc.update("""
                    insert into api_usage_daily (org_id, day, requests)
                    values (?, ?, ?)
                    on conflict (org_id, day)
                    do update set requests = api_usage_daily.requests + excluded.requests
                    """, orgId, day, requests);
        });
    }

    /**
     * A consumer id as the gateway knows it → {@code organization.id}, or null to skip the entry.
     *
     * <p>Skipped rather than inserted-as-is when nothing links it: the alternative is a row keyed by
     * an identifier from another key space, which no later query would ever find and no constraint
     * would ever reject. Consumers that are not org-attributable at all (the gateway meters some that
     * are not tenants) resolve to nothing by the same path, which is why there is no separate
     * UUID-shape test — the link table is the whole membership test.
     */
    private UUID resolve(String consumer) {
        if (consumer == null || consumer.isBlank()) {
            return null;
        }
        return organizations.organizationId(issuer, consumer.trim(), null).orElse(null);
    }
}
