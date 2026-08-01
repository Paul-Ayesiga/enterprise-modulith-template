package ug.co.smsone.scheduler.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.retention.RetentionScope;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * Per-org retention overrides: the platform keeps (or drops) one org's org-scoped log rows on a
 * different schedule than the platform default — a data-retention contract, typically. Consulted by
 * the retention jobs. Reads are platform-support, writes platform-admin (a retention commitment),
 * and every change is audited. Scope on the wire is kebab-case: `webhook-delivery`, `exchange-job`.
 */
@RestController
@RequestMapping("/api/v1/admin/orgs/{orgId}/retention")
class AdminRetentionController {

    private static final Map<String, String> SCOPES = Map.of(
            "webhook-delivery", RetentionScope.WEBHOOK_DELIVERY,
            "exchange-job", RetentionScope.EXCHANGE_JOB);

    private final RetentionOverridesImpl overrides;

    AdminRetentionController(RetentionOverridesImpl overrides) {
        this.overrides = overrides;
    }

    record RetentionRequest(Integer days) {
    }

    record RetentionAttributes(String scope, int retentionDays) {
    }

    @GetMapping
    @Operation(summary = "List an org's retention overrides",
            description = "Scopes not listed use the platform default retention for that log.")
    @PreAuthorize("hasRole('platform-support')")
    List<ResourceObject> list(@PathVariable UUID orgId) {
        return overrides.list(orgId).stream().map(AdminRetentionController::toResource).toList();
    }

    @PutMapping("/{scope}")
    @Operation(summary = "Set an org's retention for a scope",
            description = "Overrides the platform default with `days` (positive) for `scope` "
                    + "(`webhook-delivery` | `exchange-job`). May keep rows longer OR shorter than "
                    + "the default. Applies on the next retention run.")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject set(@PathVariable UUID orgId, @PathVariable String scope,
            @RequestBody RetentionRequest request) {
        int days = request.days() == null ? 0 : request.days();
        if (days <= 0) {
            throw new ValidationException("days must be a positive number.");
        }
        return toResource(overrides.set(orgId, resolve(scope), days));
    }

    @DeleteMapping("/{scope}")
    @Operation(summary = "Clear an org's retention override (fall back to the platform default)")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clear(@PathVariable UUID orgId, @PathVariable String scope) {
        overrides.clear(orgId, resolve(scope));
    }

    private static String resolve(String scope) {
        String resolved = SCOPES.get(scope == null ? "" : scope.trim().toLowerCase());
        if (resolved == null) {
            throw new ValidationException("Unknown retention scope '" + scope + "'. Known: " + SCOPES.keySet());
        }
        return resolved;
    }

    private static ResourceObject toResource(OrgRetentionOverride override) {
        return new ResourceObject(override.getOrgId() + ":" + override.getScope(), "retention-override",
                new RetentionAttributes(override.getScope(), override.getRetentionDays()));
    }
}
