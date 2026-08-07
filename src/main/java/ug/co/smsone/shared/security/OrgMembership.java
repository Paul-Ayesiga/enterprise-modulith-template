package ug.co.smsone.shared.security;

import java.util.UUID;

/**
 * Port for "is this person a live member of this organization?", implemented by the module that owns
 * {@code membership}.
 *
 * <p>Lives here, beside {@link PersonLookup} and {@link OrgLookup}, rather than on the organization
 * module's own {@code OrgMembers} port, for a structural reason: {@code organization} already depends
 * on {@code access} (its member flow consults the org's security policy), so an {@code access →
 * organization} edge would close a cycle and {@code ModularityTests} rejects it. Inverting it — the
 * consumer declares the interface, the owner implements it — is the same shape the two lookups above
 * use, and it is what keeps the dependency arrows pointing one way when this modulith is split into
 * services.
 *
 * <p>Needed wherever an endpoint takes BOTH an {@code orgId} it authorizes against AND a
 * {@code personId} it does not. A permission check answers "may you act in THIS org"; it never sees
 * the person you named. {@code access.SecurityPolicyService.setDeviceTrust} was exactly that shape and
 * let an {@code org:update} holder in any org act on any person's device.
 *
 * <p>Absent implementation means no membership, which denies — the same fail-closed posture as the
 * other ports here.
 */
public interface OrgMembership {

    /**
     * Live membership only: a soft-deleted membership is not one, so revoking a member revokes this
     * answer on the very next call.
     */
    boolean isMember(UUID orgId, UUID personId);
}
