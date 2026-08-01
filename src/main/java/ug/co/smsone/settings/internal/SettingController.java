package ug.co.smsone.settings.internal;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/v1/settings")
class SettingController {

    private static final String RESOURCE_TYPE = "setting";

    private final SettingService settingService;

    SettingController(SettingService settingService) {
        this.settingService = settingService;
    }

    record SettingAttributes(String key, String value, String description) {
    }

    record UpsertSettingRequest(@NotBlank String value, String description) {
    }

    @GetMapping
    @Operation(summary = "List platform settings")
    WindowedResult<ResourceObject> list(CursorPageRequest page) {
        return WindowedResult.of(settingService.list(page), page, SettingController::toResource);
    }

    @GetMapping("/{key}")
    @Operation(summary = "Get one platform setting by key")
    ResourceObject get(@PathVariable String key) {
        return toResource(settingService.require(key));
    }

    @PutMapping("/{key}")
    @Operation(summary = "Create or replace a platform setting",
            description = """
                    Upsert: a key that does not exist yet is created rather than rejected, so there is \
                    no separate create call. Settings are global, not per-organization.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject put(@PathVariable String key, @Valid @RequestBody UpsertSettingRequest request) {
        return toResource(settingService.put(key, request.value(), request.description()));
    }

    private static ResourceObject toResource(Setting setting) {
        return new ResourceObject(
                setting.getId().toString(),
                RESOURCE_TYPE,
                new SettingAttributes(setting.getKey(), setting.getValue(), setting.getDescription()));
    }
}
