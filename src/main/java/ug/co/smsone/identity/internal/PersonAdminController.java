package ug.co.smsone.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.identity.internal.PersonAccessService.PersonSummary;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The operator surface on a person: the platform-wide listing, one person read whole, and the writes —
 * correcting a name that is wrong, and suspending or restoring access.
 *
 * <p>The path and the {@code user} resource type are unchanged on purpose. "User" is the API's word for
 * a human with an account — the profile module hangs {@code /admin/users/{id}/profile} and
 * {@code /admin/users/{id}/devices} off the same noun — and renaming a wire contract is a change that
 * has to land with the OpenAPI export and the API guide in one slice. What DID change is the payload:
 * the id is now {@code person.id}, and {@code subject} is gone, because a Keycloak subject was never
 * something a support operator could do anything with.
 *
 * <p><b>Two floors, not one.</b> This controller was read-only, and {@code platform-support} was the
 * floor for all of it. Reading still is support's job; changing another human's identity or their access
 * is not, so every write here is {@code platform-admin}. The role hierarchy makes an admin satisfy the
 * read checks too, so the split costs an operator nothing and means a support login can neither rewrite
 * anyone's identity nor take their access away. The line is drawn at "does this alter another human's
 * account", not at "is this a write" — which is why suspension sits on the same floor as a name
 * correction rather than being escalated to {@code platform-superadmin}: it is reversible from this very
 * surface, it is audited with both states, and the one irreversible-from-here case (the last
 * super-admin) is refused outright instead of being handed to a higher tier.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
class PersonAdminController {

    private static final String RESOURCE_TYPE = "user";

    private final PersonAccessService access;
    private final PersonNameService names;

    PersonAdminController(PersonAccessService access, PersonNameService names) {
        this.access = access;
        this.names = names;
    }

    /**
     * The name arrives as the whole SCIM/OIDC block rather than as a lone {@code formattedName}, on the
     * listing as well as the single read. An operator who is about to fix a typo needs to see the
     * component the typo is IN — being shown only the display value, and then asked to PATCH
     * {@code givenName} blind, is how a correction lands on the wrong field.
     *
     * <p>Null components are omitted, so this is {@code {}} for a person no name was ever supplied for.
     * A client renders the e-mail then; what it must not do is build a display string out of the parts,
     * whose order is cultural. See {@link PersonName}.
     */
    record PersonAttributes(String personId, PersonNameAttributes name, String email, String status) {
    }

    @GetMapping
    @Operation(summary = "List provisioned users across the platform")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> list(CursorPageRequest page) {
        return WindowedResult.of(access.list(page), page, PersonAdminController::toResource);
    }

    @GetMapping("/{personId}")
    @Operation(summary = "Get one user's identity and name",
            description = """
                    The record an operator opens before correcting it: the same attributes as a row of \
                    the listing, for one person. A soft-deleted account answers 404 — to an operator an \
                    erased account is gone.""")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject get(@PathVariable UUID personId) {
        return toResource(access.require(personId));
    }

    /**
     * PATCH and not PUT, and a map body rather than a record, for the reason spelled out in
     * {@link NamePatch}: with seven mostly-null components a whole-document write erases whatever the
     * client did not know to send. <b>An omitted key is unchanged; a key sent as null (or as "") is
     * cleared.</b>
     *
     * <p>Identical in shape and semantics to {@code PATCH /api/v1/me/name} — same parser, same service,
     * same response — because they are one operation differing only in who may ask. The audit row is
     * what tells them apart afterwards: {@code actor_person_id} equals the target for a self-edit and
     * names the operator here.
     */
    @PatchMapping("/{personId}/name")
    @Operation(summary = "Correct a user's name",
            description = """
                    Partial update of another person's SCIM/OIDC name block — an operator fixing a typo. \
                    Send only the components you are changing: an absent key is left alone, `null` or \
                    `""` clears it, and everything is trimmed. Keys are `formattedName`, `givenName`, \
                    `familyName`, `middleName`, `honorificPrefix`, `honorificSuffix`, `preferredName`; \
                    anything else is a 422. `formattedName` is the display value and is NEVER derived \
                    from the parts. Every change is written to the audit trail naming the operator, the \
                    person changed, and the before and after of exactly the fields sent.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject patchName(@PathVariable UUID personId, @RequestBody Map<String, String> body) {
        return PersonNameAttributes.toResource(personId, names.apply(personId, NamePatch.of(body)));
    }

    // Suspension is a POST to a named sub-path rather than a PATCH of `status`, matching
    // OrganizationController's suspend/reactivate pair. A writable status field would put every
    // transition behind one handler and one authorization annotation, and would invite a client to send
    // INVITED — a state only the invitation flow may produce.

    @PostMapping("/{personId}/disable")
    @Operation(summary = "Suspend a user's access",
            description = """
                    Revokes access immediately: the very next API request this person makes is refused \
                    with `ACCOUNT_DISABLED`, and any impersonation session they hold or are worn through \
                    dies with it. Nothing else is changed — memberships, roles and name survive, and \
                    `POST .../enable` puts them back exactly where they were. Idempotent: suspending an \
                    already-suspended account returns it unchanged and files no audit row. Refused with \
                    409 when the target is the caller themselves, or the last platform super-admin.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject disable(@PathVariable UUID personId) {
        return toResource(access.disable(personId));
    }

    @PostMapping("/{personId}/enable")
    @Operation(summary = "Restore a suspended user's access",
            description = """
                    Returns the person to the status they held before suspension — `ACTIVE` for someone \
                    who had signed in, `INVITED` for someone who never did, so restoring never marks an \
                    invitation as accepted by a person who has not turned up. Idempotent, and files no \
                    audit row for an account that already has access. Refused with 409 when the person's \
                    identity-provider account no longer exists: restoring them would be undone by the \
                    nightly identity reconciliation and they still could not sign in.""")
    @PreAuthorize("hasRole('platform-admin')")
    ResourceObject enable(@PathVariable UUID personId) {
        return toResource(access.enable(personId));
    }

    private static ResourceObject toResource(PersonSummary person) {
        return new ResourceObject(person.personId().toString(), RESOURCE_TYPE,
                new PersonAttributes(person.personId().toString(), PersonNameAttributes.of(person.name()),
                        person.email(), person.status().name()));
    }
}
