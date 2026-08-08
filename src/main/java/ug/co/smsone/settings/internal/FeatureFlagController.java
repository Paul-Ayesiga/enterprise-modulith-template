package ug.co.smsone.settings.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The flag catalog and its per-org overrides — two tiers behind one {@code @RequestMapping}, which is
 * why the three {@code /orgs/{orgId}} routes below pin a tenant and the three above them do not.
 *
 * <p>{@code feature_flag} is platform-tier: one catalog, shared by every tenant, and a flag is not a
 * thing an organization owns. {@code feature_flag_org_override} is TENANT-tier — it is that
 * organization's own exception and travels with it on extraction (ADR 0010 §2). So the override routes
 * touch both tiers in one call, and they reach them the way ADR 0010 §5.15 requires: the tenant is
 * entered explicitly with {@link TenantContext#runAs}, and the platform table is reached from inside
 * that axis because {@code FeatureFlag} names its schema ({@code @Table(schema = "platform")}). One
 * pin, not two spans, precisely because only one of the two tables is unqualified.
 *
 * <p><strong>Why the controller pins and not the service.</strong> These are platform-operator routes
 * — {@code /api/v1/feature-flags/…} names no organization to {@code CurrentUserFilter}, so the request
 * arrives on the PLATFORM axis and stays there. The pin has to happen OUTSIDE the transaction, and
 * every {@code FeatureFlagService} method is {@code @Transactional}: pinning inside one would be a
 * silent no-op on a connection already bound to the platform search_path, which is why
 * {@code TenantContext.set} throws there rather than allowing it.
 */
@RestController
@RequestMapping("/api/v1/feature-flags")
class FeatureFlagController {

    private static final String RESOURCE_TYPE = "feature-flag";

    private final FeatureFlagService featureFlagService;

    FeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    record FeatureFlagAttributes(String key, boolean enabled, String description, Integer percentage) {
    }

    record ToggleFeatureFlagRequest(@NotNull Boolean enabled, String description,
            @Min(0) @Max(100) Integer percentage) {
    }

    record OrgOverrideRequest(@NotNull Boolean enabled) {
    }

    record EffectiveFlagAttributes(String key, boolean enabled) {
    }

    @GetMapping
    @Operation(summary = "List feature flags")
    WindowedResult<ResourceObject> list(CursorPageRequest page) {
        return WindowedResult.of(featureFlagService.list(page), page, FeatureFlagController::toResource);
    }

    @GetMapping("/{key}")
    @Operation(summary = "Get one feature flag by key")
    ResourceObject get(@PathVariable String key) {
        return toResource(featureFlagService.require(key));
    }

    @PutMapping("/{key}")
    @Operation(summary = "Create or toggle a feature flag",
            description = """
                    Upsert: a key that does not exist yet is created rather than rejected, so there is \
                    no separate create call. `percentage` (0-100, optional) turns an enabled flag into a \
                    gradual rollout bucketed per organization; per-org overrides beat both.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject set(@PathVariable String key, @Valid @RequestBody ToggleFeatureFlagRequest request) {
        return toResource(featureFlagService.set(key, request.enabled(), request.description(),
                request.percentage()));
    }

    @PutMapping("/{key}/orgs/{orgId}")
    @Operation(summary = "Set a per-org override for a flag",
            description = """
                    Pins the flag hard ON or OFF for one organization, beating the global value and any \
                    percentage rollout. The flag must already exist. Platform-admin only.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject setOrgOverride(@PathVariable String key, @PathVariable UUID orgId,
            @Valid @RequestBody OrgOverrideRequest request) {
        // Inside the method body, not around it: @PreAuthorize runs on the axis the request arrived
        // with, and the permission check on the sibling GET reads the CALLER's tenant-tier rows.
        TenantContext.runAs(orgId, () -> featureFlagService.setOrgOverride(key, orgId, request.enabled()));
        return effective(key, orgId);
    }

    @DeleteMapping("/{key}/orgs/{orgId}")
    @Operation(summary = "Clear a per-org override",
            description = "The organization falls back to the global value / percentage rollout.")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clearOrgOverride(@PathVariable String key, @PathVariable UUID orgId) {
        TenantContext.runAs(orgId, () -> featureFlagService.clearOrgOverride(key, orgId));
    }

    @GetMapping("/{key}/orgs/{orgId}")
    @Operation(summary = "Evaluate a flag for one organization",
            description = """
                    The effective answer this org gets: org override, else percentage bucket, else the \
                    global value. Unknown flags evaluate to false, never an error.""")
    @PreAuthorize("hasRole('platform-admin') or hasPermission(#orgId, 'organization', 'org:read')")
    ResourceObject effective(@PathVariable String key, @PathVariable UUID orgId) {
        // An operator arrives on PLATFORM and a member of this org arrives already pinned to it, so
        // this is a narrowing for one caller and a no-op for the other — and it reads the override
        // either way. runAs restores, so the call from setOrgOverride above nests correctly.
        boolean enabled = TenantContext.callAs(orgId, () -> featureFlagService.isEnabledFor(key, orgId));
        return new ResourceObject(key + ":" + orgId, "feature-flag-evaluation",
                new EffectiveFlagAttributes(key, enabled));
    }

    private static ResourceObject toResource(FeatureFlag flag) {
        return new ResourceObject(
                flag.getId().toString(),
                RESOURCE_TYPE,
                new FeatureFlagAttributes(flag.getKey(), flag.isEnabled(), flag.getDescription(),
                        flag.getPercentage()));
    }
}
