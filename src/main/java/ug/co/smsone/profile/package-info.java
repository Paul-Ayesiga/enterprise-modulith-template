/**
 * User self-service identity: profile (display name, phone, timezone, locale), avatar (bytes
 * behind the files port, key on the profile), contacts, small per-user preferences, and the
 * read-only linked-accounts view (Keycloak federated identities via the identity module's
 * directory — we display IdP links, we never mutate them). Everything here is the CALLER'S own
 * record; the one cross-user surface is the platform-support read that tickets and oversight
 * embed. Org membership listing for organization switching lives in the organization module,
 * which owns that data.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Profile")
package ug.co.smsone.profile;
