package ug.co.smsone.organization;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
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

    /**
     * Which of these are real organizations — the subset of {@code orgIds} that exist here.
     *
     * <p>For callers holding an id that arrived from OUTSIDE this modulith and must be trusted before
     * it is written into a tenant column. The columns that need this are soft refs with no cross-module
     * FK (deliberately — this modulith is destined to split), so nothing downstream rejects a
     * well-formed UUID naming no organization: it inserts perfectly happily and no later query ever
     * finds it again. The database will not catch that, so the caller must.
     *
     * <p>Batched rather than a per-id {@link #get} on purpose: the one caller is a per-minute flush
     * carrying every active consumer, and asking one id at a time would make an N+1 out of a check that
     * is a single indexed {@code in (…)} against the primary key. Unknown ids are simply absent from
     * the result — never an error, because a consumer that is not a tenant of ours is a normal thing
     * for another deployable to report.
     */
    Set<UUID> existing(Collection<UUID> orgIds);

    record OrgSummary(UUID orgId, String alias, String name, String status, Instant createdAt) {
    }
}
