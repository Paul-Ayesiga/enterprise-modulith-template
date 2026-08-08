package ug.co.smsone.apikeys.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** A machine credential. Revocation is soft-delete: the row stays as the audit trail. */
@Entity
@Table(name = "api_key", schema = "platform")
@SQLDelete(sql = "update platform.api_key set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class ApiKey extends SoftDeletableEntity {

    @Column(name = "org_id", updatable = false)
    private UUID orgId; // null = platform key

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20, updatable = false)
    private String prefix;

    @Column(name = "secret_hash", nullable = false, length = 64, updatable = false)
    private String secretHash;

    @Column(columnDefinition = "text")
    private String permissions; // comma-joined org codes (org keys)

    @Column(name = "platform_tier", length = 30)
    private String platformTier; // platform keys

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ApiKey() {
        // JPA
    }

    static ApiKey orgKey(UUID orgId, String name, String prefix, String secretHash,
            String permissions, Instant expiresAt) {
        ApiKey key = new ApiKey();
        key.orgId = orgId;
        key.name = name;
        key.prefix = prefix;
        key.secretHash = secretHash;
        key.permissions = permissions;
        key.expiresAt = expiresAt;
        return key;
    }

    static ApiKey platformKey(String name, String prefix, String secretHash, String tier, Instant expiresAt) {
        ApiKey key = new ApiKey();
        key.name = name;
        key.prefix = prefix;
        key.secretHash = secretHash;
        key.platformTier = tier;
        key.expiresAt = expiresAt;
        return key;
    }

    boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    UUID getOrgId() {
        return orgId;
    }

    String getName() {
        return name;
    }

    String getPrefix() {
        return prefix;
    }

    String getSecretHash() {
        return secretHash;
    }

    String getPermissions() {
        return permissions;
    }

    String getPlatformTier() {
        return platformTier;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
