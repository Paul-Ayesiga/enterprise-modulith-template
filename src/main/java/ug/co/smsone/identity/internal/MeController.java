package ug.co.smsone.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.ResourceObject;

/** The authenticated user's own profile + provisioning status + active org. Allowed while INVITED. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private static final String RESOURCE_TYPE = "user";

    private final CurrentUserProvider currentUserProvider;
    private final UserAccessService access;

    MeController(CurrentUserProvider currentUserProvider, UserAccessService access) {
        this.currentUserProvider = currentUserProvider;
        this.access = access;
    }

    record MeAttributes(String email, Set<String> roles, String activeOrgAlias, String activeOrgId,
            String provisioningStatus) {
    }

    @GetMapping
    @Operation(summary = "Get the current user's profile and access",
            description = """
                    The one path reachable before provisioning completes, so a freshly invited account \
                    can render onboarding — every other `/api/**` path answers \
                    `ACCOUNT_NOT_PROVISIONED` until then. A disabled account is refused here too.""")
    ResourceObject me() {
        CurrentUser user = currentUserProvider.requireCurrentUser();
        return new ResourceObject(user.subject(), RESOURCE_TYPE, new MeAttributes(
                user.email(),
                user.roles(),
                user.activeOrgAlias(),
                user.activeOrgId() == null ? null : user.activeOrgId().toString(),
                access.provisioningStatusOf(user.subject())));
    }
}
