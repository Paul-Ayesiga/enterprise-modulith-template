package ug.co.smsone.identity.internal;

import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.error.UnauthorizedException;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;
import ug.co.smsone.shared.web.ResourceObject;

/** The authenticated user's own profile + provisioning status + active org. Allowed while INVITED. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final CurrentUserProvider currentUserProvider;
    private final UserRepository users;

    MeController(CurrentUserProvider currentUserProvider, UserRepository users) {
        this.currentUserProvider = currentUserProvider;
        this.users = users;
    }

    record MeAttributes(String email, Set<String> roles, String activeOrgAlias, String activeOrgId,
            String provisioningStatus) {
    }

    @GetMapping
    ResourceObject me() {
        CurrentUser user = currentUserProvider.currentUser()
                .orElseThrow(() -> new UnauthorizedException("Authentication required."));
        String status = users.findBySubject(user.subject())
                .map(entity -> entity.getStatus().name())
                .orElse("UNPROVISIONED");
        return new ResourceObject(user.subject(), "user", new MeAttributes(
                user.email(),
                user.roles(),
                user.activeOrgAlias(),
                user.activeOrgId() == null ? null : user.activeOrgId().toString(),
                status));
    }
}
