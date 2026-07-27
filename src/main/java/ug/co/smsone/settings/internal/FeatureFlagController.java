package ug.co.smsone.settings.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    record FeatureFlagAttributes(String key, boolean enabled, String description) {
    }

    record ToggleFeatureFlagRequest(@NotNull Boolean enabled, String description) {
    }

    @GetMapping
    WindowedResult<ResourceObject> list(CursorPageRequest page) {
        return WindowedResult.of(featureFlagService.list(page), page, FeatureFlagController::toResource);
    }

    @GetMapping("/{key}")
    ResourceObject get(@PathVariable String key) {
        return toResource(featureFlagService.require(key));
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    ResourceObject set(@PathVariable String key, @Valid @RequestBody ToggleFeatureFlagRequest request) {
        return toResource(featureFlagService.set(key, request.enabled(), request.description()));
    }

    private static ResourceObject toResource(FeatureFlag flag) {
        return new ResourceObject(
                flag.getId().toString(),
                RESOURCE_TYPE,
                new FeatureFlagAttributes(flag.getKey(), flag.isEnabled(), flag.getDescription()));
    }
}
