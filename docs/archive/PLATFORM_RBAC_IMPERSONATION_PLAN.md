# Platform Roles + Owner-Only Org Seeding + Audited Impersonation — Implementation Plan

> **ARCHIVED — DELIVERED 2026-07-31, all three slices.** A (platform role ladder), B (org roles reduce to
> `OWNER`), C (audited impersonation) are implemented, gated and green. Kept as the decision record,
> not as work to do. **Where this plan and the shipped code disagree, the code wins**, and the two
> known divergences are called out inline below: the migration numbering (§4) and the impersonation
> design (§3), both of which were written before soft delete took V17.
>
> Current state lives in [../CHECKLIST.md](../CHECKLIST.md) (the gates), [../DATA_MODEL.md](../DATA_MODEL.md) §8
> (the schema as shipped), [../SRS.md](../SRS.md) §3.14 (the requirements and their tests) and
> [../../AGENTS.md](../../AGENTS.md) §5.5 (the invariants a contributor must not break).

Decisions (confirmed 2026-07-29):
- **Platform axis** = three hierarchical realm roles (`platform-superadmin` → `platform-admin` →
  `platform-support`) **replacing** `ADMIN`. A `RoleHierarchy` makes the higher tier satisfy the lower.
- **Org axis** = **permission-based**, with exactly one role constant: `OWNER`. Every other org role is
  a dynamic, owner-created permission bundle that no code path names.
- **Impersonation** = app-level session (`X-Impersonate: <sessionId>`), the actor keeps their own
  token, the effective principal is swapped, and the real actor is never lost.
- **Impersonation default = read-only.** A write-capable session is an explicit, higher-tier mode.

The two axes stay disjoint: a platform role grants **no** org permission (`ApiPermissionEvaluator` has
no role bypass, by design), and impersonation is the only sanctioned path from platform to tenant data.

---

## 1. Platform roles

Realm roles become `platform-superadmin`, `platform-admin`, `platform-support` (+ `USER`, unchanged).
`ADMIN` is removed from the realm export. Spring authorities stay `ROLE_<name>` via the existing
`KeycloakJwtAuthenticationConverter`, so checks read `hasRole('platform-support')`.

**Hierarchy** — one `RoleHierarchy` bean, wired into *both* the web and method-security expression
handlers (method security does not pick it up implicitly):

```
ROLE_platform-superadmin > ROLE_platform-admin
ROLE_platform-admin      > ROLE_platform-support
```

**Tier assignment** for the 10 sites that use `ADMIN` today:

| Endpoint | Tier | Why |
|---|---|---|
| `GET /api/v1/admin/users` | support | read-only platform view |
| `GET /api/v1/audit` | support | investigating is the support job |
| `GET /api/v1/scheduler/locks` | support | ops read |
| `GET /api/v1/analytics/reports[/{code}]` | support | read-only reporting |
| `PUT /api/v1/settings/{key}` | admin | changes platform behaviour |
| `PUT /api/v1/feature-flags/{key}` | admin | changes platform behaviour |
| `POST /api/v1/orgs` | admin | creates a tenant |
| `POST /api/v1/orgs/{id}/suspend` · `/reactivate` | admin | tenant lifecycle |
| `FileController.requireOwner` cross-namespace **read** | support | support fetches a user's upload |
| `FileController.requireOwner` cross-namespace **delete** | admin | destructive on tenant data |

Superadmin-only (new): impersonating a subject who *holds* a platform role, and `org:delete` if/when a
delete endpoint lands. Superadmin otherwise differs only by what it can escalate to.

`FileController.requireOwner` currently grants any ADMIN both read and delete on any namespace; the
split above is the one behaviour change here. Flagged as a candidate to move behind impersonation
entirely in a later pass — not in this one.

## 2. Org roles — permission-based with no name special cases

`RoleSeeder.systemRoleDefinitions()` reduces to one seeded role: code `OWNER`, the full permission
catalog, `system_role=true`. `ADMIN`/`MEMBER` are no longer created for new orgs.

Request authorization is **already** permission-based end to end and does not change:
`@PreAuthorize("hasPermission(#orgId,'organization','member:read')")` → `ApiPermissionEvaluator` →
`PermissionResolver` (membership → role → permissions, cached, evicted on role/membership change). A
role the owner creates is assignable immediately via `POST /members` or `PUT /members/{subject}/role`,
and `PermissionEscalationGuard` still stops anyone granting a permission they do not hold themselves.

`OWNER` **stays a constant**, exactly as it is today — the two places that name it are the org's
bootstrap and its safety net, and both are clearer as an explicit constant than as an inferred rule:
- `OrgProjectionWriter` — attaches the first owner to the OWNER role at provisioning.
- `MemberService` — last-owner protection on role reassign / member remove.

`RoleService.RESERVED_CODES` shrinks from `{OWNER, ADMIN, MEMBER}` to `{OWNER}` — the one code that is
still meaningful to the code is the one still worth reserving. A guard is added rejecting codes
starting with `PLATFORM` (case-insensitive, 422) so tenant roles cannot borrow the platform
vocabulary. `ADMIN` and `MEMBER` become ordinary codes an owner may create, edit and delete.

**V16** flips pre-existing `ADMIN`/`MEMBER` rows to `system_role=false` rather than deleting them:
memberships reference `org_role(id)`, so deletion would orphan real members. They become ordinary
custom roles the owner can edit or delete once unused.

## 3. Audited impersonation

> **Superseded in seven places by what shipped** (this section predates soft delete; the code is
> authoritative — [../DATA_MODEL.md](../DATA_MODEL.md) §8, [../SRS.md](../SRS.md) §3.14, [../../AGENTS.md](../../AGENTS.md) §5.5):
>
> 1. **Filter order.** `ImpersonationFilter` is `@Order(-2)` — before rate limiting, idempotency
>    *and* the provisioning gate, not merely before the gate — so the whole downstream request sees
>    one effective principal. The parenthetical below ("must pass the provisioning gate as itself")
>    was also inverted: the gate evaluates the target with `peek`, never `authorize`, because
>    activating an `INVITED` account on an operator's read is a durable write the target did not make.
> 2. **`CurrentUser`.** Not a loose `impersonatedBy` field but a nested, nullable
>    `Impersonation(sessionId, actorSubject)` plus `accountableSubject()` — an actor subject without
>    a session id is an impersonation nobody can trace back to its recorded reason.
> 3. **Safe methods.** `HEAD` and `OPTIONS` join `GET`: neither can change state, and refusing them
>    would break CORS pre-flight and existence checks for no gain.
> 4. **Deleted targets.** A soft-deleted account is a **409**, distinct from the 404 for an unknown
>    subject: a 404 invites re-provisioning the very account somebody erased.
> 5. **TTL.** An over-cap request is **rejected (422)**, not clamped — a silently shortened session
>    surfaces as an unexplained denial mid-investigation.
> 6. **`_expired` does not exist.** Expiry is evaluated on read, so there is nothing to raise an
>    event about and no sweep job. The shipped lifecycle actions are `_started` / `_ended` /
>    `_superseded` only.
> 7. **The kill switch refuses (403); it does not remove the routes (404).** `app.impersonation
>    .enabled=false` leaves both the controller and the filter registered and answering 403 with a
>    message naming the switch. A 404 was rejected because it is indistinguishable from a typo'd path
>    or a version skew, and silently ignoring `X-Impersonate` is worse still: the request would
>    succeed as the operator while they believed they were wearing the target, so they would read
>    their own data thinking it was the customer's.

**Module placement.** The session lives in `identity` (it impersonates an identity and already owns
`app_user` + the admin surface). `shared.security` gains a port `ImpersonationLookup`, mirroring the
existing `OrgAuthorization` seam, so the enforcing filter has no compile-time dependency on `identity`.

```
POST   /api/v1/admin/impersonations   {targetSubject, orgId?, reason, mode?, ttl?}  -> 201 {id, expiresAt}
GET    /api/v1/admin/impersonations                                                 -> active + history
DELETE /api/v1/admin/impersonations/{id}                                            -> end now
```

`POST` requires `platform-support`; `mode=WRITE` requires `platform-admin`.

**Request path.** `ImpersonationFilter` runs after authentication and *before* `ProvisioningGateFilter`
(the impersonated subject must pass the provisioning gate as itself):

1. No `X-Impersonate` header → untouched.
2. Resolve the session by id **and actor subject** — a session id leaked to another user is useless.
3. Reject if expired, ended, or (mode=`READ_ONLY`) the method is not GET → 403 envelope.
4. Swap the `SecurityContext` principal to the target: subject/email from `app_user`, **no platform
   roles**, `activeOrgId` from the session. Org permissions then resolve from the DB exactly as for the
   real user — which is precisely why impersonation works for tenant endpoints and cannot reach
   `/admin/**` (the impersonated principal holds no platform role).
5. `CurrentUser` gains `impersonatedBy` (the real actor, else `null`).

**Guardrails.**
- Target must exist and be provisioned (`app_user`, not `DISABLED`).
- Target holding any `platform-*` role → `platform-superadmin` only.
- `reason` required, ≥ 8 chars — it is the audit record's justification.
- TTL default 15 min, hard cap 30 (`app.impersonation.*`), enforced server-side.
- One active session per (actor, target); re-issuing supersedes and audits the supersede.
- Global kill switch `app.impersonation.enabled` (default true; false = **403 refusal**, see note 7).

**Audit.** `audit_log.actor` already exists and holds the acting principal. **V19** (planned here as
V18 — see §4) adds
`on_behalf_of` and `impersonation_id`: during a session `actor` = the real human, `on_behalf_of` = the
impersonated subject, and every row correlates back to the session that carries the reason. The
`AuditLog` port signature is unchanged — the audit module fills all three from the security context, so
no call site changes and nothing can forget to record it. Session lifecycle itself is audited as
`platform.impersonation_started` / `_ended` / `_expired` / `_superseded`.

Optional, not in this pass: notifying the impersonated user or the org owners on session start.

## 4. Migrations

**The numbering below is wrong and is kept only to show what it was.** This plan was written before
soft delete landed and took **V17**, which pushed both impersonation migrations up by one. As
shipped:

| Planned | Shipped | |
|---|---|---|
| **V16** | `V16__org_role_owner_only.sql` | `org_role` — flip `ADMIN`/`MEMBER` to `system_role=false` |
| — | `V17__soft_delete.sql` | soft delete (not in this plan; it landed between slices B and C) |
| ~~V17~~ | **`V18__impersonation_session.sql`** | `impersonation_session` (id, actor_subject, target_subject, org_id, reason, mode, started_at, expires_at, ended_at, ended_by, + `BaseEntity` audit columns) — **no `deleted_at`**, deliberately: sessions end, they are never deleted |
| ~~V18~~ | **`V19__audit_log_impersonation.sql`** | `audit_log` — add `on_behalf_of varchar(64)`, `impersonation_id uuid`, partial index on `on_behalf_of` |

The next free migration number is **V20**.

## 5. Acceptance gates

- Real Keycloak token: `platform-support` reads `/admin/users` (200) but 403s on `PUT /settings/{key}`;
  `platform-superadmin` passes both — proving the hierarchy, not just role presence.
- A new org has **exactly one** role (`OWNER`); the owner creates `AUDITOR`, assigns it to a member,
  and that member gets exactly the granted permissions and 403 elsewhere.
- No org route resolves authority from a role code other than `OWNER` — a member on a custom role with
  `member:invite` can invite, and the same member renamed to `ADMIN` gains nothing extra.
- Impersonation: support impersonates an org member → `GET /orgs/{id}/members` 200,
  `POST /orgs/{id}/members` 403 (read-only), `/api/v1/admin/users` 403 (no platform role).
- Ending a session denies the very next request; an expired session denies without a sweep job.
- Audit rows for the impersonated reads carry actor ≠ on_behalf_of and correlate to the session.
- Cross-actor session id is rejected; impersonating a platform-role holder needs superadmin.
- `ApplicationModules.verify()` + ArchUnit green; OpenAPI and Modulith docs regenerated.

## 6. Ripples

Every `ADMIN` reference in docs (`LOCAL_ACCESS.md`, `IDENTITY_ORG_PLAN.md`, `IMPLEMENTATION_PLAN.md`),
the realm export, `scripts/`, the Postman/OpenAPI spec, and the org RBAC tests
(`OrgRbacApiTest`/`OrgRbacAuthorityTest` currently assume seeded `ADMIN`/`MEMBER` — they will create
their own roles instead, which is a better test of the dynamic-role model anyway).

`IDENTITY_ORG_PLAN.md` documents seeded `OWNER/ADMIN/MEMBER` and a last-owner rule; it gets a
superseded-by note pointing here rather than a silent edit, so the decision history stays readable.

Dev tokens carrying `ADMIN` stop working the moment the realm is re-imported. Dev-only; `make restart`
picks up the new roles.
