package ug.co.smsone.organization;

import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Membership port for other protocol surfaces (the MCP module today) — the same operations the
 * member REST controller performs, through the same service: the escalation guard, the members-max
 * entitlement gate, the MFA-enrollment policy and the last-owner lock all ride along, whichever
 * surface calls.
 *
 * <p>A member is named by {@code person.id} and an organization by {@code organization.id}. Both are
 * ours; neither is a Keycloak identifier, and no caller of this port has to know which provider the
 * human signs in with.
 */
public interface OrgMembers {

    WindowedResult<MemberView> list(UUID orgId, CursorPageRequest page);


    /** Provision + link + record, idempotent on re-invite — see {@code MemberService.invite}. */
    MemberView invite(UUID orgId, String email, String givenName, String familyName, String roleCode);

    MemberView assignRole(UUID orgId, UUID personId, String roleCode);

    void remove(UUID orgId, UUID personId);

    record MemberView(UUID personId, String roleCode, String status, Instant since) {
    }
}
