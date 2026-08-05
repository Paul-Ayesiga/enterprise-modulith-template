/**
 * Scheduled maintenance windows. ANNOUNCE windows are banner metadata a client renders; RESTRICT
 * windows make org-scoped WRITES to the covered scope answer 503 + Retry-After during the window
 * (reads always pass). A platform-scoped window covers every tenant; an org-scoped one covers a
 * single tenant. Distinct from a tenant's lifecycle status (suspend/reactivate/delete —
 * docs/plans/TENANT_LIFECYCLE.md): those are permanent state changes, these are time-boxed, announced
 * pauses. Enforced in a filter, like the org security policy.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Maintenance")
package ug.co.smsone.maintenance;
