# Identity + Organization + Org-Scoped RBAC — Implementation Plan

Decisions (confirmed 2026-07-28): **org-scoped RBAC**; active org from the **JWT `organization` claim**
(Keycloak 26 **Organizations**, GA); **fixed permission catalog** (code) + **DB-editable custom roles**
(bundles of permissions) with seeded OWNER/ADMIN/MEMBER; **admin-API + event-driven provisioning, no
JIT** — access only after provisioning, user gets **temporary Keycloak credentials**. All versions/
APIs web-verified 2026-07-28.

Design-level calls taken here (were open in the research's risk list):
- **`Organization.id` == Keycloak org id** (no per-request claim→local lookup; `#orgId` path vars are the KC id).
- **Org creation is in scope** (POST /orgs, platform-admin gated) → the service account gets `manage-organizations`.
- **Temp creds default = `execute-actions-email`** (`UPDATE_PASSWORD`+`VERIFY_EMAIL`) — admin never sees a password; Mailpit in dev. Fallback: `reset-password temporary=true`.
- **Keycloak Admin API via Spring `RestClient` + `spring-boot-starter-oauth2-client`** (service-account `client_credentials`). **No `keycloak-admin-client`** (stuck at 26.0.11, drags RESTEasy + Jackson 2 into a Jackson-3 app).

## Module dependency direction (acyclic — Modulith-verified)
```
shared ──► (nothing)      identity ──► shared      organization ──► shared, identity
```
`shared.security` owns a new port `OrgAuthorization`; `organization` provides the impl (runtime wiring, no compile cycle). Cross-module links are **soft refs** (`membership.user_subject` varchar), no cross-module FKs.

## Permission catalog (fixed enum `ug.co.smsone.organization.Permission`)
`org:read`, `org:update`, `org:delete`, `org:settings:read`, `org:settings:update`,
`member:read`, `member:invite`, `member:remove`, `member:role:assign`,
`role:read`, `role:create`, `role:update`, `role:delete`.
Stored as enum name in `role_permission`; unknown code on write → 422.

### Seeded system roles (per org, `system_role=true`, immutable)
- **OWNER** = all permissions · **ADMIN** = all except `org:delete` · **MEMBER** = `org:read`,`member:read`,`role:read`,`org:settings:read`.
Custom roles: `system_role=false`, any subset, org-scoped, CRUD via API.

## RBAC integration
- `@PreAuthorize("hasPermission(#orgId,'organization','member:invite')")` → the JWT's **active org must == `#orgId`** (cross-org denied pre-DB) **and** the caller's role in that org contains the permission.
- `ApiPermissionEvaluator` delegates to `OrgAuthorization` (default-deny when no impl / no active org).
- `OrgAuthorizationImpl` resolves membership→role→permissions, **cached** (Caffeine L1 + Valkey L2), evicted on `RolePermissionsChanged`/`MembershipRoleChanged`/`MemberRemoved`.
- `CurrentUser` gains `activeOrgAlias`/`activeOrgId`, parsed from the alias-keyed `organization` claim (`{"acme":{"id":"…"}}`, needs `addOrganizationId=true` on the mapper; a token scoped to ≠1 org → null → deny).

## Provisioning (no JIT)
`POST /api/v1/orgs/{orgId}/members {email,firstName,lastName,roleCode}` (needs `member:invite`) →
1. `identity.UserProvisioning.provision(...)`: Keycloak `POST /users` (username=email, enabled, emailVerified=false; 409 → reuse), issue temp creds (`execute-actions-email` default), upsert `app_user` row `status=INVITED`, publish `UserProvisioned`.
2. Keycloak `POST /organizations/{kcOrgId}/members` (body = quoted user id).
3. Save `Membership(orgId, subject, roleId, ACTIVE)`.
Keycloak calls precede the short local transaction; a mid-flight failure leaves an `INVITED` row + no membership (no access) → re-invite is idempotent.

**Access gate** (`ProvisioningGateFilter`, after JWT auth): unknown `sub` → 403 `account_not_provisioned` (the no-JIT guarantee — a valid JWT is *not* enough); `DISABLED` → 403; `INVITED` → lazy-activate to `ACTIVE` (+`UserActivated`) then allow; `ACTIVE` → allow. `GET /api/v1/me` allowed for `INVITED`.

## REST surface (envelope + cursor pagination + `@PreAuthorize`)
- **identity**: `GET /api/v1/me` (auth, incl. INVITED); `GET /api/v1/admin/users` (platform `ADMIN`).
- **organization**: `POST /api/v1/orgs` (platform admin, creates KC org + projection); `GET/PATCH /orgs/{orgId}` (`org:read`/`org:update`); `GET/POST /orgs/{orgId}/members` (`member:read`/`member:invite`); `DELETE /orgs/{orgId}/members/{subject}` (`member:remove`, unlinks membership — never deletes the KC user); `PUT /orgs/{orgId}/members/{subject}/role` (`member:role:assign`, last-owner-protected); `GET/POST/PUT/DELETE /orgs/{orgId}/roles[/{roleId}]` (`role:*`, system roles immutable); `GET /api/v1/permissions` (catalog).

## Migrations (next free V10)
- **V10** `app_user` (subject unique, email, status, provisioned_at, activated_at + audit).
- **V11** `organization` (id = kc org id, alias unique), `role` (org_id FK, code, system_role, unique(org_id,code)), `role_permission` (role_id FK, permission), `membership` (org_id FK, user_subject soft-ref, role_id FK, unique(org_id,user_subject)).

## Realm changes (`docker/keycloak/realm-smsone.json`)
1. `"organizationsEnabled": true`. 2. built-in `organization` client scope w/ `oidc-organization-membership-mapper` + `addOrganizationId=true`. 3. `smsone-web` gets `optionalClientScopes:["organization"]` (SPA requests `scope=organization:<alias>`). 4. new confidential `smsone-admin` (service account) with realm-management roles `manage-organizations`,`view-organizations`,`query-organizations`,`manage-users`,`view-users`. 5. seed org `acme` with `paul` as OWNER for local dev.

## Dependencies
One new starter: `spring-boot-starter-oauth2-client` (BOM-managed) for the service-account token. RestClient from existing `spring-boot-starter-web`; JSON via Boot Jackson 3. No `org.keycloak:*`.

## Build order (phased) — ✅ all shipped (2026-07-28)
1. ✅ **Shared plumbing** — `OrgAuthorization` port, `keycloakAdminRestClient`, `CurrentUser`/provider org-claim parsing, `ApiPermissionEvaluator`. _(commit 15193a7)_
2. ✅ **identity** — V10, `User`/status/repo, Keycloak user gateway, provisioning service + port, `ProvisioningGateFilter`, `GET /me`, `/admin/users`. _(commit a17d421)_
3. ✅ **organization** — V11, `Permission`, `Role`/`Membership`/`Organization` + repos, `RoleSeeder`, `PermissionResolver`, `OrgAuthorizationImpl` (+cache+eviction). Evaluator live. _(commit 66afd19)_
4. ✅ **Provisioning + REST** — KC org gateway, org/member/role services, controllers with `@PreAuthorize`, last-owner protection, realm changes, dev bootstrap; Idempotency-Key transparent. _(commit f332099)_
5. ✅ **Hardening** — Modulith verify, HTTP RBAC matrix + cross-org/no-active-org denial, invite orchestration, last-owner block, async cache-eviction test, adversarial review, docs.

Note vs. the original outline: org creation installs the first OWNER (solves the `member:invite` chicken-and-egg); the `organization` scope ships as an **optional** client scope (`scripts/token.sh` requests it) so multi-org switching stays possible.

## Open confirmations
- SPA must actually request `scope=organization:<alias>` or the claim (and all org `@PreAuthorize`) silently denies — the biggest live-config gotcha.
- Org switching for multi-org users = re-auth with a new scope (no server-side "current org").
- Last-owner protection on member remove / role reassign so an org can't be locked out.
