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
 */
public interface OrgMembers {

    WindowedResult<MemberView> list(UUID orgId, CursorPageRequest page);

    /** Provision + link + record, idempotent on re-invite — see {@code MemberService.invite}. */
    MemberView invite(UUID orgId, String email, String firstName, String lastName, String roleCode);

    MemberView assignRole(UUID orgId, String subject, String roleCode);

    void remove(UUID orgId, String subject);

    record MemberView(String subject, String roleCode, String status, Instant since) {
    }
}
