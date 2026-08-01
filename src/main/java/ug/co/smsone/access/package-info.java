/**
 * Access controls beyond RBAC: a user's registered DEVICES (self-service; a future push channel
 * reads their tokens; the org can trust one) and per-organization SECURITY POLICIES (IP allowlist,
 * require-a-trusted-device, session max age) enforced in a filter after authentication. Devices and
 * policies live together because the trusted-device gate is a policy that reads devices. A policy
 * denial is a distinct, audited, counted 403 that names the policy — never mistakable for an RBAC
 * decision; every policy field TIGHTENS access over the platform default, never loosens it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Access")
package ug.co.smsone.access;
