# Tenant Lifecycle

How the platform manages an organization from birth to erasure — every state, who moves it, and
what each move touches. The operating rule behind all of it: **platform operators act ON tenants
(lifecycle, plans, oversight); acting AS a tenant is impersonation's job and is audited.**

## States and transitions

    (none) ──create──► ACTIVE ◄──reactivate── SUSPENDED ──delete──► DELETED (soft)
                          │                        ▲                    │
                          └───────suspend──────────┘              purge (P30D)
                                                                        │
                                                                        ▼
                                                                     erased

| Transition | Who | Endpoint | What happens |
|---|---|---|---|
| create | `platform-admin` | `POST /api/v1/orgs` | Keycloak org + local projection + first OWNER provisioned (invite email); `OrganizationRegistered` published; audited `organization.created` |
| suspend | `platform-admin` | `POST /api/v1/orgs/{id}/suspend` | Status flips; `OrganizationStatusChanged` evicts every member's cached permissions — access dies immediately, data stays. Reversible |
| reactivate | `platform-admin` | `POST /api/v1/orgs/{id}/reactivate` | The mirror of suspend |
| delete | `platform-admin` | `DELETE /api/v1/admin/orgs/{id}` | **Only from SUSPENDED** (409 otherwise) — suspension is the reversible "are you sure" that already cut access. Soft delete; `OrganizationDeleted` published (permission caches cleared, `org.deleted` webhook fans out); audited `organization.deleted`. Memberships/roles stay beneath the hidden row so a restore yields a working org |
| purge | `SoftDeletePurgeJob` (nightly) | — | Past `app.persistence.soft-delete.retention` (P30D) the row is hard-deleted — the point of no return that makes an erasure request actually erase. The search-residue sweep un-indexes the org in the same run |

**The Keycloak organization is deliberately kept on delete.** With the local projection gone, every
permission resolution fails closed (no org row → no permissions → 403), so access is dead without
touching the IdP; keeping the Keycloak record preserves the account linkage a restore needs. If a
tenant must also vanish from the IdP, that is an operator action in Keycloak, taken after the purge.

## Observing tenants

| Need | Endpoint | Tier |
|---|---|---|
| Every tenant, newest first (filter `?status=`) | `GET /api/v1/admin/orgs` | `platform-support` |
| One tenant's record | `GET /api/v1/admin/orgs/{id}` | `platform-support` |
| One tenant's roster (subjects + role codes, read-only) | `GET /api/v1/admin/orgs/{id}/members` | `platform-support` |
| A tenant's audit trail | `GET /api/v1/audit?org=…` | `platform-support` |
| Acting inside the tenant | impersonation session | audited, TTL-bound |

Managing members/roles/webhooks remains the tenant's own surface (`/api/v1/orgs/{orgId}/**`,
permission-gated) — the platform observes and governs, it does not micro-manage.

## Commercial state — subscriptions

Orthogonal to the lifecycle status: a tenant is ACTIVE *and* on a plan. The `subscription` module
(see SRS §3.19) holds the plan catalog (FREE/PRO/ENTERPRISE, seeded), one subscription row per org
(none = FREE), and the `Entitlements` port other modules gate on — member count on invite, webhook
count on create, the exchange feature and schedule count on submit. Assigning a plan is a platform
action: `PUT /api/v1/admin/orgs/{id}/subscription` (`platform-admin`), audited, cache-evicted via
`SubscriptionChanged`, announced to the tenant's webhooks as `org.subscription_changed`. The tenant
reads its own state at `GET /api/v1/orgs/{orgId}/subscription`. Payment processing is out of scope
by design — the module is the ENTITLEMENT authority; a billing integration drives it through the
same admin endpoint.
