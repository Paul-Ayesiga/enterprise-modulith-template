package ug.co.smsone.apikeys.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Platform API keys — support-tier machine credentials minted by {@code platform-admin}. They read
 * platform surfaces; they never satisfy a higher tier and never carry org permissions.
 */
@RestController
@RequestMapping("/api/v1/admin/api-keys")
class AdminApiKeyController {

    private final ApiKeyService service;

    AdminApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    record CreateRequest(String name, Instant expiresAt) {
    }

    @PostMapping
    @Operation(summary = "Mint a platform API key (support tier)",
            description = "The `secret` is returned in full exactly ONCE. Reads platform surfaces only.")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject create(@RequestBody CreateRequest request) {
        return ApiKeyResources.toCreated(service.createPlatformKey(request.name(), request.expiresAt()));
    }

    @GetMapping
    @Operation(summary = "List platform API keys")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> list(CursorPageRequest page) {
        return WindowedResult.of(service.listPlatform(page), page, ApiKeyResources::toResource);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a platform API key")
    @PreAuthorize("hasRole('platform-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id) {
        service.revokePlatformKey(id);
    }
}
