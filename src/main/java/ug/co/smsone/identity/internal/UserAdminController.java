package ug.co.smsone.identity.internal;

import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/** Platform-admin user listing (Keycloak realm role ADMIN), cursor-paginated. */
@RestController
@RequestMapping("/api/v1/admin/users")
class UserAdminController {

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final UserRepository users;

    UserAdminController(UserRepository users) {
        this.users = users;
    }

    record UserAttributes(String subject, String email, String status) {
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    WindowedResult<ResourceObject> list(CursorPageRequest page) {
        Window<User> window = users.findBy(
                (root, query, cb) -> cb.conjunction(),
                query -> query.limit(page.size()).sortBy(LIST_SORT).scroll(page.scrollPosition()));
        return WindowedResult.of(window, page, UserAdminController::toResource);
    }

    private static ResourceObject toResource(User user) {
        return new ResourceObject(user.getSubject(), "user",
                new UserAttributes(user.getSubject(), user.getEmail(), user.getStatus().name()));
    }
}
