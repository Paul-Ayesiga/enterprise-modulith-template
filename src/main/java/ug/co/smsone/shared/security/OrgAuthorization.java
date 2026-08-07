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
 */
public interface OrgAuthorization {

    boolean hasPermission(UUID personId, UUID organizationId, String permissionCode);

    Set<String> permissions(UUID personId, UUID organizationId);
}
