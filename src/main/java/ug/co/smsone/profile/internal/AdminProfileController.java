package ug.co.smsone.profile.internal;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The support view of a person's profile — the context a ticket or an oversight session starts from.
 * Read-only; editing someone's profile is either their own act or an impersonation session's.
 *
 * <p>It hangs off {@code /admin/users/{id}} because that is the API's noun for a human with an
 * account, and {@code id} there is now a {@code person.id} — the same identifier this module keys on,
 * so the path variable needs no translation.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminProfileController {

    private final ProfileService profiles;

    AdminProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/users/{personId}/profile")
    @Operation(summary = "Read a person's profile as the platform")
    @PreAuthorize("hasRole('platform-support')")
    ResourceObject profile(@PathVariable UUID personId) {
        return MeProfileController.toResource(profiles.view(personId));
    }
}
