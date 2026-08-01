package ug.co.smsone.profile.internal;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The support view of a user's profile — the context a ticket or an oversight session starts from.
 * Read-only; editing someone's profile is either the user's own act or an impersonation session's.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminProfileController {

    private final ProfileService profiles;

    AdminProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/users/{subject}/profile")
    @Operation(summary = "Read a user's profile as the platform")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject profile(@PathVariable String subject) {
        return MeProfileController.toResource(profiles.view(subject));
    }
}
