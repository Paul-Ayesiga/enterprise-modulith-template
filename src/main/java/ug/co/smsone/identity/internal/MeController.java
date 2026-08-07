package ug.co.smsone.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.identity.internal.PersonAccessService.SelfView;
import ug.co.smsone.shared.error.ForbiddenException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The caller's own identity + provisioning status + active org, and the one place they can read and
 * correct their own name. Reading is allowed while INVITED.
 *
 * <p><b>Why the name lives here and not on {@code /me/profile}.</b> The profile module's
 * {@code displayName}, {@code timezone} and {@code locale} are UI preferences — cosmetics a person may
 * set to anything. A name is not a preference: it is the identity {@code person} exists to hold, it is
 * what an operator corrects when it is wrong, and it is modelled on SCIM/OIDC so a provider can supply
 * it. Two things with different semantics and different owners, which is exactly why
 * {@code person_profile.display_name} and {@code person.preferred_name} are separate columns (V28).
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private static final String RESOURCE_TYPE = "user";

    private final CurrentUserProvider currentUserProvider;
    private final PersonAccessService access;
    private final PersonNameService names;

    MeController(CurrentUserProvider currentUserProvider, PersonAccessService access, PersonNameService names) {
        this.currentUserProvider = currentUserProvider;
        this.access = access;
        this.names = names;
    }

    /**
     * {@code personId} is null exactly while {@code provisioningStatus} is {@code UNPROVISIONED} — a
     * typed field rather than something a client has to infer from the resource id, because those are
     * the two states onboarding branches on.
     *
     * <p>{@code email} comes from the person's own {@code person_contact}, not from the token: an
     * address this platform has on file is the one a client should show, and the token's may be an
     * address nothing here can reach.
     *
     * <p>{@code name} is the SCIM/OIDC name block, always present as an object and empty ({@code {}})
     * for anyone we were never told a name for. It was write-only until this slice: the parts went in
     * at invite time and no endpoint ever gave them back, so a person could not see — let alone
     * correct — what this platform believed they were called.
     *
     * <p>There is no {@code activeOrgAlias}. The alias belongs to the organization resource, which
     * {@code activeOrgId} now names directly in this platform's own id space — and identity cannot read
     * it without depending on {@code organization}, which depends on identity. One call to
     * {@code GET /api/v1/orgs/{activeOrgId}} is the honest way to get it.
     */
    record MeAttributes(String personId, String email, PersonNameAttributes name, Set<String> roles,
            String activeOrgId, String provisioningStatus) {
    }

    @GetMapping
    @Operation(summary = "Get the current user's identity and access",
            description = """
                    The one path reachable before provisioning completes, so a freshly invited account \
                    can render onboarding — every other `/api/**` path answers \
                    `ACCOUNT_NOT_PROVISIONED` until then. A disabled account is refused here too.""")
    ResourceObject me() {
        CurrentUser user = currentUserProvider.requireCurrentUser();
        SelfView self = access.selfView(user.personId());
        // The resource id is the person id once there is one. Before provisioning there is no person to
        // name, so it falls back to the caller's own token subject — a value they already hold and
        // nobody else is shown, which is the only identifier that exists at that moment.
        String id = self.personId() == null
                ? CallerSubject.of().orElse(null)
                : self.personId().toString();
        return new ResourceObject(id, RESOURCE_TYPE, new MeAttributes(
                self.personId() == null ? null : self.personId().toString(),
                self.email(),
                PersonNameAttributes.of(self.name()),
                user.roles(),
                user.organizationId() == null ? null : user.organizationId().toString(),
                self.provisioningStatus()));
    }

    /**
     * PATCH and not PUT, and the body is a map rather than a record, for one reason: with seven
     * mostly-null components a whole-document write erases whatever the client did not know to send.
     * <b>An omitted key is unchanged; a key sent as null (or as "") is cleared.</b> See
     * {@link NamePatch} for why a record of Strings cannot express that.
     */
    @PatchMapping("/name")
    @Operation(summary = "Correct your own name",
            description = """
                    Partial update of the SCIM/OIDC name block. Send only the components you are \
                    changing: an absent key is left alone, `null` or `""` clears it, and everything is \
                    trimmed. Keys are `formattedName`, `givenName`, `familyName`, `middleName`, \
                    `honorificPrefix`, `honorificSuffix`, `preferredName`; anything else is a 422, so \
                    a typo cannot look like a save that worked. `formattedName` is the display value \
                    and is NEVER derived from the parts — change it explicitly or it stays as it was. \
                    A person with no name at all is a valid state.""")
    ResourceObject patchName(@RequestBody Map<String, String> body) {
        UUID personId = requirePerson(currentUserProvider.requireCurrentUser());
        return PersonNameAttributes.toResource(personId, names.apply(personId, NamePatch.of(body)));
    }

    /**
     * Reading {@code /me} without a person is normal — that IS onboarding — but writing a name needs
     * somebody to be named. Two callers land here and neither can be served: a machine key, which will
     * never have a person (an administrator minted a credential, and a credential has no name), and an
     * unprovisioned human, whom the provisioning gate turns away before this handler runs in any
     * deployment that has it on.
     */
    private static UUID requirePerson(CurrentUser user) {
        UUID personId = user.personId();
        if (personId == null) {
            throw new ForbiddenException("This endpoint edits a person's name; no person is calling.");
        }
        return personId;
    }
}
