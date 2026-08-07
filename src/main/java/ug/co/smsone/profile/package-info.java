/**
 * A person's self-service display record: profile (display name, timezone, locale), avatar (bytes
 * behind the files port, key on the profile), small per-person preferences, and the read-only
 * linked-accounts view (identity providers, read through the identity module's {@code
 * PersonDirectory} — we display IdP links, we never mutate them). Everything here keys on {@code
 * person.id} as a soft ref; the one cross-person surface is the platform-support read that tickets
 * and oversight embed.
 *
 * <p>Contacts are NOT here. Reachability — e-mail and phone, labelled, primary, verified — moved to
 * {@code person_contact} in the identity module (V10/V28): a person with no profile still has an
 * e-mail, and a verification is an event about a specific row. Org membership listing for
 * organization switching lives in the organization module, which owns that data.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Profile")
package ug.co.smsone.profile;
