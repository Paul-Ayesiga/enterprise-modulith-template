/**
 * Identity module: {@code person} is the canonical identity of a human on this platform, and every
 * identifier minted somewhere else lives beside it in {@code external_identity}. Provisioning is
 * admin-driven and there is <b>no JIT</b> — a valid JWT is not access; a person is reachable only after
 * an admin provisions them (a {@code person} row, a {@code person_contact} to reach them at, a linked
 * Keycloak account and its invite), and {@code ProvisioningGateFilter} rejects
 * authenticated-but-unprovisioned humans. Other modules provision members through
 * {@link ug.co.smsone.identity.PersonProvisioning} and address people by {@code person.id} through
 * {@link ug.co.smsone.identity.PersonDirectory}; internals live under {@code internal}.
 *
 * <p><b>This module is the only place an issuer and a subject exist.</b> The edge validates a token and
 * hands down a provider subject; {@code PersonResolver} turns that pair into a {@code person.id} once,
 * and nothing downstream — here or in any other module — sees it again. The same discipline keeps
 * Keycloak's {@code firstName}/{@code lastName} vocabulary inside
 * {@code KeycloakUserAdminGateway}: a boundary translates, it does not leak.
 *
 * <p>It also owns the <b>impersonation session</b> — an operator's audited, time-boxed reach into
 * another account — because that is an identity being worn, and the accounts it names are this
 * module's. Enforcement is not here: the filter that applies a session lives in {@code shared}, which
 * reaches back through the {@link ug.co.smsone.shared.security.ImpersonationLookup} port.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity")
package ug.co.smsone.identity;
