/**
 * The integration hub: which external provider serves an organization for a capability
 * (SMS, email, payment gateway) and its config, at platform-default and org-override scope.
 * Exposes the {@code Integrations} resolution port other modules consult at use time — the
 * notification channels resolve per-org provider creds through it. Secret config values are
 * AES-GCM encrypted at rest and masked on read (the webhook signing-secret pattern). Adding a
 * provider is a new provider code plus a consumer that reads its settings; the port stays closed.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Integration Hub")
package ug.co.smsone.integration;
