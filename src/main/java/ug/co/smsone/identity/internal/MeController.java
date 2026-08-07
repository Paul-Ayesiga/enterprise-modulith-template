package ug.co.smsone.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.identity.internal.PersonAccessService.SelfView;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.ResourceObject;

/** The caller's own identity + provisioning status + active org. Allowed while INVITED. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private static final String RESOURCE_TYPE = "user";

    private final CurrentUserProvider currentUserProvider;
    private final PersonAccessService access;

    MeController(CurrentUserProvider currentUserProvider, PersonAccessService access) {
        this.currentUserProvider = currentUserProvider;
        this.access = access;
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
     * <p>There is no {@code activeOrgAlias}. The alias belongs to the organization resource, which
     * {@code activeOrgId} now names directly in this platform's own id space — and identity cannot read
     * it without depending on {@code organization}, which depends on identity. One call to
     * {@code GET /api/v1/orgs/{activeOrgId}} is the honest way to get it.
     */
    record MeAttributes(String personId, String email, Set<String> roles, String activeOrgId,
            String provisioningStatus) {
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
                user.roles(),
                user.organizationId() == null ? null : user.organizationId().toString(),
                self.provisioningStatus()));
    }
}
