package ug.co.smsone.identity.internal;

import java.util.UUID;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * The wire rendering of a {@link PersonName}: the SCIM 2.0 / OIDC component names, unchanged, so a
 * client that already speaks either does not have to translate.
 *
 * <p>It is a file of its own rather than a record nested in a controller because <b>two</b> surfaces
 * render it — the person's own {@code /api/v1/me} and the operator's {@code /api/v1/admin/users/…} —
 * and two copies of one payload is how the same fact ends up spelled two ways.
 *
 * <p>A null component is omitted from the JSON entirely ({@code spring.jackson.default-property-
 * inclusion: non_null}), which is the honest rendering: absent means "we were never told", exactly
 * what null means in the column. So a person with no name at all answers {@code {}} here — a valid
 * state, not an error — and {@code formattedName} is absent for anyone whose provider never supplied
 * one. A client renders something else in that case (the e-mail, an avatar); what it must never do is
 * build a display string out of the parts, whose order is cultural. See {@link PersonName}.
 */
record PersonNameAttributes(String formattedName, String givenName, String familyName, String middleName,
        String honorificPrefix, String honorificSuffix, String preferredName) {

    /**
     * The JSON:API type for the name sub-resource, shared by both surfaces so they cannot drift. It is
     * {@code person-name} rather than {@code name} because a bare "name" in a {@code type} field reads
     * like a field, not like the thing being addressed.
     */
    static final String RESOURCE_TYPE = "person-name";

    static PersonNameAttributes of(PersonName name) {
        return new PersonNameAttributes(name.formattedName(), name.givenName(), name.familyName(),
                name.middleName(), name.honorificPrefix(), name.honorificSuffix(), name.preferredName());
    }

    /** The name as a standalone resource — what {@code PATCH …/name} answers with, on both surfaces. */
    static ResourceObject toResource(UUID personId, PersonName name) {
        return new ResourceObject(personId.toString(), RESOURCE_TYPE, of(name));
    }
}
