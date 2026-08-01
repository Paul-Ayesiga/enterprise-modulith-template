package ug.co.smsone.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/** Platform-wide user listing, cursor-paginated. Read-only, so {@code platform-support} is the floor. */
@RestController
@RequestMapping("/api/v1/admin/users")
class UserAdminController {

    private static final String RESOURCE_TYPE = "user";

    private final UserAccessService access;

    UserAdminController(UserAccessService access) {
        this.access = access;
    }

    record UserAttributes(String subject, String email, String status) {
    }

    @GetMapping
    @Operation(summary = "List provisioned users across the platform")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> list(CursorPageRequest page) {
        return WindowedResult.of(access.list(page), page, UserAdminController::toResource);
    }

    private static ResourceObject toResource(User user) {
        return new ResourceObject(user.getSubject(), RESOURCE_TYPE,
                new UserAttributes(user.getSubject(), user.getEmail(), user.getStatus().name()));
    }
}
