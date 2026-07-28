/**
 * Identity module: the business projection of Keycloak users plus the admin-driven provisioning
 * lifecycle. There is <b>no JIT</b> — a valid JWT is not access; a user is reachable only after an
 * admin provisions them (Keycloak user + temporary credentials + a local {@code app_user} row), and
 * a {@code ProvisioningGateFilter} rejects authenticated-but-unprovisioned subjects. Other modules
 * provision members through {@link ug.co.smsone.identity.UserProvisioning}; internals live under
 * {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity")
package ug.co.smsone.identity;
