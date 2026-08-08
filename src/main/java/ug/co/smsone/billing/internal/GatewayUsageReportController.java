package ug.co.smsone.billing.internal;

import io.swagger.v3.oas.annotations.Hidden;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.organization.OrgDirectory;
import ug.co.smsone.shared.security.GatewaySecretVerifier;

/**
 * The usage-report seam: the gateway flushes per-consumer request counts here every minute;
 * they land as per-org, per-day upserts in the api_usage_daily ledger. Internal (permit-listed
 * path, shared-secret authenticated, hidden from the spec) — the same contract as the other
 * gateway seams. Non-UUID consumers are skipped: only org-attributable usage can ever bill.
 *
 * <p><b>The consumer id IS an {@code organization.id}.</b> The gateway's only source of one is API-key
 * introspection, and {@code GatewayIntrospectionController} answers with {@code api_key.org_id} — our
 * own tenant key, not a provider's. That is precisely what the identity decoupling bought: before it,
 * the tenant key was Keycloak's org id and this seam genuinely had to translate.
 *
 * <p>It briefly did the translation anyway. Resolving through {@code external_organization} here looks
 * right — it is the same lookup the edge does — but that table is keyed by PROVIDER-MINTED ids, and an
 * {@code organization.id} is never one of those, so every consumer resolved to nothing and the ledger
 * silently took no rows at all. Nothing failed: usage simply stopped existing and Kill Bill billed
 * zero. The sibling seam ({@code GatewayQuotaController.parseOrg}) never translated, which is the
 * asymmetry that gives the game away — one string cannot be in two key spaces at once.
 *
 * <p>What the translation was reaching for is still real and is kept: {@code org_id} is a soft ref with
 * no cross-module FK (by design — this modulith is destined to split), so a well-formed UUID naming no
 * organization inserts perfectly happily and no later query ever finds it. The database will not catch
 * that. So the ids are checked against the organization directory in ONE batched call before any
 * upsert, which is the membership test the link table was standing in for.
 *
 * <p>The write side is batched to match: a flush carrying every active consumer costs two round trips
 * (one existence check, one {@code batchUpdate}) rather than 1 + N, so the cost of this endpoint tracks
 * the flush INTERVAL and not the tenant count.
 */
@Hidden
@RestController
@RequestMapping("/internal/gateway/usage-report")
class GatewayUsageReportController {

    private final GatewaySecretVerifier secretVerifier;
    private final OrgDirectory organizations;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    GatewayUsageReportController(GatewaySecretVerifier secretVerifier, OrgDirectory organizations,
            JdbcTemplate jdbc, Clock clock) {
        this.secretVerifier = secretVerifier;
        this.organizations = organizations;
        this.jdbc = jdbc;
        this.clock = clock;
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
        // Parse first, then ask the directory once for the whole batch: a per-consumer existence check
        // would turn a per-minute flush carrying every active consumer into an N+1.
        Map<UUID, Long> billable = new LinkedHashMap<>();
        report.counts().forEach((consumer, requests) -> {
            UUID orgId = parseOrg(consumer);
            if (orgId != null && requests != null && requests > 0) {
                billable.merge(orgId, requests, Long::sum);
            }
        });
        if (billable.isEmpty()) {
            return;
        }
        Set<UUID> known = organizations.existing(billable.keySet());
        Date day = Date.valueOf(LocalDate.now(clock));
        // Filter BEFORE the batch: batchUpdate has no way to skip a row once it is in the list, and the
        // gateway legitimately meters consumers that are not tenants of ours.
        List<Object[]> rows = billable.entrySet().stream()
                .filter(entry -> known.contains(entry.getKey()))
                .map(entry -> new Object[] {entry.getKey(), day, entry.getValue()})
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        // One round trip for the whole flush instead of one per consumer. Still N statements — each
        // upsert stays its own row-level operation, which is what keeps the batch safe: the merge above
        // guarantees one row per org, but even a duplicate would only re-enter the additive
        // do-update. The trap is the "obvious" next step — collapsing this into a single multi-row
        // VALUES — which Postgres refuses outright ("ON CONFLICT DO UPDATE command cannot affect row a
        // second time") the first time one flush carries the same org twice, e.g. the same UUID
        // presented in two casings. Keep the batch, not the multi-row insert.
        jdbc.batchUpdate("""
                insert into platform.api_usage_daily (org_id, day, requests)
                values (?, ?, ?)
                on conflict (org_id, day)
                do update set requests = api_usage_daily.requests + excluded.requests
                """, rows);
    }

    /**
     * The consumer id as the gateway knows it → {@code organization.id}. Null for anything that is not
     * a UUID at all; existence is a separate, batched question.
     */
    private static UUID parseOrg(String consumer) {
        if (consumer == null || consumer.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(consumer.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
