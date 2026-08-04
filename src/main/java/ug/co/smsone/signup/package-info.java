/**
 * Self-service signup — the platform's public front door. A visitor asks to create an organization,
 * proves control of their email (hashed single-use token, TTL-bound), and the verified request runs
 * the SAME provisioning path a platform admin uses ({@code organization}'s port): Keycloak org +
 * invited OWNER (set-password email), {@code OrganizationRegistered} fires, and the trial-on-signup
 * and billing auto-provision listeners do the rest. Off by default ({@code SIGNUP_ENABLED}) — the
 * enterprise, admin-provisioned mode stays the safe baseline.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Signup")
package ug.co.smsone.signup;
