# ADR 0011 — The own-database hop: one deployment, many databases

- **Status:** Proposed · **Date:** 2026-08-09 · **Companion to ADR 0010** (§6 hop 1→2, §7 Phase 7). Supersedes nothing; 0010's tier table, routing seam and promotion machinery are the ground this stands on.
- Written against the source at HEAD `c6b1306` (Phases 0–6 shipped, suite 948/0, `silo-per-org` the default placement) and against **measured replication mechanics**: every claim in §7 about publications, slots, LSNs and reversal was executed on 2026-08-09 against two scratch `postgres:18.4-alpine` containers on one Docker network (`wal_level=logical`, `max_replication_slots=10`, `max_wal_senders=10`), not read out of the manual. Where a number appears, it was observed.
- **This document decides; builders implement.** It contains no code and claims no migration number. Where a new table is named, the implementing change takes the next free V-number per AGENTS §4.5 and moves the counter itself.

---

## 1. The decision

**One deployment routes to many databases.** `platform.tenant_placement.datasource_name` (V57 — written since Phase 4, read by nothing) becomes the routing key: `TenantRoutingDataSource` resolves an organization's route to **(schema, datasource)**, borrows from the named pool, and sets the `search_path` exactly as it does today. `primary` — the default in every existing row — is the platform database. A tenant whose row names anything else is served, byte for byte the same code path, out of another database entirely.

**What moves is the tenant tier and nothing else.** The remote database holds the tenant's `t_<32hex>` schema, `ext`, `no_tenant`, and a deliberately minimal `platform` schema (§5). The person graph, the catalogs, the routing registry, the queue signals and every coordination table stay on primary. This is 0010 §2's boundary made physical: the same 28-table tier that `pg_dump -n` proves restorable is what logical replication carries across.

**What this hop actually changes is the consistency model, which is why it gets its own ADR.** Inside one database, "platform and tenant rows read in one `@Transactional` method" was one snapshot. Across two databases there is no snapshot, no XA, and no pretending: every read path that crosses the boundary gets an explicit staleness contract (§2), every piece of infrastructure gets an explicit home (§5), and the one fact that may never be served stale is written down with the mechanism that enforces it.

**Scope honesty, up front (expanded in §9):** Phase 7 is one deployment, many databases — every port below still resolves in-process, and "the platform is unreachable" means the primary *database* is unreachable. HTTP adapters for a jar that runs without the platform database are Phase 8's split. The port **contracts** recorded here are Phase 8's contracts unchanged; only the transport moves. Nothing in this document is provisional on that ground.

---

## 2. The consistency model: what `@Transactional` stops meaning

Today `PermissionResolver` walks `membership → org_role → role_permission` in the same transaction that resolved `external_organization → organization → person`. After the hop those sit in two databases; the walk is two transactions on two connections, and nothing fails — it just becomes able to read a person deleted a millisecond ago (0010 §6 item 1). The repair is not to chase consistency but to write the staleness contract per read path and enforce the one that may not have one.

### 2.1 The contract, per read path

| read path | authority | over the split | staleness bound | at the bound |
|---|---|---|---|---|
| `person-by-subject` (`PersonLookup`) | primary: `person`, `external_identity` | stale-while-unreachable | **PT15M hard ceiling** per entry, measured from that entry's last authoritative refresh | 503 + `Retry-After`, never 401/403 |
| `org-by-external-id` (`OrgLookup`) | primary: `external_organization`, `organization` | stale-while-unreachable | same ceiling, same measurement | same |
| `OrgAuthorization.permissions` | **the tenant's own database**: `membership`, `org_role`, `role_permission` | does not cross the boundary in Phase 7 — the data lives in the database serving the request | **never stale-while-unreachable, in any phase** (AGENTS §5.5) | unreachable authority = deny. There is no ceiling because there is no grace |
| Tenant routes (`TenantRoutes`) | primary: `tenant_placement` | **never extended past `route-ttl`** | 30 s (`app.tenancy.route-ttl`), unchanged | borrow fails → per-tenant 503. See §2.4 |
| Plan / entitlements (`PlanCatalog`, §6) | primary: `plan`, `plan_entitlement` | TTL cache + stale-while-unreachable | 60 s TTL; PT15M ceiling | entitlement checks **throw** → 503. Never fall through to "no plan" (§6) |
| Platform catalogs (`setting-values`, `feature-flags`, `translation-bundles`, platform `maintenance_window`) | primary | already GLOBAL caches (0010 §3.5) | their existing TTLs; stale-while-unreachable to the same PT15M ceiling | settings/translations: last value or default. `feature-flags`: **a flag that cannot be read is off** — fail closed |

### 2.2 "Unreachable" is a connection answer, never a data answer

**Unreachable means the authority could not be asked**: a borrow from the primary pool failing (Hikari `SQLTransientConnectionException` after `connection-timeout`, connection refused, DNS failure) or a statement dying at the socket timeout. It never means an answer we dislike:

- **An authoritative "absent" is an answer and it replaces the cache entry immediately.** A deleted person resolves to nothing, and stale-while-unreachable must not resurrect them — the stale path is only reachable when no answer of any kind arrived. Miss this and the erasure pipeline un-erases people for fifteen minutes.
- **A query-shaped error (constraint violation, syntax, missing relation) is a bug and is rethrown.** Serving stale over a bug converts a loud failure into a silently wrong answer with a 15-minute fuse.

### 2.3 Staleness is a property of the entry, not of the outage

Each cached identity entry carries the instant of its last **successful authoritative refresh** (from the injected `Clock`). Age is measured from that instant; the ceiling compares age. An entry last confirmed 14 minutes before the outage began has **one minute** of grace, not fifteen — the ceiling bounds "how long since this fact was known true", which is the only reading under which "a revoked identity is honored within 15 minutes" is actually a promise. At the ceiling the entry is treated as absent, and the default-deny posture the ports already have takes over.

**What the caller sees at the ceiling: 503 + `Retry-After`, in the envelope, with the request id** — the `TenantSchemaFloor` shape, because it makes the same claim: *this system cannot currently answer for you; try again*. Not 403 ("you are known and refused" — false), not 401 ("your token is bad" — false), not `ACCOUNT_NOT_PROVISIONED` (telling a paying user they don't exist because a database is down is a support incident with a wrong root cause attached).

**Observability is part of the contract:** a counter of stale-served resolutions tagged by cache, a gauge of the oldest served age, a counter of ceiling denials; WARN once on entering stale service per cache, ERROR on the first ceiling denial, INFO on recovery. Silent stale service is how a 15-minute ceiling becomes a 15-minute mystery.

### 2.4 Identity may be stale. Placement may not. The asymmetry is the design

`TenantRoutes` already refuses to guess when the registry is unreadable, and this hop is where that refusal earns its keep — so it is **not** granted stale-while-unreachable. The tempting argument (routes change once in a tenant's lifetime, and a cutover cannot flip while primary is down because the flip is a primary write) fails at the edge: a flip that committed **just before** the outage, on a pod whose re-read then starts failing, would serve the old route past the TTL the promoter's freeze arithmetic depends on — and a misroute is 0010 §1's worst failure, while a refusal is a bounded 503. Identity staleness degrades who we think you are, bounded and metered; placement staleness silently writes your rows into a schema your `org_id` disagrees with. Availability loses that argument every time.

### 2.5 Writes during a primary outage

Tenant-tier writes on a healthy remote database proceed — that is the availability this hop buys. Platform-tier writes from those requests (§5's enumeration) fail loudly per call site: the null-org audit row, the usage row, the search-document upsert are each explicit platform-axis operations with their own failure story, and none of them silently drops into the tenant's database — the remote's deliberately minimal `platform` schema (§5.1) turns every unconverted call site into `relation "platform.x" does not exist`, a 500 with a request id, not a misroute.

---

## 3. The person graph does not come. Ever.

Restating 0010 §2.2 as a settled decision, because Phase 7 is where someone will try to "fix" it: the silo gets a **projection** through `PersonLookup` — id, names, status, one display contact — cached under §2's contract. Writes to `person`, `person_contact`, `external_identity` stay platform-only **forever**, because `uq_external_identity_subject_live` (one-login-one-human) and `uq_person_contact_verified_live` (one-verified-address-one-human) are platform-wide facts that no silo can enforce from where it sits. Two databases each holding "the" verified row for an address is not a replication lag problem; it is two systems both entitled to be wrong. A remote tenant that needs a person fact it does not have asks the platform and may get yesterday's answer; it never gets a pen.

---

## 4. The datasource registry

### 4.1 The vocabulary

```yaml
app:
  tenancy:
    datasources:
      analytics-eu:                     # the name tenant_placement.datasource_name selects
        url: ${TENANT_DS_ANALYTICS_EU_URL}          # jdbc:postgresql://…  required
        username: ${TENANT_DS_ANALYTICS_EU_USER}    # required
        password: ${TENANT_DS_ANALYTICS_EU_PASSWORD} # required; env indirection, never a literal
        pool:
          maximum-pool-size: 8          # default 8 — see §8 item 6 for the budget arithmetic
          minimum-idle: 0               # default 0 — a remote nobody routes to holds no connections
          connection-timeout: 5s        # default 5s — also the "unreachable" detector, §2.2
    identity-stale:
      ceiling: PT15M                    # §2's hard ceiling; lower is legal, higher needs this ADR reopened
    cutover:
      watch-window: P7D                 # how long the reverse stream runs after a flip (§7 step 9)
```

- **Names match `^[a-z][a-z0-9_-]{0,31}$`**, validated in the properties record's compact constructor (the house rule: dangerous config fails at startup, not at 04:00).
- **`primary` is reserved and may not appear in the map** — the platform pool is `spring.datasource`, and a second definition of it would be two truths about one database. Configuring it fails startup, naming the key.
- `tenant_placement.datasource_name` selects; `'primary'` (the column default) means the platform database. No placement row means pooled on primary, exactly as today.

### 4.2 A named-but-unconfigured datasource fails closed **per tenant, never per pod**

This is the Phase 5/6 lesson applied before the bug is written instead of after. `TenantFanOut.aHomeNobodyBuilt` already withholds one broken registry row from a sweep and reports it at ERROR rather than letting it fail the run for every other tenant; the `PlanCatalogGuard` repair exists because its first version fanned a per-tenant check out from a ready listener and converted one dangling row into a fleet-wide boot failure. The identical failure is one typo away here: a placement row naming `analytics-eu2` where config says `analytics-eu` **must not** throw at boot or in a shared code path. The decided semantics:

- **Request path:** route resolution to an unconfigured name is a per-tenant refusal — the same 503 + `Retry-After` the schema floor produces, memoized with the floor's own `RECHECK_AFTER` discipline so the registry is not hammered. Logged once at ERROR naming the organization, the row's name, and the config key that would fix it.
- **Fan-outs and jobs:** the home is withheld and reported, `aHomeNobodyBuilt`-style — never thrown out of a sweep, never silently skipped without a line.
- **Boot:** pools for every *configured* name are built eagerly (malformed config fails fast) with Hikari's initialization failure check disabled, so a remote database that is down at boot is that tenant's outage, not the deployment's. A configured name no placement row uses is a WARN, not an error — config may legitimately lead placement by a release.

### 4.3 The seam, named for builders

- `shared.persistence.TenantDataSourceProperties` — the `@ConfigurationProperties` record for the block above; reserves `primary`, validates names, defaults pool numbers.
- `shared.persistence.TenantDataSources` — name → pool registry. `poolFor(name)` throws a typed exception the request path maps to the per-tenant 503; never returns primary for an unknown name.
- `shared.tenancy.TenantRoutes` — the memoized `Route` grows a `datasourceName`; a new `routeOf(orgId)` returns the pair, `homeOf` keeps its meaning and callers. The TTL, the refusal-to-guess and the promoter's freeze arithmetic all apply to the pair, unchanged.
- `TenantRoutingDataSource` — resolve route → pick pool → borrow → `SET search_path`, in that order. Resolve-before-borrow stays load-bearing for the same deadlock reason its javadoc states today.
- `TenantMigrationRunner` / `TenantSchemaMigrator` — `discoverFleet` already reads placement; each fleet entry gains the pool its schema is reached through. Per-schema Flyway history lives with the schema, on whichever database holds it.

---

## 5. Which infrastructure lives where

Five platform-tier tables are coordination state, and the naive answer — "platform tier means primary" — is right for four of them and impossible for the fifth. Decided:

| table | home for a remote tenant | why |
|---|---|---|
| `event_publication` | **the tenant's database** — fresh, empty, never copied | The only one with a transactional coupling: the outbox row commits **in the same transaction** as the aggregate change it describes, or it is not an outbox. There is no cross-database atomic write, so the registry follows the transaction. It is self-consistent because listeners run on the event's own tenant axis (0010 §3.2), so publish, consume and complete all resolve to the same database. |
| `shedlock` | primary only | One fleet, one scheduler. A second lock table is 0010 §6's "two deployments, same lock names, one silently runs nothing" — inside one deployment. |
| `idempotency_key` | primary only | Per-principal, claimed by a filter at `@Order(1)` — person-scoped, no tenant coupling. `IdempotencyStore` pins its own platform-axis borrow instead of riding the request's tenant connection. |
| `queue_signal` | primary only | Its own tier note says it: the claim is a search for work *before* a tenant is chosen, so the table that answers "which tenant" cannot follow a tenant. **This home costs an invariant — §5.2.** |
| `tenant_freeze` | primary only | Every pod and the promoter must read one truth, and a freeze living in the database being moved would be moved with it. |

**The cost, stated because the hint is real:** every remote-home queue cycle now touches two databases — claim the tenant from primary's `queue_signal`, run the claim query on the tenant's own database, release back to primary. And a primary outage pauses **remote** tenants' queues and jobs even while their own databases are healthy, because the signals, the locks and the freezes all live on the side that is down. That is the price of one coordination brain, and it is the right price: the alternative is coordination state that can disagree with itself across databases, which is not a cheaper failure, just a quieter one.

### 5.1 The remote `platform` schema is minimal so that mistakes are loud

The remote database gets a `platform` schema containing **exactly `event_publication`** (plus the tenant sequence's Flyway history beside the tenant schema). Not a full empty copy. This is a deliberate tripwire: after the hop, any platform-qualified statement executed on a connection borrowed under a remote tenant's axis is a statement running against the wrong database, and against a full empty copy it would *succeed* — an audit row, a search-document upsert, a usage row landing in a `platform` schema nobody reads, silently, forever. Against the minimal schema it is `relation "platform.audit_log" does not exist`: a 500, a request id, a call site to fix. Every such call site must be converted to an explicit platform-axis operation. The conversion makes each one a **separate transaction on another database**, and each therefore names its own partial-failure story — the audit row that survives its action's rollback is accepted and documented at the call site, the same trade `MemberService.remove` already documents for Keycloak.

**The earlier draft of this paragraph listed five call sites and called them "the known set". That list was wrong in method, not merely in contents, and it is replaced here by the derivation that produces it.** A hand list is invisible to the two mechanisms that hide most of these, and re-writing one is how the gap comes back. The list is the output of four sweeps, and a call site qualifies when *any* of them names it:

| sweep | what it finds | what it CANNOT see |
|---|---|---|
| **A. literal** | every SQL string literal matching `platform.<table>` in `src/main/java`, comments stripped | anything whose schema is built at runtime |
| **B. mapping** | every `@Table`/`@CollectionTable` with `schema = "platform"`, then its repository, then its callers | JDBC |
| **C. interpolated** | every statement whose schema comes from `SplitTables.homeOf` / `SplitTables.homes()` / `MappedTables.qualified` — these emit `platform.` *or* `t_<32hex>` at runtime, so sweep A never sees them | — |
| **D. transitive** | every caller of a platform-tier *component* (`QueueSignals`, `EventInbox`, `IdempotencyStore`, `AuditLog`, `SearchIndex`, …), because the offending statement is one frame down and grep-invisible at the call site | — |

Then, per hit, two questions — and the second one is new with this ADR: **(1) can a caller be on an org axis?** and **(2) can the schema this names be in a different database than the connection it is issued on?** Sweep C's hits fail on (2) in *both* directions: a platform-axis job writing a remote tenant's row names `t_<32hex>` on primary, and a remote tenant's request writing a null-org row names `platform.audit_log` on the remote. `SplitTables.homeOf` answers "which schema" and has been half an address since this ADR shipped; `CrossDatabaseWrites.runInHomeOf` is the other half, and it is a no-op — same connection, same transaction — whenever the row's home and the caller's axis are in the same database, which is every call on every deployment with no remote datasource configured.

Sweep D is what the five-item list missed entirely, and it is where the worst of them was: **`IdempotencyStore`.** `CurrentUserFilter` (`@Order(-1)`) pins the caller's organization before `IdempotencyFilter` (`@Order(1)`) runs, so `claim` has been writing `platform.idempotency_key` over the request's *tenant* connection. On a remote tenant that is not a degraded write, it is a 500 before the controller is ever reached: **every idempotent POST to that tenant fails.** The same sweep also names `QueueSignals.raise` from all four tenant-axis enqueues, `WebhookDeliveryQueue.releaseSignal` (the release runs under the tenant pin the tenant-tier half requires, so the platform half went to the wrong database and the lease was never handed back), and `EventInbox.recordIfNew` from `WebhookDispatcher` — which is the one consumer that deliberately runs on the event's org axis rather than the platform axis, exactly as ADR 0010 §3.2 requires of it.

**The derivation was run, and it changes the shape of the problem — not merely its length.** Sweeps A–D over `src/main/java` produce 43 files holding a `platform.<table>` literal, 20 entities mapped `schema = "platform"`, and 5 sites interpolating a home at runtime. Then the axis question, and this is where the "convert the call sites" framing turns out to have been sized against the wrong denominator: **`CurrentUserFilter` pins `TenantContext.set(orgId)` for any caller whose credential names an organization, on _every_ route — not only under `/orgs/**`.** So the org axis is not a property of tenant-scoped endpoints; it is the ambient state of an authenticated request. `GET /me/organizations`, `/me/profile`, `/me/notifications`, `/me/devices`, a settings read, a translation-bundle miss, a plan lookup — every one of them touches a `platform.` relation over the tenant's connection, and on a remote tenant every one of them is `relation … does not exist`.

**Decided, so the next reader does not re-derive it as a surprise.** §5.1 converts the *infrastructure* seam — the tables whose whole job is coordination, where the failure is silent-loss rather than a 500 — and that set is closed and named: `idempotency_key`, `queue_signal`, `event_inbox`, `notification_delivery`, `search_document`, `org_membership_index`, both halves of `audit_log`, both halves of `integration`/`integration_setting`, the tenant half of `exchange_job`, and the `api_usage_daily` summary. Two of those are worth naming individually because the derivation found them and a hand list would not have. `integration` fails in the direction that is hardest to see: `NotificationDeliveryWorker` is pinned to PLATFORM by necessity (its status writes are platform-tier) and resolves a REMOTE org's SMS provider, so it names `t_<32hex>.integration` on a primary connection and every message to a tenant with a provider of its own dies on a background thread. And `org_membership_index` is the one whose ordering had to be chosen rather than inherited: its writes now commit on primary *before* the `membership` row they mirror, which leaves only the benign direction open — a switcher entry with no seat, which denies on arrival — never a seat its owner cannot see. `shared.tenancy.CrossDatabaseWrites` is the mechanism, and its one design rule is that it does nothing when nothing is wrong: it compares the caller's DATABASE with the target's — never their axes, because a pooled tenant's axis is not the platform axis while its rows are in the same database — and hops only when they differ. On a deployment with no remote datasource configured that branch is never taken, so the shipped behaviour and every atomicity guarantee behind it are byte-for-byte unchanged.

**The rest of the surface is NOT a longer list of the same work, and pretending it is would be the second version of the mistake this section already made once.** What remains is dominated by *reads* of slow-moving platform state on the request path — identity (`person`, `external_identity`, `external_organization`, `organization`), catalogs (`setting`, `feature_flag`, `translation`, `plan`), person-scoped rows (`person_profile`, `person_preference`, `in_app_notification`, `user_device`), the platform half of `maintenance_window`, `api_key` and its prefix reservation, `sla_policy`, and the `api_usage_daily` summary — and wrapping ~30 request-path components in a per-statement borrow is not a design, it is thirty places to forget. §2 already answers four of them properly, with ports and a staleness contract, and **that is the shape the remainder takes: a port with a declared authority, a declared staleness bound and a declared behaviour at the bound, not a `runOnPlatform` around a repository call.** Phase 7 is complete when the infrastructure seam is converted (it is) and the read surface is reachable; a remote tenant does not serve a full request until the ports in §2 and §6 cover it, which is why §9's scope sentence is load-bearing rather than throat-clearing. The tripwire is what makes this safe to stage: an unconverted read is a 500 with a request id, never a quiet answer from an empty copy.

### 5.2 `queue_signal`'s same-transaction rule: kept where it is possible, abandoned where it is not

`QueueSignals.raise` carries a rule its own javadoc calls load-bearing: **the signal is written in the same transaction as the rows it indexes.** The reason is stated as a failure, not a preference — commit them apart and a worker can claim the tenant in the window between, find nothing, and `release(dueAt = null)` **deletes the signal for rows that then commit.** That work is queued, no error is raised anywhere, and no poll ever looks at it again. Every enqueue path in all three queues wraps the two writes in one transaction to keep it.

**Across two databases the rule is not weakened, it is unachievable.** A remote tenant's `webhook_delivery` and `exchange_job` rows commit on that tenant's database; `queue_signal` commits on primary (§5, and the reason stands: a claim searches for work before a tenant is chosen). Two commits, no snapshot, and this platform refuses XA. §5.1 named five call sites to convert and did not notice that this one is not a conversion at all — it is a guarantee with nowhere to go. Deciding it now, because a silent decision here is exactly the shape of loss the rule was written to forbid.

**Decided, in three parts.**

1. **`queue_signal` stays on primary.** Moving it into each tenant's database would restore the transaction and destroy the claim: `claim` is one indexed statement that picks the longest-waiting *due tenant across the whole fleet*, and that is where round-robin fairness comes from. Per-database signals turn one statement into one poll per configured datasource, make "longest waiting" a merge across N servers with N clocks, and give a primary-side worker no way to see a remote tenant's backlog without asking every remote first. The fleet-wide sweep is the thing this table exists to be. It does not move.

2. **The rule is kept wherever it can be kept, which is every tenant co-located with primary — i.e. every tenant on every deployment today.** `raise` compares the caller's *database* with primary's, not the caller's axis, and when they agree it writes inline exactly as it always has. The distinction matters: a pooled tenant's axis is not the platform axis, but its rows and the signal are in one database, so nothing about it changes. On a deployment with no remote datasource configured this branch is taken on every enqueue, and the shipped guarantee is the shipped guarantee.

3. **For a remote tenant the raise is deferred to `afterCommit` and issued in its own transaction on primary.** Not "raise it separately" — raise it *after*, and the ordering is the whole of the decision. Raising before the rows commit reproduces the original failure verbatim and needs only a concurrent poll to provoke it, which is the normal state of a running fleet. Raising after makes that same race self-healing: a probe may delete the signal, and the raise that follows re-creates one over rows that are now visible. What survives is a strictly smaller hole — **the process dying between the rows' commit and the signal's** — and unlike the first it cannot be provoked by load.

**What is lost, stated so nobody discovers it in production.** For a tenant on another database, queued work is invisible to every poll between its own commit and the signal's: sub-millisecond normally, and *indefinitely* if that pod dies in between. There is no error, no log line and no metric at the moment it happens, because nothing observed a failure — which is precisely why the repair cannot be "operators will notice".

**The repair: a reconciling sweep, on the `sweepSearchResidue` precedent.** `SoftDeletePurgeJob.sweepSearchResidue` exists because `search_document` had no delete path, so residue accumulated forever and something had to go looking for it. This is the same shape with the polarity reversed — not a projection row whose source is gone, but *work whose projection row was never written* — and it gets the same answer. Each affected queue gains `reconcileSignal(scope)`: it asks the queue's **own** remaining-work expression (the one `releaseSignal` already computes, extracted so the two cannot drift) and, when there is claimable work, issues `insert … on conflict do nothing` against `queue_signal`. Three properties are not negotiable:

- **`do nothing`, never `do update`.** A scope that already has a signal may be leased by a worker mid-batch; pulling its `due_at` forward or clearing its lease would void that worker's release and park the tenant behind a lease nobody holds — the starvation this table exists to remove, reintroduced by its own repair. Reconciliation may only ever create what is missing.
- **Remote homes only, and taken from `TenantFanOut`.** A co-located tenant cannot be in this state, so visiting it is pure cost; and the fan-out already subtracts frozen and half-built homes, so the sweep cannot announce work in a schema a cutover is copying.
- **It runs on an idle poll, rate-limited (5 minutes), per instance.** The idle poll is the only moment the fleet has spare capacity and the only moment "nothing is due" is a claim worth checking. Per instance rather than under ShedLock because the write is idempotent and a repair that depends on the scheduler being healthy is a repair that is absent during the incident that produced the residue.

**Two things this does not cover, named rather than implied.** `notification_delivery` needs none of it — the rows and the signal are both platform-tier and both on primary, so their transaction survives the hop intact; it is the one queue of the three for which nothing is lost. And `EventInbox` has the mirror-image version of the same problem: its dedup claim can no longer commit with the side effects it de-duplicates, so a failed cross-database fan-out would leave the message claimed and its work absent, and the at-least-once redelivery would be swallowed. The claim is therefore released on failure (`EventInbox.forget`), unconditionally — for a co-located tenant it matches zero rows because the claim already rolled back, and a failure path that must first ask which topology it is in is a failure path that will ask wrong.

---

## 6. `PlanCatalog`: the fourth port, and what a stale plan may cost

`EntitlementResolver.resolve` reads `org_subscription` (tenant tier, moves) and joins `plan` through `PlanRepository` (platform catalog, stays) — in-process today, impossible across databases without a seam. AGENTS §5.2 and 0010 hop 3→4 already name this port as destiny; Phase 7 is where it gets built, because Phase 7 is when the join first actually breaks.

**The contract:** `subscription.PlanCatalog` — in the module's **API package** (the `apikeys.ApiKeyReminting` precedent: the capability crosses a boundary, the data stays owned), implemented by `subscription.internal` against `PlanRepository` today and by an HTTP adapter in Phase 8 with no contract change. Two methods, returning an immutable `PlanSnapshot` (id, code, entitlement map): `plan(UUID planId)` and `planByCode(String code)` — the second exists because the FREE fallback is part of the resolution semantics, not a caller convenience. Both return `Optional`; absence is an answer (§2.2).

**Read semantics:** plans are slow-moving catalog data. A declared **GLOBAL** cache (`plan-catalog`, registered in `CacheRegistry` like everything else), 60 s TTL, keyed evict broadcast on any plan mutation through the existing `SubscriptionService` paths. Stale-while-unreachable applies with the PT15M ceiling.

**What a stale answer costs, named so nobody discovers it in production:** entitlements are quotas and features, not identity — a plan downgraded at minute zero can be exercised at its old limits for up to the TTL (60 s normally, PT15M under an outage), and an upgrade is invisible for the same window. That is acceptable and bounded. **What is not acceptable is the fall-through:** `EntitlementsImpl.limitOf` returns null for an absent entitlement and `requireWithinLimit` does nothing with a null limit — so "no plan" reads as **every quota unlimited**, which is the `PlanSeeder`/`PlanCatalogGuard` lesson wearing a new hat. Decided: at the ceiling, `PlanCatalog` **throws**; entitlement checks fail as 503; a plan that cannot be known is never a plan with no limits. Fail closed on quota, exactly as on authorization — the failure just wears a different status code.

---

## 7. The cutover sequence, with the mechanics measured

### 7.1 Row-filtered publications are rejected, and the measurement is why

0010 §6 sketched `CREATE PUBLICATION … FOR TABLE ticket WHERE (org_id = ?)` and asserted "every table has a primary key so default `REPLICA IDENTITY` suffices." **Measured: the publication creates cleanly — and the first `UPDATE` on the table fails.** On 18.4, with the filter on `org_id` (not in any PK) and default replica identity:

```
ERROR:  cannot update table "ticket"
DETAIL:  Column used in the publication WHERE expression is not part of the replica identity.
```

Three properties of that failure make it a fleet-stopper, all observed:

1. **It fires at DML time on the publisher, for every row of every tenant** — an update to org B's ticket fails though the filter names org A. Creating the publication is what breaks the fleet, before any subscription exists, before any byte has moved.
2. `DELETE` fails identically; `INSERT` still succeeds — so a canary that only inserts would come up green.
3. Both repairs work and both are taxes: a unique index on `(id, org_id)` + `REPLICA IDENTITY USING INDEX` (measured working; a new index on every tenant table), or `REPLICA IDENTITY FULL` (measured working; full old-row images in WAL for **every pooled tenant's updates** for the whole window).

**Decided: the hop runs silo-to-database only, and the publication is schema-level.** `silo-per-org` is the default placement and hop discipline is 0→1→2 — a pooled tenant promotes to its silo first (a promotion, machinery shipped and proven) and only a silo cuts over. `CREATE PUBLICATION p_<schema> FOR TABLES IN SCHEMA t_<hex>` carries no row filter, so default PK replica identity genuinely does suffice — measured: publication created, updates and deletes on live traffic unaffected, initial sync online (1,000 `ticket` + 299 `ticket_message` to state `r`), a post-sync insert and update replicated inside the 2 s poll that watched for them. The row-filter path stays in this document only as the reason it is refused.

### 7.2 The sequence

Each step names its failure story. Steps 1–4 are online; the freeze begins at step 5.

1. **Build the destination.** The migration runner creates `t_<hex>`, `ext`, `no_tenant` and the minimal `platform` (§5.1) on the named datasource, by the same code that builds any silo. *Failure: nothing routed, nothing frozen — delete and retry.*
2. **Record the cutover as a queryable fact.** A new platform table (`tenant_cutover`: org, target datasource, state SYNCING → CUT → WATCHING, timestamps — the V57 doctrine: partial-fleet state is a `select`, not an incident; the implementing change takes the next free V-number). Placement stays **ACTIVE** throughout the sync — the tenant is fully served from primary and jobs must keep running; only the freeze window changes any serving state. **While a cutover row exists, the tenant-migration runner refuses this tenant** — see 7.3 for the measured reason.
3. **Publish and subscribe.** Publication on primary (7.1); subscription on the destination with `copy_data = true`; wait for every `pg_subscription_rel` at `r`. *Failure: `DROP SUBSCRIPTION` drops the remote slot with it (observed as the `NOTICE`), delete the cutover row, nothing else happened.*
4. **Watch lag, not calendar.** The stream is caught up when `confirmed_flush_lsn` tracks the head. Sizing note from measurement: the walsender decodes the **whole** WAL stream and confirms past other tenants' records (observed: `confirmed_flush_lsn` advanced over org-B-only traffic), so catch-up scales with fleet-wide write volume, never with the tenant's size.
5. **Freeze** — identical to promotion: HTTP RESTRICT window + `tenant_freeze` + queue pause, bounded by `expires_at`; then the settle drain.
6. **Capture the LSN once, then wait for it.** `pg_current_wal_lsn()` read **once** after the last frozen-tenant write; wait `confirmed_flush_lsn >= captured`. Measured: 89 ms to converge on an idle box, while the live head moved past the captured value under unrelated traffic — which is exactly why the wait must be against a *captured* value: a wait for equality with the live head chases every other tenant's writes and can starve indefinitely on a busy fleet. *Failure: still frozen, nothing flipped; abort = unfreeze, stream keeps running, retry the freeze later.*
7. **Verify, then flip.** Per-table row counts and checksums across the two databases (the promotion discipline); then one UPDATE: `datasource_name = '<name>'` — `schema_name` unchanged, state ACTIVE, cutover row → CUT. *Failure before the flip: abort as step 6. The flip itself is one row, atomic.*
8. **Evict, and honor the route TTL.** `TenantPromotionCaches.evictAfterPlacementFlip` + `TenantRoutes.forget` fix the flipping process; every other pod heals on `route-ttl`, so the freeze holds **one full `route-ttl` after the flip** — the same arithmetic, now protecting the datasource half of the route as well as the schema half.
9. **Reverse-replicate BEFORE unfreezing.** Still inside the freeze: drop the forward subscription (loop prevention first — measured that the drop removes its slot on the publisher), publish the destination's schema, subscribe from primary **`WITH (copy_data = false)`**. Measured, both halves: the default `copy_data = true` reversal immediately storms `duplicate key value violates unique constraint` on every synced table's PK (the origin already holds every row — a tablesync into it is a collision by construction), and with `copy_data = false` a far-side write appeared on the origin within the 3 s poll that watched for it. **Only then unfreeze.** The ordering is the rollback guarantee: the first write the destination ever serves is already flowing back, so there is no instant at which flipping `datasource_name` back loses a committed write. That is "the last cheap reversal", and it is cheap *because* of this ordering, not by nature.
10. **Watch, then decommission.** The reverse stream runs for `app.tenancy.cutover.watch-window` (default P7D); rollback during it is: freeze briefly, flip back, evict, wait `route-ttl`, unfreeze — the reverse stream has kept origin current the whole time. Decommission: drop the reverse subscription, drop the origin schema (one schema per transaction — 0010 §5.12), drop the forward publication, cutover row deleted. **The decommission trap, measured:** `DROP SUBSCRIPTION` with an unreachable publisher fails (`could not connect to publisher when attempting to drop replication slot`); the escape is `ALTER SUBSCRIPTION … DISABLE` → `SET (slot_name = NONE)` → `DROP` — and the orphaned slot left on the far side **pins WAL there until someone drops it by hand**. The runbook step is not optional; an orphaned slot is a disk-full incident on a timer.

### 7.3 Migrations during the window: measured, and forbidden

DDL does not replicate, and the failure is worse than "the new table is missed." Measured end to end: a table created in the published schema mid-window, plus one insert, **crash-loops the destination's apply worker** every ~5 s (`logical replication target relation "…" does not exist`), the slot goes inactive, `confirmed_flush_lsn` freezes, and WAL retention on primary grows until someone notices. Then the trap inside the trap: creating the missing table on the destination **heals the stream but silently skips that table's rows** — the stalled ticket insert queued behind it arrived in order, the new table's own row did not, because the relation is not in the subscription until `ALTER SUBSCRIPTION … REFRESH PUBLICATION`, which tablesyncs it and was measured to deliver the missing row. So: green stream, complete-looking tenant, one table quietly short. **Decided:** the runner refuses the tenant for the life of the cutover row (step 2); the repair for a migration that lands anyway is migrate-the-destination-then-REFRESH, in the runbook with both measured failure signatures. Consequence stated plainly: a cutover should not span a release that raises `MIN_TENANT_SCHEMA_VERSION` — the frozen tenant falls below the floor and 503s, which is the floor working, and still an outage someone scheduled.

---

## 8. What gets worse

1. **Primary becomes an availability dependency for tenants whose own database is healthy.** Identity resolution, routes, plans, queue signals, locks and freezes all live there. §2 bounds the identity half; nothing bounds the coordination half — a primary outage pauses every remote tenant's queues and background work. Stated, accepted, and the reason §5's table exists in writing.
2. **Every queue cycle for a remote home is two databases.** Claim from primary, work on the remote, release to primary — cross-database latency inside loops that were single-digit milliseconds. Every fan-out job's `RUN_DEADLINE` is re-derived *again* when the first remote home ships; the Phase 3 derivations assumed one database.
3. **A tenant's truth now spans two databases, and so do its backups.** The tenant's rows are remote; its routing row, person graph and catalog are primary. A point-in-time restore of either side alone is not a point-in-time restore of the tenant. The DR runbook gains a section, and "restore the database" stops being one sentence.
4. **Replication lag becomes an operator metric with a consequence attached** — during every window, `confirmed_flush_lsn` distance is the difference between a seconds-long freeze and a stuck one, and an inactive slot (7.3) is a disk-full incident on primary with a fuse nobody set.
5. **The audit trail forks in time.** Platform-half audit rows from remote-tenant requests are separate transactions (§5.1): ordering between a tenant's rows and its platform audit rows is no longer transactional, and an audit row can survive its action's rollback. Per-call-site documentation is the mitigation, not a fix.
6. **Connection arithmetic.** Each named datasource is a pool per pod: N remotes × 8 defaults × pods, plus primary's 16 — the budget that used to be one number is a spreadsheet. `minimum-idle: 0` keeps unused remotes free, and §10 Q3 caps the count until someone measures.
7. **The suite gets slower and the fixture gets heavier.** Proving any of this per ADR 0003 means a second real Postgres per relevant context — container startup, a second migration run, and a cross-database fixture in `testsupport` (a `TenantRemotes` sibling to `TenantSilos`).

**One thing gets better, and it is the point:** the blast radius of a tenant-tier incident — a runaway query, a bloated table, a vacuum storm, a compromised credential scoped to one database — stops at that database's edge. Extractability stops being a rehearsal artifact and becomes the shipped topology.

---

## 9. Scope honesty: Phase 7 routes, Phase 8 splits

Phase 7 is **one deployment, many databases**. `PersonLookup`, `OrgLookup`, `OrgAuthorization` and `PlanCatalog` keep their in-process implementations; what Phase 7 changes is which *database* each implementation reaches and under which staleness contract (§2). The port contracts recorded here — including the two that gain semantics in this document (`PlanCatalog`'s throw-at-ceiling, the identity ports' stale-entry model) — are the same contracts Phase 8's HTTP adapters implement; the wire adapters, the gateway route by `organization` claim, and the fresh Valkey/SeaweedFS roots are Phase 8 and are **not** blocked on anything here beyond these contracts holding. One consequence worth naming now so Phase 8 inherits it as doctrine rather than discovers it: in Phase 7 `OrgAuthorization` never crosses the boundary (the membership rows live in the database serving the request), so the split's §5.5 question first becomes *live* in Phase 8 — and its answer is already written in §2.1's table: never stale, no ceiling, no grace. That row does not get renegotiated when the transport changes.

---

## 10. Open questions, each with a default so silence is safe

**Q1. Secret material for remote datasources.** Default: `${ENV_VAR}` indirection per name, exactly as every other credential in `application.yaml`; a secrets-manager integration is a deployment concern, not this ADR's. Rotation = new env value + rolling restart; pools rebuild on boot.

**Q2. Version skew between databases.** Default: same Postgres major (18) required, probed by the migration runner at destination-build time and refused otherwise. Logical replication tolerates more; the operational surface of "it mostly works across majors" is not worth what it saves.

**Q3. How many named datasources before the pool arithmetic breaks?** Default: **10** per deployment until measured — at the defaults that is ≤80 remote connections per pod on top of primary's 16. The refusal lives in the properties record, loudly, like the silo ceiling in the promoter.

**Q4. Watch-window length.** Default: P7D. The reverse stream is cheap (one slot, the tenant's own write rate); the thing it insures against — a subtle serving defect on the new database discovered on day three — is not.

**Q5. Does an org-scoped `maintenance_window` row gate a cutover the way it gates a promotion?** Default: yes — the freeze mechanism is shared, and a second freeze vocabulary would drift from the first.

**Q6. Who runs cutovers?** Default: operator-initiated from a runbook, same as promotions (0010 §8 Q3), and no automation until ten manual cutovers have gone through the reverse path at least once — including one deliberate rollback at step 10, because a reversal nobody has rehearsed is a reversal that does not exist.
