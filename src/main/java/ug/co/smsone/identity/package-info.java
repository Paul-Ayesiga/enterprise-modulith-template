/**
 * Identity module: the business projection of Keycloak users plus the admin-driven provisioning
 * lifecycle. There is <b>no JIT</b> — a valid JWT is not access; a user is reachable only after an
 * admin provisions them (Keycloak user + temporary credentials + a local {@code app_user} row), and
 * a {@code ProvisioningGateFilter} rejects authenticated-but-unprovisioned subjects. Other modules
 * provision members through {@link ug.co.smsone.identity.UserProvisioning}; internals live under
 * {@code internal}.
 *
 * <p>It also owns the <b>impersonation session</b> — an operator's audited, time-boxed reach into
 * another account — because that is an identity being worn, and the accounts it names are this
 * module's. Enforcement is not here: the filter that applies a session lives in {@code shared}, which
 * reaches back through the {@link ug.co.smsone.shared.security.ImpersonationLookup} port.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity")
package ug.co.smsone.identity;
