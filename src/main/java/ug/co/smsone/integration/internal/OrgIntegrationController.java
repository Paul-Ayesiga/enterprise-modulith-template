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
 * An organization's provider overrides (`org:update`). Setting a provider here makes the hub
 * resolve it over the platform default for that capability. Secret values (apiKey, authToken, …)
 * are encrypted at rest and masked on read.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/integrations")
class OrgIntegrationController {

    private final IntegrationService service;

    OrgIntegrationController(IntegrationService service) {
        this.service = service;
    }

    record UpsertRequest(String kind, String provider, Boolean enabled, Map<String, String> settings) {
    }

    @PutMapping
    @Operation(summary = "Configure an integration for the organization",
            description = "Upsert by (kind). Known secret keys (apiKey/apiSecret/authToken/password/"
                    + "secretKey/accessKey) are encrypted at rest and masked on read.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    ResourceObject upsert(@PathVariable UUID orgId, @RequestBody UpsertRequest request) {
        return IntegrationResources.toResource(service.upsert(orgId, request.kind(), request.provider(),
                request.enabled() == null || request.enabled(), request.settings()));
    }

    @GetMapping
    @Operation(summary = "List the organization's integrations (secrets masked)")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:read')")
    List<ResourceObject> list(@PathVariable UUID orgId) {
        return service.list(orgId).stream().map(IntegrationResources::toResource).toList();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "Test an integration's configuration completeness")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    Map<String, String> test(@PathVariable UUID orgId, @PathVariable UUID id) {
        return Map.of("result", service.testConnection(orgId, id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove an integration")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'org:update')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        service.delete(orgId, id);
    }
}
