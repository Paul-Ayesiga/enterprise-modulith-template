package ug.co.smsone.integration.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The PLATFORM-DEFAULT integrations — used by any org that has no override of its own. Managed by
 * platform-admin; the same encryption and masking as the org surface.
 */
@RestController
@RequestMapping("/api/v1/admin/integrations")
class AdminIntegrationController {

    private final IntegrationService service;

    AdminIntegrationController(IntegrationService service) {
        this.service = service;
    }

    @PutMapping
    @Operation(summary = "Configure a platform-default integration")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject upsert(@RequestBody OrgIntegrationController.UpsertRequest request) {
        return IntegrationResources.toResource(service.upsert(null, request.kind(), request.provider(),
                request.enabled() == null || request.enabled(), request.settings()));
    }

    @GetMapping
    @Operation(summary = "List platform-default integrations")
    @PreAuthorize("hasRole('platform-support')")
    List<ResourceObject> list() {
        return service.list(null).stream().map(IntegrationResources::toResource).toList();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Test a platform-default integration")
    @PreAuthorize("hasRole('platform-admin')")
    Map<String, String> test(@PathVariable UUID id) {
        return Map.of("result", service.testConnection(null, id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a platform-default integration")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(null, id);
    }
}
