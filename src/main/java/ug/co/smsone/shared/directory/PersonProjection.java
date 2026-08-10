package ug.co.smsone.shared.directory;

import java.util.UUID;

/**
 * The projection of a human that may travel: <b>id, the three name components, status, and the one
 * display address</b> — and nothing else, ever.
 *
 * <p>The field list is not a convenience choice, it is ADR 0010 §2.2 quoted as a record: "id,
 * {@code formatted_name} / {@code given_name} / {@code family_name}, {@code status}, and the one
 * contact the org actually uses". {@code compliance.internal.PersonProjector} emits exactly these
 * columns into an extraction bundle; this record is the same set on the request path. Two shapes of
 * one decision would be two places to widen it, and widening it is how a verified
 * {@code person_contact} or an {@code external_identity} row ends up in a second database in
 * violation of {@code uq_person_contact_verified_live} / {@code uq_external_identity_subject_live} —
 * platform-wide constraints that no silo can enforce from where it sits.
 *
 * <p><b>{@code email} is a DISPLAY address, not a proof, and must never be inverted.</b> It is
 * whatever the person is best reached at — verified if they have one, the invite address if they do
 * not — because a member list has to render something for somebody who has never signed in. Matching
 * an address back to a person is {@code identity.PersonDirectory#findPersonIdByEmail}, which refuses
 * to answer from an unproven row precisely so that parking a claim on a colleague's address is not an
 * account-takeover primitive. Null when nothing is on file.
 *
 * <p><b>{@code formattedName} is the only display value.</b> Never assemble one from
 * {@code givenName} and {@code familyName}: name order is cultural, so {@code given + " " + family}
 * prints much of the world's names backwards. Every component is nullable, including all three at
 * once — a person nobody supplied a name for is an ordinary state, and a client renders the e-mail
 * then.
 *
 * <p>The five columns of {@code person} that are NOT here — {@code middleName}, both honorifics,
 * {@code preferredName} — are absent by the same paragraph. A caller that needs the whole SCIM block
 * is looking at one person, not at twenty, and reads {@code GET /api/v1/admin/users/{personId}}.
 *
 * @param status the {@code ProvisioningStatus} name ({@code INVITED} / {@code ACTIVE} /
 *     {@code DISABLED}) as a String, deliberately: the enum belongs to {@code identity}, and
 *     publishing it into {@code shared} would make adding a lifecycle state a recompile for every
 *     module that renders one.
 */
public record PersonProjection(UUID personId, String formattedName, String givenName, String familyName,
        String status, String email) {
}
