package ug.co.smsone.organization;

/**
 * The organization-provisioning port: create an organization with an invited OWNER through the same
 * audited path the platform-admin endpoint uses (tenant row, owner person + set-password invite,
 * provider org link, {@code OrganizationRegistered}). Consumed by self-service signup; throws the
 * standard {@code ConflictException} when the alias is taken so the caller can retry with a variant.
 *
 * <p>Returns this platform's own ids. It used to return the Keycloak organization id, which is how a
 * provider identifier ended up as a JSON:API resource id, a Kill Bill external key and the gateway's
 * usage consumer id (V11's header is the post-mortem).
 */
public interface Organizations {

    /**
     * {@code ownerGivenName} / {@code ownerFamilyName}, not first/last: name ORDER is cultural, so
     * "first" and "last" name a position in one rendering rather than the part itself. Both are
     * optional — a mononym is an ordinary name — and neither is ever concatenated for display.
     */
    ProvisionedOrganization create(String alias, String name, String ownerEmail, String ownerGivenName,
            String ownerFamilyName);
}
