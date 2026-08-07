/**
 * Self-service signup — the platform's public front door. A visitor asks to create an organization,
 * proves control of their email (hashed single-use token, TTL-bound), and the verified request runs
 * the SAME provisioning path a platform admin uses ({@code organization}'s port): the tenant row and
 * its provider org link, an invited OWNER person (set-password email), {@code OrganizationRegistered}
 * fires, and the trial-on-signup and billing auto-provision listeners do the rest. Off by default
 * ({@code SIGNUP_ENABLED}) — the enterprise, admin-provisioned mode stays the safe baseline.
 *
 * <p>{@code signup_request} is the PRE-IDENTITY table and the cleanest argument for person being the
 * root rather than a projection of an identity provider: it describes a human — an email, a name —
 * before any {@code external_identity} exists for them, and it always did. Completion records both
 * ids the handshake produced ({@code org_id}, {@code owner_person_id}) in the transaction that
 * creates them, so the request → person link is a column rather than an email-string match.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Signup")
package ug.co.smsone.signup;
