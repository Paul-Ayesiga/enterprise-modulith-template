/**
 * Subscriptions and entitlement gating: the commercial axis of a tenant, orthogonal to its
 * lifecycle status. The module owns the seeded plan catalog (FREE/PRO/ENTERPRISE), one
 * subscription per org (no row = FREE), and the {@code Entitlements} port everything else gates
 * on — member count at invite, webhook count at create, the exchange feature and schedule cap at
 * submit. It depends on nothing but the kernel, so any module may consult it without a cycle.
 * Payment processing is out of scope BY DESIGN: this module is the entitlement authority; a
 * billing integration drives it through the admin endpoint and hears {@code SubscriptionChanged}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Subscription")
package ug.co.smsone.subscription;
