package ug.co.smsone.identity.internal;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.identity.internal.PersonAccessService.PersonSummary;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.shared.web.ResourceObject;
import ug.co.smsone.shared.web.WindowedResult;

/**
 * Platform-wide listing of the people who can sign in, cursor-paginated. Read-only, so
 * {@code platform-support} is the floor.
 *
 * <p>The path and the {@code user} resource type are unchanged on purpose. "User" is the API's word for
 * a human with an account — the profile module hangs {@code /admin/users/{id}/profile} and
 * {@code /admin/users/{id}/devices} off the same noun — and renaming a wire contract is a change that
 * has to land with the OpenAPI export and the API guide in one slice. What DID change is the payload:
 * the id is now {@code person.id}, and {@code subject} is gone, because a Keycloak subject was never
 * something a support operator could do anything with.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
class PersonAdminController {

    private static final String RESOURCE_TYPE = "user";

    private final PersonAccessService access;

    PersonAdminController(PersonAccessService access) {
        this.access = access;
    }

    /**
     * {@code formattedName} is rendered as given, never assembled from parts: it is null for anyone
     * whose provider never supplied one, and a UI showing the e-mail instead is correct where one
     * showing {@code given + " " + family} would be showing some people their name backwards.
     */
    record PersonAttributes(String personId, String formattedName, String email, String status) {
    }

    @GetMapping
    @Operation(summary = "List provisioned users across the platform")
    @PreAuthorize("hasRole('platform-support')")
    WindowedResult<ResourceObject> list(CursorPageRequest page) {
        return WindowedResult.of(access.list(page), page, PersonAdminController::toResource);
    }

    private static ResourceObject toResource(PersonSummary person) {
        return new ResourceObject(person.personId().toString(), RESOURCE_TYPE,
                new PersonAttributes(person.personId().toString(), person.formattedName(), person.email(),
                        person.status().name()));
    }
}
