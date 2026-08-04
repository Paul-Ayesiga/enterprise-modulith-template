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
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

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
        featureFlagService.setOrgOverride(key, orgId, request.enabled());
        return effective(key, orgId);
    }

    @DeleteMapping("/{key}/orgs/{orgId}")
    @Operation(summary = "Clear a per-org override",
            description = "The organization falls back to the global value / percentage rollout.")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clearOrgOverride(@PathVariable String key, @PathVariable UUID orgId) {
        featureFlagService.clearOrgOverride(key, orgId);
    }

    @GetMapping("/{key}/orgs/{orgId}")
    @Operation(summary = "Evaluate a flag for one organization",
            description = """
                    The effective answer this org gets: org override, else percentage bucket, else the \
                    global value. Unknown flags evaluate to false, never an error.""")
    @PreAuthorize("hasRole('platform-admin') or hasPermission(#orgId, 'organization', 'org:read')")
    ResourceObject effective(@PathVariable String key, @PathVariable UUID orgId) {
        return new ResourceObject(key + ":" + orgId, "feature-flag-evaluation",
                new EffectiveFlagAttributes(key, featureFlagService.isEnabledFor(key, orgId)));
    }

    private static ResourceObject toResource(FeatureFlag flag) {
        return new ResourceObject(
                flag.getId().toString(),
                RESOURCE_TYPE,
                new FeatureFlagAttributes(flag.getKey(), flag.isEnabled(), flag.getDescription(),
                        flag.getPercentage()));
    }
}
