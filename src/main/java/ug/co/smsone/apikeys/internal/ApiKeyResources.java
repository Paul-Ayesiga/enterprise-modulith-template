package ug.co.smsone.apikeys.internal;

import java.time.Instant;
import java.util.List;
import ug.co.smsone.shared.web.ResourceObject;

/** Resource shapes. The full secret appears ONLY in the create response's dedicated attribute. */
final class ApiKeyResources {

    static final String RESOURCE_TYPE = "api-key";

    record KeyAttributes(String name, String prefix, List<String> permissions, String platformTier,
            Instant expiresAt, Instant lastUsedAt) {
    }

    record CreatedKeyAttributes(String name, String prefix, String secret, List<String> permissions,
            String platformTier, Instant expiresAt) {
    }

    private ApiKeyResources() {
    }

    static ResourceObject toResource(ApiKey key) {
        return new ResourceObject(key.getId().toString(), RESOURCE_TYPE,
                new KeyAttributes(key.getName(), key.getPrefix(), permissions(key),
                        key.getPlatformTier(), key.getExpiresAt(), key.getLastUsedAt()));
    }

    static ResourceObject toCreated(ApiKeyService.Minted minted) {
        ApiKey key = minted.key();
        return new ResourceObject(key.getId().toString(), RESOURCE_TYPE,
                new CreatedKeyAttributes(key.getName(), key.getPrefix(), minted.secret(),
                        permissions(key), key.getPlatformTier(), key.getExpiresAt()));
    }

    private static List<String> permissions(ApiKey key) {
        return key.getPermissions() == null || key.getPermissions().isBlank()
                ? List.of()
                : List.of(key.getPermissions().split(","));
    }
}
