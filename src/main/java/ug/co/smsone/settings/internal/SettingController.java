package ug.co.smsone.settings.internal;

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
    WindowedResult<ResourceObject> list(CursorPageRequest page) {
        return WindowedResult.of(settingService.list(page), page, SettingController::toResource);
    }

    @GetMapping("/{key}")
    ResourceObject get(@PathVariable String key) {
        return toResource(settingService.require(key));
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
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
