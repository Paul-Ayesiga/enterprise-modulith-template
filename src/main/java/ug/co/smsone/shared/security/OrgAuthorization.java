package ug.co.smsone.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * Port for org-scoped authorization, implemented by the {@code organization} module. Resolves a PERSON's
 * effective permissions within an organization (membership → role → permissions). When no implementation
 * is present the caller holds nothing and {@link ApiPermissionEvaluator} denies — so {@code shared} never
 * compile-depends on {@code organization}.
 *
 * <p>Both arguments are ids of ours: {@code membership.person_id} and {@code organization.id}. Neither a
 * token subject nor a Keycloak org id reaches this port any more — they are translated at the edge, and a
 * module answering a question about its own tables should never have had to know which provider the
 * caller signed in with.
 *
 * <p>{@link #permissions} is what the edge calls, once per request, to fill
 * {@link CurrentUser#permissions()}. {@link #hasPermission} stays for callers with no request context —
 * a scheduled job authorizing the person who registered the schedule — where there is no
 * {@code CurrentUser} to read and the question is about somebody who is not calling.
 *
 * <h2>NEVER stale-while-unreachable — the one row of ADR 0011 §2.1 with no ceiling and no grace</h2>
 *
 * <p>{@code PersonLookup}, {@code OrgLookup} and {@code PlanCatalog} may serve a bounded last-known
 * answer when their authority is unreachable. <b>This port may not, in any phase</b> (AGENTS §5.5): a
 * revoked membership must deny the VERY NEXT request, and a last-known "yes" is precisely the answer a
 * revocation exists to kill. An implementation that cannot ask its tables answers by NOT answering —
 * the exception propagates, the edge fails the request, and the deny comes from absence-of-fresh-answer
 * rather than from a remembered grant. The bounded {@code org-permissions} cache is not an exception to
 * this: it is evicted on every membership/role/org-status change, so it only ever bridges reads BETWEEN
 * changes — it is never a fallback FOR an unreachable authority, and no holdover may be added behind
 * it. {@code PlatformUnreachableIntegrationTest} is written to go red if one is.
 *
 * <p>In Phase 7 this costs nothing: {@code membership}, {@code org_role} and {@code role_permission}
 * are tenant-tier, live in the database serving the request, and the read does not cross the split —
 * a platform outage does not touch it. The rule is recorded here because Phase 8 is where it becomes
 * live (the port goes over the wire), and ADR 0011 §9 says this row does not get renegotiated when the
 * transport changes: unreachable authority = deny, 503-or-fail, never a cached yes.
 */
public interface OrgAuthorization {

    boolean hasPermission(UUID personId, UUID organizationId, String permissionCode);

    Set<String> permissions(UUID personId, UUID organizationId);
}
