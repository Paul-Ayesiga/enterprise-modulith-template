# AGENTS.md — engineering standards for `enterprise-modulith-template`

The rules every agent and contributor follows in **this** repository. Not a style textbook: where a
principle is generic it gets one line; where this codebase has a specific, enforced answer, that
answer is the standard and a real file is cited as the reference implementation.

**How to use it.** Read §1 before writing any code. Use §14 as the pre-merge review checklist.
When a rule and an existing file disagree, the file wins only if you can say why — otherwise fix the
file.

**Prime directive:** *find the neighbouring file that already solves your problem and match it.*
Every section below points at that file.

---

## 1. Hard rules with teeth

These fail the build or the review. They are not preferences.

| Rule | Enforced by | Breaks as |
|---|---|---|
| Module boundaries: no reaching into another module's `internal` | `ModularityTests.verifiesModularStructure()` → `ApplicationModules.verify()` | test failure naming the illegal dependency |
| No field injection (`@Autowired` on fields) | `ArchitectureTests.noFieldInjection` | ArchUnit failure |
| No `throw new RuntimeException/Exception` | `ArchitectureTests.noGenericExceptions` | ArchUnit failure |
| No `System.out` / `System.err` | `ArchitectureTests.noStandardStreams` | ArchUnit failure |
| Every infra-touching test runs on real containers — **no H2, no embedded substitutes, no mocked repositories** | ADR 0003, `testsupport/AbstractIntegrationTest` | review rejection |
| Every JSON response is the envelope (`data` XOR `errors`, `meta.requestId`) | `EnvelopeResponseBodyAdvice`, `EnvelopeContractTest`, `ProblemDetailContractTest` | contract test failure |
| Collections paginate by **cursor**; no `page[number]`, no totals, no `COUNT` | ADR 0002, `Cursors` / `CursorPageRequest`, `WindowedResult`, `CursorPaginationContractTest` | contract test failure |
| A soft-deletable entity declares its **own** `@SQLDelete` (with `and version = ?` **and** `version = version + 1`) and `@SQLRestriction` | `ArchitectureTests.softDeletableEntitiesDeclareTheirOwnHibernateAnnotations` | ArchUnit failure |
| `SoftDeletePurgeJob.PURGE_ORDER` covers every soft-deletable entity, children before parents | `SoftDeletePurgeJobIntegrationTest.purgeOrderCoversEverySoftDeletableEntity` | test failure |
| No cross-module foreign keys — cross-module links are soft refs (`membership.user_subject varchar`) | migration review | review rejection |
| No JIT provisioning: a valid JWT is **not** access | `identity.internal.ProvisioningGateFilter` | review rejection |
| Platform roles and org permissions are **disjoint** axes; no role bypasses a permission check | `ApiPermissionEvaluator` (no role branch), `PlatformRoleHierarchyTest`, `OrgRbacApiTest` | review rejection |
| Impersonation is the only bridge between the axes, and it carries **no** authority — the swapped principal's authority collection is empty | `ImpersonatedAuthenticationToken`, `ImpersonationReachTest.supportReachesTenantDataThroughASessionAndTheAdminSurfaceOnlyOutsideOne` | test failure |
| Inside a session `audit_log.actor` is the **operator**; the worn identity moves to `on_behalf_of` | `AuditLogImpl.attribution()`, `ImpersonationReachTest.anAuditRowFromInsideASessionNamesTheOperatorAndTheIdentityTheyWore` | test failure |
| A session is re-authorized on **every** request — ending it, its expiry, or revoking either account denies the very next one | `ImpersonationLookupImpl`, `ImpersonationReachTest.endingASessionDeniesTheVeryNextRequest`, `.anExpiredSessionDeniesWithoutAnySweepJobHavingRun`, `.anOperatorWhoLosesTheirPlatformTierLosesTheSessionTheyHold` | test failure |
| `ImpersonationFilter` restores the previous `SecurityContext` in a `finally` — a pooled request thread never inherits someone else's identity | `ImpersonationFilterTest.theContextIsSwappedForTheChainAndRestoredAfterwards` (the restore) and `.aChainThatThrowsStillLeavesTheOperatorsOwnContextOnTheThread` (the `finally` specifically), `ImpersonationReachTest.theRequestAfterAnImpersonatedOneSeesItsOwnIdentity` | test failure |
| A session never writes to the account it wears — no lazy `INVITED → ACTIVE` on someone else's read | `ProvisioningGateFilter.decide`, `ImpersonationProvisioningGateTest.aSessionNeverActivatesTheTargetItWears` | test failure |
| Anything durable keys on the token **subject**, never `preferred_username` | `CurrentUserProvider.currentSubject()`, `SubjectAttributionTest` | review rejection |
| `audit_log` is append-only; it is never soft-deletable and never mutated. `impersonation_session` is the same rule one step sharper: end-only, never deleted | `AuditEntry extends BaseEntity`, `V17__soft_delete.sql` header; `ImpersonationSession extends BaseEntity`, `V18__impersonation_session.sql` header | review rejection |
| No stack trace, framework message, or internal detail reaches the wire | `GlobalExceptionHandler`, `server.error.include-*: never` | contract test failure |
| No Lombok. Records + constructor injection | ADR 0001 | review rejection |

Build commands:

```bash
./gradlew compileJava compileTestJava
./gradlew test --tests <pattern>
./gradlew exportModulithDocs   # refreshes docs/modulith/ — commit the diff
./gradlew exportOpenApi        # refreshes docs/openapi/
```

---

## 2. Module system

Modules are Java packages under `ug.co.smsone`. One Gradle module; physical splitting is a
deliberate later step (ADR 0001), which is exactly why the logical boundaries are enforced now.

### 2.1 Layout, non-negotiable

```
ug.co.smsone.<module>/            <- the module API: ports, events, DTOs, enums. Public.
ug.co.smsone.<module>/internal/   <- everything else: entities, repositories, services, controllers.
ug.co.smsone.<module>/package-info.java   <- @ApplicationModule + a paragraph on what the module IS
```

Anything in the API package is a promise to other modules. If another module does not need it, it
goes in `internal`. Controllers, services, entities and repositories are `internal` and
**package-private** (`class OrganizationService`, `interface MembershipRepository`) — see
`organization/internal/`. Make a class public only when a cross-module port or Spring's proxying
forces it, and say why.

### 2.2 Dependency direction

```
business modules  ──►  shared (OPEN kernel)
business modules  ──►  another module's API package only, never its internals
shared            ──►  nothing in a business module (compile-time)
```

- `shared` is the **only** `ApplicationModule.Type.OPEN` module (`shared/package-info.java`).
  Business modules must never be OPEN — that would delete their boundary.
- `shared` never compile-depends on a business module. When it needs behaviour a module owns, it
  declares a **port** and default-denies / no-ops when no implementation is present.

### 2.3 Ports: where the interface lives decides who depends on whom

| Port | Declared in | Implemented by | Why there |
|---|---|---|---|
| `shared.security.OrgAuthorization` | `shared` | `organization.internal.OrgAuthorizationImpl` | method security lives in `shared`; the RBAC data lives in `organization`. `ApiPermissionEvaluator` resolves it via `ObjectProvider` and **default-denies** when absent |
| `shared.security.ImpersonationLookup` | `shared` | `identity.internal.ImpersonationLookupImpl` | `ImpersonationFilter` lives in `shared` (it swaps the `SecurityContext` before every other filter); the session table lives in `identity`. Same seam as `OrgAuthorization` — absent impl means the header cannot be authorized, so it is **denied**, never ignored |
| `shared.audit.AuditLog` | `shared` | `audit.internal.AuditLogImpl` (`@Primary`) | any module must be able to audit without depending on `audit` |
| `identity.UserProvisioning` | `identity` (API pkg) | `identity.internal.UserProvisioningService` | `organization` provisions members without touching Keycloak itself |
| `notification.Notifications` | `notification` | `notification.internal.NotificationService` | fan-out is the notification module's business |
| `files.FileStorageProvider` | `files` | `files.internal.S3StorageProvider` | S3 SDK types never escape the module |
| `analytics.AnalyticsEngine` | `analytics` | `analytics.internal.DuckDbAnalyticsEngine` | ClickHouse/Trino stay a pure impl swap (ADR 0006) |

Rule: **the interface belongs to the consumer's world, the implementation to the owner's.** A
`shared` port + `ObjectProvider` + default-deny is the pattern when `shared` is the consumer.

### 2.4 Adding a module

1. `ug.co.smsone.<name>/package-info.java` with `@ApplicationModule(displayName = "…")` and a
   paragraph explaining what it owns and what it deliberately does not.
2. API package: events (records), ports, public enums. `internal/` for everything else.
3. Migration `V<next>__<name>.sql` — its own tables, no FK to another module's tables.
4. Integration test extending `AbstractIntegrationTest`.
5. `./gradlew exportModulithDocs` and add the module to `docs/ARCHITECTURE.md`; new events go in
   `docs/EVENTS.md`.

---

## 3. HTTP contract

### 3.1 Controllers

Thin. Validate, delegate, map to a resource. No business logic, no repository access, no
transaction handling.

| Do | Reference |
|---|---|
| Return `ResourceObject` (`{id, type, attributes}`) or `WindowedResult<ResourceObject>` | `settings/internal/SettingController` |
| Let `EnvelopeResponseBodyAdvice` wrap it — never hand-build `ApiResponse` in a controller | `shared/web/EnvelopeResponseBodyAdvice` |
| Declare request bodies as nested `record`s with Jakarta constraints, `@Valid @RequestBody` | `OrganizationController.CreateOrganizationRequest` |
| `@ResponseStatus(HttpStatus.CREATED)` on creates | `OrganizationController.create` |
| Put the authorization annotation on the handler, one per operation | §5 |
| `private static ResourceObject toResource(...)` mapper at the bottom of the controller | every controller |

`RESOURCE_TYPE` is a `private static final String` constant per controller; it is a wire contract —
renaming it breaks clients.

### 3.2 Errors

Throw a typed `ApiException` subclass. Never a bare `RuntimeException` (ArchUnit blocks it), never a
`ResponseStatusException`.

| Throw | Status | `code` on the wire |
|---|---|---|
| `ValidationException(detail, ApiSource)` | 422 | `VALIDATION_FAILED` |
| `NotFoundException` | 404 | `RESOURCE_NOT_FOUND` |
| `ConflictException` | 409 | `CONFLICT` |
| `ForbiddenException` | 403 | `FORBIDDEN` |
| `UnauthorizedException` | 401 | `UNAUTHORIZED` |

Rules:

- `ErrorCode` enum **names are the wire contract** — renaming an entry is a breaking API change.
  Adding a code is additive; do it in `shared/error/ErrorCode` and nowhere else.
- `detail` is a curated, client-safe sentence. Never an exception message, never a SQL string,
  never an internal identifier the caller can't act on.
- Attach an `ApiSource` so the client can point at the offending input:
  `ApiSource.pointer("/data/attributes/permissions")` for body fields,
  `ApiSource.parameter("page[after]")` for query params, `ApiSource.header("Idempotency-Key")`.
- The catch-all 500 in `GlobalExceptionHandler.handleUnexpected` is the **only** place a stack trace
  is logged, and it logs the `requestId` so support can join the two.
- A `Filter` that throws bypasses `GlobalExceptionHandler` entirely. Filters render errors
  themselves via `EnvelopeErrorWriter` — see `ProvisioningGateFilter.doFilterInternal`'s catch block.

### 3.3 Pagination — cursor only

```java
private static final Sort MEMBER_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

Window<Membership> list(UUID orgId, CursorPageRequest page) {
    return memberships.findBy(
        (root, query, cb) -> cb.equal(root.get("orgId"), orgId),
        q -> q.limit(page.size()).sortBy(MEMBER_SORT).scroll(page.scrollPosition(MEMBER_SORT)));
}
```

- Every listable entity needs a **stable unique** sort. `createdAt desc, id desc` is the house
  default; `id` is the tiebreaker that makes it unique.
- Always call `page.scrollPosition(SORT)` — the overload that validates the cursor's keys against
  the collection's sort. `scrollPosition()` without the sort turns a cursor minted for another
  collection into a 500 instead of a 422.
- The `Sort` constant lives next to the query that uses it, `private static final`.
- `WindowedResult.of(window, page, mapper)` in the controller; the advice emits `meta.page` and
  `links.next`.
- Never add `COUNT`, `totalPages`, or offset paging. If a caller needs a total, that is a product
  conversation, not a pagination change.

### 3.4 Cross-cutting request machinery (do not reinvent)

| Concern | Component | Rule |
|---|---|---|
| Request id | `RequestIdFilter` (`HIGHEST_PRECEDENCE`) | ULID unless the client sends a well-formed `X-Request-Id`. `traceId`/`spanId` never go on the wire |
| Impersonation | `ImpersonationFilter` (`@Order(-2)`) | `X-Impersonate: <sessionId>` swaps the effective principal **before** every other filter, so one request has ONE identity end to end — §5.5. Never reorder it below rate limiting, idempotency or the provisioning gate |
| Idempotency | `IdempotencyFilter` (`@Order(1)`, after the org-MDC filter (`-1`) and rate limiting (`0`)) | keys are **per principal** — a global namespace is an authorization hole (ADR 0005). Only status < 400 is stored |
| Rate limiting | `RateLimitFilter` + `RateLimitKeyResolver` | buckets key on tenant → subject → IP. Never on username |
| Caching | `TwoLevelCache` (`@Primary` manager) | cache String keys and JSON-serializable values; writers evict **and** broadcast (ADR 0004) |
| SSRF | `SafeOutboundUrl.requireSafe(url, allowPrivateHosts)` | **mandatory** for any caller-supplied outbound URL. It is the first line only — a deployment still needs an egress policy |

---

## 4. Domain model & persistence

### 4.1 Entity hierarchy — pick deliberately

| Base | Gives you | Use for | Examples |
|---|---|---|---|
| `BaseEntity` | UUID id, `@Version`, created/updated at/by, id-based equality | rows that are records of fact, not aggregates | `AuditEntry`, `InAppNotification`, `ImpersonationSession` |
| `AggregateRoot` | + `registerEvent(...)` published on `save(..)` | aggregate roots that emit domain events | — |
| `SoftDeletableEntity` | + `deleted_at`, `markDeleted`, `restore` | aggregate roots whose deletion must be recorded | `Organization`, `Membership`, `Role`, `Setting`, `FeatureFlag`, `User`, `WebhookSubscription`, `Translation`, `Document` |

Entity conventions, all visible in `organization/internal/Organization.java`:

- `protected Xxx() { // JPA }` — the no-arg constructor exists for Hibernate and says so.
- Static factory (`Organization.register(...)`, `User.invited(...)`) instead of a public constructor
  or setters. **No setters.** State changes are named behaviours: `suspend()`, `assignRole()`,
  `replacePermissions()`, `activate()`.
- Guards live in the entity when they are invariants: `Role.requireEditable()` (system roles are
  immutable), `Organization.suspend()`'s idempotent early return (no spurious event/cache flush).
- Getters are package-private unless a cross-package caller genuinely needs them.
- Enums are `@Enumerated(EnumType.STRING)` with an explicit `length`. Never `ORDINAL`.

### 4.2 Soft delete — read this before touching a delete path

`repository.delete(entity)` on a `SoftDeletableEntity` issues an `UPDATE`, not a `DELETE`. Services
were deliberately **not** changed when soft delete landed.

Each concrete entity must declare **both** annotations itself — Hibernate does not inherit them from
a mapped superclass, and a missing one is a silent hard delete or a silent leak of deleted rows:

```java
@SQLDelete(sql = "update org_role set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
```

`ArchitectureTests.softDeletableEntitiesDeclareTheirOwnHibernateAnnotations` rebuilds that exact
string from the entity's own `@Table` name and fails on any drift, including a missing increment.

| Rule | Why |
|---|---|
| **Both** halves of the version clause are mandatory | the `where version = ?` predicate keeps optimistic locking honest: deleting a stale instance affects zero rows and raises the usual concurrency failure instead of silently winning. The `version = version + 1` increment is the half that is easy to miss — a hard DELETE dooms any instance loaded before it, but a soft delete leaves the row there, so without the bump a concurrent flush still matches `version = ?` and writes its own null `deletedAt` back. The deletion is silently undone and the caller sees a 200 |
| Every unique constraint on a soft-deletable table is a **partial** unique index `where deleted_at is null` | a soft-deleted row still occupies its key; without this, deleting role `AUDITOR` permanently forbids creating another one. See `V17__soft_delete.sql` |
| `@SQLRestriction` hides deleted rows from **all** HQL/criteria queries automatically | you never add `and deletedAt is null` to a JPA query — and you must remember it does **not** apply to native SQL |
| There is no `deleted_by` column, by design | `@SQLDelete` is raw SQL and cannot see the security context. The actor lives in the `audit_log` row the deleting service writes |
| `restore()` callers must re-check uniqueness first | the key was free while deleted; a live row may now hold it |
| Retention is what makes deletion real: `SoftDeletePurgeJob` hard-deletes past `app.persistence.soft-delete.retention` (P30D) | soft delete alone is a UI state, not an erasure guarantee. A restore is only possible inside the window |

Adding a new soft-deletable table means editing **three** places, in this order:

1. the entity (`extends SoftDeletableEntity` + both annotations);
2. the migration (`deleted_at` column, partial unique indexes, `idx_<table>_deleted` partial index
   on `where deleted_at is not null` for the retention scan);
3. `SoftDeletePurgeJob.PURGE_ORDER` — **children before parents**. That list is load-bearing:
   `membership.role_id -> org_role(id)` has no `on delete cascade` and is blind to `deleted_at`, so
   purging `org_role` first fails the moment a role and its memberships age out together. Tables
   whose FK *is* `on delete cascade` (`role_permission`, `webhook_delivery`) are deliberately absent.

The purge job is also the reference for "why native SQL here": `@SQLRestriction` makes the very rows
it exists to remove invisible to JPA. That, and bounded batches committing on their own connections
rather than one giant transaction, are the two decisions worth copying.
`SoftDeletePurgeJobIntegrationTest` covers every table plus the FK-ordering case
(`purgesAnAgedMembershipAndItsAgedRoleInOneRun`) — extend it when you add a table, do not write a
parallel test.

**Not soft-deletable, deliberately:** `audit_log` (append-only — a deletable audit trail is not an
audit trail), `impersonation_session` (same reason, sharper: the person a delete would serve is the
operator whose reach the row records — sessions *end*, they are never removed), `in_app_notification`
(disposable, not an aggregate), `webhook_delivery` (a log, trimmed by retention), `role_permission`
(an `@ElementCollection` following its role), and framework tables. Do not "fix" these.

Second-order effect to keep in mind: an FK no longer backstops a check, because the row survives.
`RoleService.delete` documents exactly that trade-off and why the resulting race is acceptable
(it fails closed — access lost, never gained).

### 4.3 Transactions

| Rule | Reference |
|---|---|
| The boundary is the **service** method, not the controller and not the repository | `SettingService` (`@Transactional` on the class, `readOnly = true` on reads) |
| Self-invocation of a `@Transactional`/`@Cacheable` method is a **no-op** — the proxy is bypassed. Extract a separate bean | `OrgProjectionWriter` (tx), `PermissionResolver` (cache) — both exist for exactly this reason and say so in their javadoc |
| Reads are `@Transactional(readOnly = true)` | `SettingService.list` |
| **Keycloak (and any remote) calls run OUTSIDE the local transaction** | `MemberService.invite`, `UserProvisioningService.provision`, `OrganizationService.provisionOwner` |
| When part of an operation must commit before a remote call, use `TransactionTemplate.executeWithoutResult`, not a wider `@Transactional` | `MemberService.remove` |
| `spring.jpa.open-in-view: false` — no lazy loading outside the service boundary | `application.yaml` |

Why remote calls stay outside: a remote round-trip inside a transaction pins a Hikari connection and
any pessimistic row locks for the duration of someone else's outage. The compensating design is that
every remote step is **idempotent and ordered so a mid-flight failure leaves no partial local
state** — a retry then finishes the job. `UserProvisioningService` spells out the ordering rationale
step by step; copy that reasoning, not just the shape.

### 4.4 Repositories

- Spring Data interfaces, package-private, in `internal`. Add `JpaSpecificationExecutor` when the
  module needs keyset scrolling.
- Derived query methods for the simple cases; `@Query` only when derivation cannot express it, and
  then with a javadoc saying why — see `MembershipRepository.lockByOrgIdAndRoleIdAndStatus`
  (`@Lock(PESSIMISTIC_WRITE)` to close the last-owner TOCTOU race).
- `saveAndFlush` when you need a constraint violation to surface *here* as a documented 409 rather
  than as a 500 at commit (`RoleService.create`).
- Catch `DataIntegrityViolationException` only where losing a race has a well-defined idempotent
  outcome, and resolve to the winner's row (`MemberService.saveMembership`).
- Hot, high-fan-out queue work uses plain `JdbcTemplate`, not JPA: `WebhookDeliveryQueue`,
  `NotificationDeliveryQueue`, `EventInbox`, `ExchangeJobStore`.

### 4.5 Migrations

- `src/main/resources/db/migration/V<n>__<snake_name>.sql`. **V26 is taken; the next free number is
  V27.** Never edit an applied migration; never renumber.
- `ddl-auto: validate`. The schema is the migration's job, always.
- Head the file with a comment explaining the *decision*, not the statements — `V17__soft_delete.sql`
  and `V11__organization_rbac.sql` are the reference voice.
- Drop constraints **unqualified** (no `if exists`) when this project created them: a drifted name
  must fail loudly at deploy time rather than silently leave the old constraint in place.
- No FK across module boundaries. Within a module, FKs are fine (`role_permission.role_id`).
- New index? Say in a comment which query it serves. Partial indexes where the predicate is
  selective — `where deleted_at is not null` for retention scans (live rows are the majority and
  would be dead weight on every write).

---

## 5. Security

### 5.1 Two disjoint authorization axes

| Axis | Source | Check | Grants |
|---|---|---|---|
| **Platform** | Keycloak realm roles → `ROLE_<name>` | `@PreAuthorize("hasRole('platform-admin')")` | operator capability across tenants |
| **Organization** | DB: membership → role → permissions | `@PreAuthorize("hasPermission(#orgId, 'organization', 'member:invite')")` | capability inside one tenant |

They do not intersect. `ApiPermissionEvaluator` has **no role bypass** — a platform superadmin holds
zero org permissions. Reaching tenant data as an operator is impersonation's job (**§5.5**), which is
audited, time-boxed and reason-bearing; a role that silently widened is none of those. Do not add a
"but admins can do anything" branch.

- Platform roles are hierarchical (`SUPERADMIN → ADMIN → SUPPORT`, `SecurityConfig.platformRoleHierarchy`).
  A check names the **minimum** tier that may pass it, never a set of tiers. Use the constants in
  `shared.security.PlatformRole`, never a string literal in a second place.
- Org permission codes come from the fixed `organization.Permission` catalog (`org:read`,
  `member:role:assign`, …). Roles are DB-editable bundles of them. **`OWNER` is the only role code
  the application names**; no request path resolves authority from a role code.
- Choosing the axis: if a fresh org has no members yet, or the action cuts every member's access, it
  cannot be gated on a permission held inside that org — that's platform. `OrganizationController`
  documents this for create/suspend.
- Client roles are namespaced (`ROLE_<client>_<role>`) and never flattened into the realm namespace
  (`KeycloakJwtAuthenticationConverter`) — otherwise a client role named `platform-admin` would
  satisfy `hasRole('platform-admin')`.

### 5.2 Identity: subject, not username

`preferred_username` is mutable and, once freed, reassignable. Anything durable keys on the token
**subject**:

```java
currentUserProvider.currentSubject()   // audit attribution, idempotency scoping, rate-limit buckets,
                                       // created_by/updated_by, membership.user_subject
```

`CurrentUser.username()` is for display only. `JpaAuditingConfig.auditorProvider` writes the subject
(with the `"system"` sentinel outside a request), which is what makes `created_by` join-compatible
with `audit_log.actor` and `membership.user_subject`.

### 5.3 No JIT provisioning

A valid Keycloak JWT is not access. `ProvisioningGateFilter` rejects an authenticated but
unprovisioned subject with `ACCOUNT_NOT_PROVISIONED`; `GET /api/v1/me` is the single lenient path
(onboarding) and is still a hard stop for `DISABLED`. Never add a code path that creates an
`app_user` from a token.

### 5.4 Privilege escalation

Handing someone a role **is** granting its permissions. Every path that grants permissions goes
through `PermissionEscalationGuard.requireCallerHolds` — role create/update *and* member
invite/re-role. Without the latter, `member:role:assign` alone is equivalent to OWNER.

### 5.5 Impersonation — the only sanctioned path from platform to tenant data

§5.1's two axes never intersect, so an operator investigating a tenant has exactly one way in: open a
session and wear the account. That is deliberate. A widened role would reach the same data silently
and permanently; a session is authorized once, bounded by a server-set deadline, carries a stated
reason, and names the operator on every row it writes.

The surface is `POST` / `GET` / `DELETE /api/v1/admin/impersonations` (`ImpersonationController`,
floor `PlatformRole.SUPPORT`), and the header is `X-Impersonate: <sessionId>` on any `/api/**`
request. The session table lives in `identity`; the enforcing filter lives in `shared` and reaches
back through the `ImpersonationLookup` port (§2.3) — that seam is why `shared` still compile-depends
on no business module, and it is not optional.

**The five invariants. Breaking any one of them silently un-builds the feature.**

| Invariant | Where it lives | Why it is the invariant |
|---|---|---|
| The impersonated principal's authorities are **empty** | `ImpersonatedAuthenticationToken` (`super(List.of())`) | That emptiness *is* the mechanism, both ways at once: org permissions still resolve from the database for the target, so tenant endpoints work — and every `hasRole('platform-*')` fails, so `/admin/**` (including the endpoint that mints sessions) is unreachable from inside one. Granting one authority here undoes both halves |
| `audit_log.actor` stays the **accountable human** | `AuditLogImpl.attribution()` | The one place in the system where attribution deliberately differs from the request's effective subject. `created_by`, `updated_by`, the rate-limit bucket and the idempotency key all record the *target* — this row records who made the account look that way |
| Every gate is re-decided **per request**, never trusted from open time | `ImpersonationFilter` (actor's tier, mode vs method), `ImpersonationLookupImpl` (expiry, both accounts' provisioning state) | A session outlives its authorization by up to its whole TTL. A revocation that only stopped the *next* open would leave the reach it already granted running to the clock |
| The previous `SecurityContext` is restored in a `finally` | `ImpersonationFilter` | Request threads are pooled. A leaked context hands the next request somebody else's identity — the worst failure this codebase can produce |
| A session **reads** the account it wears; it never writes to it | `ProvisioningGateFilter.decide` (`peek`, not `authorize`) | Lazy `INVITED → ACTIVE` means "the person finally showed up", and an operator wearing them is not them showing up |

**Ordering is load-bearing.** `@Order(-2)` — immediately after authentication (`-100`) and before the
org-MDC filter (`-1`), rate limiting (`0`), idempotency (`1`) and the provisioning gate (`2`). The whole downstream request must
see ONE effective principal; swapping later would bucket the operator's rate limit and idempotency
keys under their own identity while the handler ran as someone else. The filter's own two decisions
are the mode check — `READ_ONLY` admits `GET`, `HEAD` and `OPTIONS` only, since none of them can
change state — and re-reading the actor's tier. Everything else it needs, the port answers.

**Guardrails live in `ImpersonationService`, not in the filter** — the filter applies a session that
already exists, so anything decidable once is settled at authorization time and written into the row:
target must exist, not be `DISABLED` and not be soft-deleted (404 and 409 stay distinct — a 404
invites re-provisioning the account somebody erased); impersonating a platform-role holder needs
`SUPERADMIN`; `mode=WRITE` needs `ADMIN` and is checked in the service because an annotation cannot
see the body; `reason` ≥ 8 characters; TTL default 15 min, cap 30, an over-cap request **rejected**
rather than clamped; one live session per (actor, target), re-issuing supersedes and audits it;
nobody impersonates themselves.

`app.impersonation.enabled=false` makes the feature **refuse**, not vanish. Both the controller and
the filter stay registered and answer **403** with a message naming the switch. A 404 would be
indistinguishable from a typo'd path or a version skew; and silently ignoring `X-Impersonate` is worse
still — the request would succeed as the operator while they believed they were wearing the target,
so they would read their own data thinking it was the customer's.

Lifecycle is audited through the `AuditLog` port as `platform.impersonation_started` / `_ended` /
`_superseded`. There is no `_expired` and no sweep job — expiry is evaluated on read.

Reference files, in the order worth reading them: `shared/security/ImpersonationFilter`,
`shared/security/ImpersonationLookup` (+ `ImpersonatedPrincipal`, `ImpersonatedAuthenticationToken`),
`identity/internal/ImpersonationService`, `identity/internal/ImpersonationLookupImpl`,
`identity/internal/ImpersonationSession`, `audit/internal/AuditLogImpl`,
`V18__impersonation_session.sql`, `V19__audit_log_impersonation.sql`. Tests: `ImpersonationApiTest`
(who may open one, against whom, for how long), `ImpersonationReachTest` (what an open session
reaches and every revocation that must kill it), `ImpersonationFilterTest` (header handling, safe
methods, the context restore), `ImpersonationProvisioningGateTest` (the gate, switched on),
`ImpersonationDisabledTest` (the kill switch).

### 5.6 Other standing rules

- Tenant isolation is strict id equality (`ApiPermissionEvaluator`): the org the endpoint acts on
  must **be** the org permissions resolve against. No alias matching — alias-addressed URLs resolve
  the alias to its id *before* the check.
- Multi-tenant reads filter by the tenant key in the query, not after (`RoleService.require` filters
  by `orgId` before returning).
- Every caller-supplied outbound URL goes through `SafeOutboundUrl`.
- Secrets are `${ENV:default}` properties and never logged. Dev defaults exist and are commented
  `ALWAYS override in production` (`app.keycloak-admin.client-secret`).
- Fail closed. Every ambiguous authorization state in this codebase denies:
  no `OrgAuthorization` impl → deny; no active org on the token → deny; suspended org → zero
  permissions; membership pointing at a deleted role → zero permissions.

---

## 6. Events

Outbox = the Modulith DB event-publication registry. Inbox = `shared.events.EventInbox`.

| Rule | Reference |
|---|---|
| Events are `record`s in the module's **API** package, carrying `occurredAt` | `organization.MembershipRoleChanged` |
| Publish by `registerEvent(...)` on an `AggregateRoot`; Spring Data publishes on `save(..)` | `Organization.suspend()` |
| A `delete` does **not** trigger `@DomainEvents` — publish explicitly | `MemberService.remove` → `events.publishEvent(new MemberRemoved(...))` |
| Consume with `@ApplicationModuleListener` (async, own transaction, after the publisher commits) | `webhooks.internal.WebhookEventListener` |
| Delivery is **at-least-once**. Any listener with side effects calls `EventInbox.recordIfNew(listenerId, messageId)` first and returns when it is false | message id derived from business identity, e.g. `code + ":" + orgId + ":" + subject + ":" + occurredAt` |
| Add a row to `docs/EVENTS.md` — both tables — when a new event or consumer lands | |
| Idempotent state changes return early instead of re-publishing | `Organization.suspend()`, `MemberService.assignRole`'s no-op branch |

`@ApplicationModuleListener` requires `@EnableAsync` (`shared.config.AsyncConfig`) and runs on
virtual threads.

---

## 7. Background work & concurrency

- **Cron** lives in the `scheduler` module, `@Scheduled` + `@SchedulerLock` so a job fires once
  across all instances (`IdempotencyPurgeJob`, `SoftDeletePurgeJob`). ShedLock uses DB time
  (`usingDbTime()`) — instances need no clock agreement. The one sanctioned exception is a job that
  needs another module's `internal` collaborators: it stays in that module and still carries
  `@Scheduled` + `@SchedulerLock` (`identity.internal.IdentityReconciliationJob`, which needs
  `KeycloakUserAdminGateway` and `UserRepository`). `scheduler` owns the ShedLock infrastructure, not
  a monopoly on `@Scheduled`. What is never acceptable is `@Scheduled` without `@SchedulerLock`.
- **A purge run never swallows a failure.** It rethrows, ShedLock releases, the next run retries. A
  constraint violation during a purge is a data bug that must be *seen*. Where a run does exactly one
  kind of work, aborting on the first failure is the whole answer (`IdempotencyPurgeJob`,
  `EventPublicationPurgeJob`). Where a run walks several independent tables it must **isolate, log and
  rethrow at the end** instead (`SoftDeletePurgeJob.purgeExpiredSoftDeletes`): the tables are ordered,
  so aborting at the first one would starve every table after it — erasure stops platform-wide and the
  only symptom is one stack trace at 04:00. Loud AND complete, not loud instead of complete.
- Purge jobs are also not `@Transactional`: bounded batches commit independently so a backlog
  never becomes one long-held lock set, and a `maxBatches` cap keeps one run inside its lease
  (`SoftDeletePurgeJob`).
- **Durable queues** (`webhook_delivery`, `notification_delivery`) follow one pattern, and new ones
  must match `WebhookDeliveryQueue`:
  - claim a batch with `for update skip locked` so instances never double-claim;
  - **fence every status update** on `(status = 'PROCESSING' and attempts = ?)` so a stale claimant
    whose row was re-claimed cannot corrupt the new owner's state;
  - a stale-lock interval reclaims rows from a crashed instance;
  - retry with exponential backoff, then dead-letter;
  - join to fetch the secret at claim time rather than copying it into the delivery row.
- **Timeouts are mandatory** on anything remote, and must be shorter than the lock/lease that
  protects the work. Precedents: Lettuce 2s (a 60s default turns an outage into a stall, not a
  degrade), SMTP 5s vs `stale-lock` PT5M, webhook `timeout-seconds` 5.
- **Races**: prefer a DB constraint + a documented idempotent resolution over locking. Reach for
  `@Lock(PESSIMISTIC_WRITE)` only for genuine invariant TOCTOU (last-owner), and say so in javadoc.
- Time comes from the injected `Clock` bean for anything persisted or asserted in a test
  (`AuditLogImpl`, `EventInbox`, `ApiMetaFactory`, `JpaAuditingConfig`). Entity event stamps use
  `Instant.now()`.

---

## 8. Configuration

- All Spring config in YAML. The only `.properties` file in the repo is `gradle.properties`.
- Every app setting lives under `app.` in `application.yaml` as `${ENV_VAR:default}` — so the
  Kubernetes migration is config, not code.
- Bind to a `@ConfigurationProperties` **record** owned by the code the policy belongs to, not by
  whoever happens to consume it: `SoftDeleteProperties` lives in `shared/persistence` beside the
  mapping it governs and is switched on by `SchedulingConfig`'s `@EnableConfigurationProperties`,
  because the scheduler is only the module that *acts* on it. Others: `CacheProperties`,
  `WebhookProperties`, `NotificationProperties`, `RateLimitProperties`.
- Defaults go in the record via `@DefaultValue` or a compact constructor — not scattered across
  `@Value` call sites. `WebhookProperties` normalizes null/non-positive values there.
- A comment above a value explains *why that number*, not what it is. See the Hikari pool note
  ("keep pool ≥ notification delivery concurrency") and the Lettuce timeout note.
- Validate dangerous config **in the record's compact constructor** so a bad value fails at startup,
  not at 4am: `SoftDeleteProperties` rejects a negative retention (it would purge everything);
  `ProvisioningProperties` rejects a platform role as the provisioning baseline.
- Any irreversible scheduled action gets an `*-enabled` flag so an operator can freeze it without a
  redeploy (`app.persistence.soft-delete.purge-enabled`).
- `@Configuration(proxyBeanMethods = false)` everywhere.
- Test overrides go in `src/test/resources/application-test.yaml`, off by default, and the one test
  that needs a subsystem re-enables it against its own container. Follow the existing comment style
  there — each disabled block says which test turns it back on.

---

## 9. Logging & observability

- SLF4J, `private static final Logger log = LoggerFactory.getLogger(X.class)`. No `System.out`
  (ArchUnit).
- Parameterized, never concatenated: `log.error("Member {} removed from org {}: {}", subject, orgId, ex.toString())`.
- Log **decisions and failures**, not flow. If a message would appear on every happy-path request,
  delete it.
- Log the `requestId` (it is in the MDC via `RequestIdFilter`) whenever the log line is what support
  will search for.
- WARN/ERROR for things a human must act on. `MemberService.remove` logs ERROR for a failed Keycloak
  unlink precisely because access was already revoked and only ops can finish the job.
- Never log tokens, secrets, passwords, or full request bodies.

---

## 10. Testing

Philosophy: this template's value is *verified* behaviour, not the appearance of coverage.

| Rule | Detail |
|---|---|
| Extend `ug.co.smsone.testsupport.AbstractIntegrationTest` | full context, real Postgres 18 via a singleton Testcontainer, `@ActiveProfiles("test")` |
| **No H2, no embedded substitutes, no mocked repositories** | ADR 0003. Real Hibernate against real Postgres, real JWKS validation, real S3 semantics |
| Mock only the things at the system's edge | `@MockitoBean KeycloakOrgAdminGateway`, `@MockitoBean UserProvisioning` — see `OrgRbacApiTest` |
| HTTP surfaces are tested over HTTP | `@AutoConfigureMockMvc` + `MockMvc`, asserting on the envelope: `$.data…`, `$.errors[0].code`, `$.meta.requestId` |
| Auth is faked at the token, not at the evaluator | `jwt().jwt(j -> j.subject(...).claim("organization", Map.of(alias, Map.of("id", orgId))))` |
| Test names are sentences that state the rule | `aNonOwnerCannotSelfPromoteToOwner`, `crossOrgAccessIsDeniedBeforeAnyDbHit`, `duplicateAliasCreateIsConflictNotAdoption` |
| Assert the *negative* too | `then(userProvisioning).should(never()).provision(any())` — proving the denial happened *before* the side effect is the whole point |
| Every security rule and every fixed bug gets a test that fails without the fix | |
| Class javadoc says what contract the class pins and which sibling test complements it | `OrgRbacApiTest` ↔ `OrgRbacAuthorityTest` |
| Contract tests are their own thing | `EnvelopeContractTest`, `ProblemDetailContractTest`, `SecurityContractTest`, `CursorPaginationContractTest`, `FlywayBaselineTest` — extend these when the contract changes |

Docker notes: pre-pull images before container-heavy runs (image-pull storms have crashed
constrained Docker VMs); `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` is defaulted in `build.gradle.kts`.
Keep the test Hikari pool small — many cached contexts share one container.

---

## 11. Naming & code style

| Kind | Convention | Example |
|---|---|---|
| Port | the capability, no `I`/`Impl` prefix noise | `UserProvisioning`, `AuditLog`, `FileStorageProvider` |
| Adapter | `<Port>Impl` only when the port name is already the best name | `OrgAuthorizationImpl`, `AuditLogImpl` |
| Remote adapter | `<System><Thing>Gateway` | `KeycloakUserAdminGateway`, `KeycloakOrgAdminGateway` |
| Service | `<Aggregate>Service`, package-private | `MemberService`, `RoleService` |
| Guard / policy | says what it prevents | `PermissionEscalationGuard`, `ProvisioningGateFilter`, `SafeOutboundUrl` |
| Config props | `<Area>Properties` record | `WebhookProperties` |
| Event | past tense, subject first | `MembershipRoleChanged`, `OrganizationStatusChanged` |
| Audit action | dotted `module.verb_phrase` | `organization.member_role_changed`, `identity.user_provisioned`, `platform.impersonation_started` |
| Wire codes | `SCREAMING_SNAKE` for `ErrorCode`, `colon:separated` for permissions, `dot.separated` for webhook event types | |

Method names state the outcome, not the mechanism: `require(...)` throws if absent, `find…` returns
`Optional`, `ensure…` is idempotent get-or-create, `guard…`/`require…Holds` throws on violation.

### Comment voice — this matters here

Comments explain **why**, never what. Dense, precise, no filler, no ceremony headers.

Write comments for these, and only these:

- a decision whose alternative looks more obvious (`// Strict id equality: … an alias branch would let a token scoped to org A satisfy the scope check for org B`);
- an ordering or boundary that is load-bearing (`// Keycloak calls run OUTSIDE any local transaction`);
- a trade-off knowingly accepted, with its failure mode (`// … FAILS CLOSED (access lost, never gained), which is why this is left as a narrow window`);
- a number with a reason (`// Timeouts well below app.notification.delivery.stale-lock — JavaMail defaults to infinite`);
- a deliberate omission (`// Who deleted it is deliberately not a column here`).

Class javadoc states what the class **is** and the one thing a reader would otherwise get wrong.
`SoftDeletableEntity`, `ApiPermissionEvaluator`, `PlatformRole` and `MemberService` are the models —
read one before writing yours.

Never write `// getter for name`, `// loop over items`, or a comment restating the line below it.

---

## 12. Universal principles, compressed

One line each. They are not this repo's differentiators; violating them is still a review comment.

- **SRP**: a class you cannot name without "and" is two classes.
- **OCP**: extend via a new implementation of an existing port (`NotificationChannelSender`), not by
  editing a switch.
- **LSP**: a subtype must not strengthen preconditions — see the `SoftDeletableEntity` contract.
- **ISP**: ports stay small; `OrgAuthorization` is two methods.
- **DIP**: depend on the port, own the impl. §2.3 is the concrete form.
- **Composition over inheritance**: inheritance here is only for JPA mapped superclasses.
- **Immutability**: records for DTOs, events, properties, cursors; `List.copyOf` / `Set.of` /
  `Collections.unmodifiableList` on anything returned.
- **Constructor injection, final fields**, no field injection (ArchUnit-enforced), no setter
  injection.
- **Encapsulation**: no setters on entities; package-private by default; the S3/DuckDB/Keycloak SDK
  types never leave their module.
- **Null**: `Optional` for "may be absent" returns; never an `Optional` field or parameter.
- **Fail fast** on programmer error (`IllegalStateException` in `Role.reconcileSystemPermissions`),
  **fail closed** on authorization, **fail loudly** in migrations.
- **DRY with judgement**: `PlatformRole.isPlatformRole` exists so three role names are not re-listed
  at each site and one drifts. Duplicating a two-line mapper is cheaper than a wrong abstraction.
- **YAGNI**: no speculative interface with one implementation, unless it is a documented seam
  (`AnalyticsEngine`, ADR 0006).

---

## 13. Documentation duties

| You changed | You must also update |
|---|---|
| Module structure, a new module | `docs/ARCHITECTURE.md`, `./gradlew exportModulithDocs` (commit `docs/modulith/`) |
| An event or a consumer | `docs/EVENTS.md` (both tables) |
| A public HTTP surface | `./gradlew exportOpenApi`, and the endpoint catalogue in `docs/SRS.md` §4.6 + `docs/LOCAL_ACCESS.md` |
| A table, column, index or migration | `docs/DATA_MODEL.md` — the per-table reference **and** the migration history in its §6 |
| Behaviour anyone could state as a SHALL | `docs/SRS.md` — a requirement row with a stable ID and the test that verifies it, plus its §8 traceability row |
| A decision with alternatives worth recording | a new `docs/adr/000N-*.md` in the existing voice: Context / Decision / Why / Consequences |
| A deliverable whose gate passed | `docs/CHECKLIST.md` |
| A doc added, renamed or retired | `docs/README.md` (the index); retired docs move to `docs/archive/` with a superseded-by header |
| Anything with a plan doc | `docs/IMPLEMENTATION_PLAN.md` is the living plan — plan first, then code |

---

## 14. Review checklist

Run top to bottom on every change.

**Boundaries**
- [ ] Nothing imports another module's `internal`; `ApplicationModules.verify()` passes.
- [ ] New cross-module need is a port in the consumer's world, impl in the owner's.
- [ ] New classes are package-private unless something forces otherwise.
- [ ] No new compile dependency from `shared` into a business module.

**HTTP**
- [ ] Controller returns `ResourceObject` / `WindowedResult`; no hand-built `ApiResponse`.
- [ ] Failures throw a typed `ApiException` with a client-safe `detail` and an `ApiSource`.
- [ ] No new `ErrorCode` renames; additions only.
- [ ] New collection endpoint: stable unique `Sort`, `page.scrollPosition(SORT)`, no totals/offset.
- [ ] Caller-supplied outbound URL passes `SafeOutboundUrl`.

**Data**
- [ ] Correct base class (`BaseEntity` / `AggregateRoot` / `SoftDeletableEntity`).
- [ ] Soft-deletable entity carries **both** `@SQLDelete` (with `and version = ?`) and `@SQLRestriction`.
- [ ] Every unique constraint on a soft-deletable table is a partial index `where deleted_at is null`.
- [ ] New soft-deletable table is added to `SoftDeletePurgeJob.PURGE_ORDER`, children before parents.
- [ ] Native SQL touching a soft-deletable table filters `deleted_at` itself — `@SQLRestriction` does not apply.
- [ ] Migration is the next free `V<n>__`, never edits an applied one, no cross-module FK, headed by a why-comment.
- [ ] `@Transactional` is on a service method reached **through the proxy** (no self-invocation).
- [ ] Remote/Keycloak calls are outside the local transaction, and each step is idempotent.

**Security**
- [ ] Correct axis: `hasRole('platform-*')` vs `hasPermission(#orgId, 'organization', '…')`; no role bypass added.
- [ ] Anything durable keys on `subject`, not username.
- [ ] Any grant path passes `PermissionEscalationGuard`.
- [ ] Ambiguous states deny. Cross-tenant access is denied before any DB hit.
- [ ] No secret, token, or body in a log line.

**Impersonation** (§5.5 — skip only if the change touches none of the filter, the port, `CurrentUser`,
`AuditLogImpl` or the session module)
- [ ] The impersonated principal still holds **zero** authorities; no code path grants one.
- [ ] `audit_log.actor` is still `accountableSubject()`, and nothing else on the request switched to it.
- [ ] Anything a session may reach is re-decided per request, not read from the row at open time.
- [ ] `ImpersonationFilter` is still `@Order(-2)` and still restores the context in a `finally`.
- [ ] No new write on the target's behalf (the gate uses `peek`, never `authorize`, under a session).
- [ ] A new guardrail went into `ImpersonationService`, not the filter, and has a test that fails without it.
- [ ] `app.impersonation.enabled=false` still **refuses** (403 naming the switch) rather than removing
      the routes or ignoring the header.

**Events & background work**
- [ ] New event is a record in the API package with `occurredAt`; `docs/EVENTS.md` updated.
- [ ] Listener with side effects is idempotent via `EventInbox`.
- [ ] Deletes publish their event explicitly.
- [ ] New queue: `skip locked` claim, fenced status updates, stale-lock reclaim, backoff, dead-letter.
- [ ] Every remote call has a timeout shorter than the lock protecting it.

**Tests**
- [ ] Extends `AbstractIntegrationTest`; real containers; no H2, no mocked repository.
- [ ] Test name states the rule; the negative case is asserted too.
- [ ] Every security rule and every fixed bug has a test that fails without the change.

**Craft**
- [ ] Comments explain why; no comment restates its code.
- [ ] No Lombok, no field injection, no `System.out`, no generic exceptions.
- [ ] Config is `app.*` + `${ENV:default}` bound to a properties record, with a why-comment on any tuned number.
- [ ] Generated docs regenerated and committed if the structure or API changed.
