package ug.co.smsone.gateway.core.security;

/**
 * The organization a credential names, spelled the way the EDGE can see it — and that qualification is
 * the whole point of the type. The platform's tenant key is an {@code organization.id} it mints itself,
 * reachable only by reading {@code external_organization}; the gateway is forbidden a business database
 * (ADR 0007) and would not survive needing one. So what the edge holds is what the token said, and
 * routing decisions are made on that or not at all.
 *
 * <p><b>Both halves, because either can be the one that resolves.</b> Keycloak mints the
 * {@code organization} claim as a map KEYED BY ALIAS — {@code {"acme":{"id":"01H…"}}} — so a token
 * carries a mutable alias and a stable provider id, and the platform's own {@code OrgLookup} accepts
 * either. An API key carries neither: introspection answers with the platform's organization id, which
 * lands in {@link #id()}. A router that insisted on one spelling would silently stop routing the moment
 * a deployment configured the other.
 *
 * <p><b>An alias is never trusted as an identifier</b> — same rule {@code OrgLookup} states. Nothing
 * here resolves anything; these are opaque strings compared against operator-written configuration, so
 * an alias that collides with somebody's id matches only what an operator wrote down.
 *
 * @param alias the organization's human-readable key from the token's organization claim, or null
 * @param id the identifier that claim carries under the alias — or, for an API key, the organization id
 *        the platform's introspection answered — or null
 */
public record EdgeOrganization(String alias, String id) {

    /** The request names no organization: unauthenticated, a service token, or a claim naming none. */
    public static final EdgeOrganization NONE = new EdgeOrganization(null, null);

    public boolean isEmpty() {
        return isBlank(alias) && isBlank(id);
    }

    public EdgeOrganization {
        alias = isBlank(alias) ? null : alias;
        id = isBlank(id) ? null : id;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
