package ug.co.smsone.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.identity.internal.PersonAccessService.PersonSummary;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * The operator surface on a person: the platform-wide listing, one person read whole, and the one
 * write — correcting a name that is wrong.
 *
 * <p>The path and the {@code user} resource type are unchanged on purpose. "User" is the API's word for
 * a human with an account — the profile module hangs {@code /admin/users/{id}/profile} and
 * {@code /admin/users/{id}/devices} off the same noun — and renaming a wire contract is a change that
 * has to land with the OpenAPI export and the API guide in one slice. What DID change is the payload:
 * the id is now {@code person.id}, and {@code subject} is gone, because a Keycloak subject was never
 * something a support operator could do anything with.
 *
 * <p><b>Two floors, not one.</b> This controller was read-only, and {@code platform-support} was the
 * floor for all of it. Reading still is support's job; editing another human's name is not, so the
 * PATCH is {@code platform-admin}. The role hierarchy makes an admin satisfy the read checks too, so
 * the split costs an operator nothing and means a support login cannot rewrite anyone's identity.
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

    private static ResourceObject toResource(PersonSummary person) {
        return new ResourceObject(person.personId().toString(), RESOURCE_TYPE,
                new PersonAttributes(person.personId().toString(), PersonNameAttributes.of(person.name()),
                        person.email(), person.status().name()));
    }
}
