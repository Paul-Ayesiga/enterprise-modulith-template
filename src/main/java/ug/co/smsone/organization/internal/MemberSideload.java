package ug.co.smsone.organization.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.directory.PersonProjection;
import ug.co.smsone.shared.directory.PersonProjections;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * {@code ?include=person} on the two member listings: turns a page of {@code personId}s into the
 * {@code included} array of a JSON:API compound document.
 *
 * <p><b>The problem it removes is a client-side N+1.</b> Both rosters answer with ids, so rendering
 * twenty members with names was twenty round trips to
 * {@code GET /api/v1/admin/users/{personId}} — paid over the network, by the browser, where nobody
 * can see it in a server profile. One parameter, one extra pair of statements
 * ({@link PersonProjections}), any page size.
 *
 * <p><b>Opt-in, permanently.</b> Without the parameter the response is byte-identical to what it was
 * before this class existed — {@code included} is absent, not empty — because a listing that silently
 * grew a second top-level array would be a breaking change dressed as a feature.
 * {@code MemberIncludeApiTest.withoutTheParameterTheResponseIsUnchanged} pins that.
 *
 * <h2>It discloses nothing new, on either surface</h2>
 *
 * <p>Both callers already hold the names and addresses this returns, through a longer path:
 * {@code member:read} is what the {@code org-members} exchange export requires and that file has
 * carried member e-mail since it shipped, and {@code platform-support} reads every person on the
 * platform at {@code GET /api/v1/admin/users}. The sideload changes how many requests that costs, not
 * who may ask. Anything that changes the second — a wider attribute, a new include target — is a new
 * authorization question and does not belong in this class.
 */
@Component
class MemberSideload {

    /** The one relationship name this parameter accepts. */
    private static final String PERSON = "person";

    /**
     * The JSON:API {@code type} of a sideloaded person: {@code user}, the same word
     * {@code GET /api/v1/admin/users/{personId}} answers with, and the same {@code id}
     * ({@code person.id}). A client that merges by {@code (type, id)} therefore merges these into the
     * records it already has. A second spelling — "person" — would have been more faithful to the
     * table and would have made the two surfaces two different resources to every client that caches
     * by type.
     */
    private static final String USER_TYPE = "user";

    /**
     * The attributes a sideloaded person carries: a strict SUBSET of the {@code user} resource, using
     * that resource's own key names so nothing has to be translated when the client later fetches one
     * whole.
     *
     * <p><b>{@code name} is the three-component projection, not the seven-component SCIM block</b>
     * (ADR 0010 §2.2 — {@link PersonProjection} carries the argument). That is a real difference and
     * not an omission: {@code middleName}, the honorifics and {@code preferredName} are absent here
     * and present at {@code GET /api/v1/admin/users/{personId}}, and because null components are
     * omitted from JSON a client cannot tell "not in the projection" from "this person has none". The
     * guide says so out loud; a client that needs the whole block is looking at ONE person and should
     * read that endpoint.
     *
     * <p>Membership {@code status} is deliberately not duplicated in here. The row in {@code data}
     * already carries one, they are different lifecycles (a person may be {@code DISABLED} while
     * their membership is {@code ACTIVE}), and two fields called {@code status} in one document is how
     * a client renders the wrong one.
     */
    record IncludedPerson(Name name, String email) {

        /** The projection's three components, spelled the way {@code attributes.name} spells them. */
        record Name(String formattedName, String givenName, String familyName) {
        }
    }

    private final ObjectProvider<PersonProjections> people;

    MemberSideload(ObjectProvider<PersonProjections> people) {
        this.people = people;
    }

    /**
     * Whether this request asked for the people, rejecting anything else it asked for.
     *
     * <p><b>Call it before doing the work, not after.</b> An unknown include value is a client bug and
     * has to be answered as one; validating it after the roster query would charge the tenant for a
     * page nobody can use. A 422 with {@code source.parameter} matches how every other query parameter
     * on this surface refuses ({@code AdminOrganizationController.parseStatus}, {@code page[after]}).
     *
     * <p>Silently ignoring an unrecognised value is the alternative and is worse: a client that ships
     * {@code ?include=persons} would get a page with no {@code included} array, conclude the feature
     * does not work, and go back to the N+1 this exists to remove.
     */
    boolean wantsPeople(String include) {
        if (include == null || include.isBlank()) {
            return false;
        }
        boolean wanted = false;
        for (String relationship : include.split(",")) {
            String name = relationship.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!PERSON.equals(name)) {
                throw new ValidationException("include must be '" + PERSON + "' — '" + name
                        + "' is not a relationship of a member.", ApiSource.parameter("include"));
            }
            wanted = true;
        }
        return wanted;
    }

    /**
     * The {@code included} array for one page: one entry per DISTINCT person the page names, in page
     * order, and nothing else.
     *
     * <p>Deduplication is structural — the ids go into a {@link LinkedHashSet} before the port sees
     * them, and the port takes a {@link Set} — because a compound document that lists a resource twice
     * is malformed and no reader is obliged to cope. Order is the page's, so a client walking
     * {@code data} and {@code included} in parallel sees them agree.
     *
     * <p>An id with no projection is simply skipped: the person was erased, the membership row
     * survives it, and the roster still shows the row with its bare id. Rendering a placeholder person
     * here would be inventing a human to explain a gap.
     *
     * @return possibly empty, never null — empty is the honest answer to "you asked, nothing resolved"
     */
    List<ResourceObject> peopleFor(List<UUID> personIds) {
        Set<UUID> distinct = new LinkedHashSet<>(personIds);
        // Absent implementation means no directory, which means no names — never an error. See the
        // port's javadoc: an unanswerable rendering degrades where an unanswerable authorization denies.
        PersonProjections directory = people.getIfAvailable();
        if (directory == null || distinct.isEmpty()) {
            return List.of();
        }
        Map<UUID, PersonProjection> projections = directory.projectionsOf(distinct);
        List<ResourceObject> included = new ArrayList<>(projections.size());
        for (UUID personId : distinct) {
            PersonProjection projection = projections.get(personId);
            if (projection != null) {
                included.add(new ResourceObject(personId.toString(), USER_TYPE,
                        new IncludedPerson(new IncludedPerson.Name(projection.formattedName(),
                                projection.givenName(), projection.familyName()), projection.email())));
            }
        }
        return List.copyOf(included);
    }
}
