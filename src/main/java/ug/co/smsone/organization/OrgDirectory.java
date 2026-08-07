package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;

/**
 * Read/rename port over an organization's profile for other protocol surfaces (the MCP module
 * today). Delegates to the same service the REST controller uses — one rename path, one audit
 * trail, whichever surface asked. {@code orgId} is {@code organization.id}.
 */
public interface OrgDirectory {

    OrgSummary get(UUID orgId);

    /** The {@code org:update} write REST exposes: rename. Status changes stay platform-side. */
    OrgSummary rename(UUID orgId, String name);

    record OrgSummary(UUID orgId, String alias, String name, String status, Instant createdAt) {
    }
}
