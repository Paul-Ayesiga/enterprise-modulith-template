/**
 * Billing and payments through Kill Bill. Kill Bill is the BILLING system of record — accounts,
 * subscriptions, invoices and payments live there, keyed back to us by {@code externalKey} =
 * org id — while the {@code subscription} module stays the ENTITLEMENT authority: Kill Bill's push
 * notifications reconcile INTO it through the {@code Subscriptions} port, so a paid plan arrives
 * through exactly the same audited path a manual comp does, and a payment failure flips standing
 * to {@code PAST_DUE} without touching entitlements until the billing state says so. This module
 * owns the gateway (timeouts mandatory, remote calls outside transactions), the
 * {@code billing_account} linkage projection, the platform/tenant billing surfaces, and the
 * callback endpoint. It never gates anything itself — gating is the subscription module's job.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Billing")
package ug.co.smsone.billing;
