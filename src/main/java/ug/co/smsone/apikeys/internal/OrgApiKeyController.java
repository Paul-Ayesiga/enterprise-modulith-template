package ug.co.smsone.apikeys.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.Set;
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
 * An organization's machine keys. Managing them takes {@code apikey:manage}; the key itself then
 * carries a SUBSET of the minter's permissions (capped at mint — see {@link ApiKeyService}). The
 * full secret is in the create response once, then unreachable.
 */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/api-keys")
class OrgApiKeyController {

    private final ApiKeyService service;

    OrgApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    record CreateRequest(String name, Set<String> permissions, Instant expiresAt) {
    }

    @PostMapping
    @Operation(summary = "Mint an organization API key",
            description = """
                    The `secret` is returned in full exactly ONCE, here. `permissions` must be a \
                    subset of what you hold — a key can never out-rank its creator. Send it as \
                    `X-Api-Key: <prefix>.<secret>`.""")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'apikey:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceObject create(@PathVariable UUID orgId, @RequestBody CreateRequest request) {
        return ApiKeyResources.toCreated(
                service.createOrgKey(orgId, request.name(), request.permissions(), request.expiresAt()));
    }

    @GetMapping
    @Operation(summary = "List the organization's API keys (secrets never shown)")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'apikey:manage')")
    WindowedResult<ResourceObject> list(@PathVariable UUID orgId, CursorPageRequest page) {
        return WindowedResult.of(service.listOrg(orgId, page), page, ApiKeyResources::toResource);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke an API key", description = "Immediate — the next call with it 401s.")
    @PreAuthorize("hasPermission(#orgId, 'organization', 'apikey:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID orgId, @PathVariable UUID id) {
        service.revokeOrgKey(orgId, id);
    }
}
