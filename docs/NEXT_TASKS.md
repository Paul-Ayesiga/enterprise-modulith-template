# Next Tasks — handoff for the next agent

Phases 0–4 are done and gated (see [CHECKLIST.md](CHECKLIST.md)). This is the prioritized backlog.
Each task is self-contained; read this preamble first.

## How this repo works (read before touching anything)

- **Build/test:** `./gradlew build` — every gate is a real test. Infra-touching tests use REAL
  containers (ADR 0003): Postgres/Keycloak/SeaweedFS/Valkey via Testcontainers 2. No H2, ever.
- **Conventions that are law:** unified envelope with `meta.requestId` (+ RFC 9457 on
  `Accept: application/problem+json`); cursor pagination only (ADR 0002); no stack traces on the
  wire; constructor injection; modules own their data and talk through events; migrations in
  `src/main/resources/db/migration` (next free: **V8**).
- **Boot 4.1 / TC2 gotchas already discovered** (do not relearn): `docs/IMPLEMENTATION_PLAN.md`
  §10. Highlights: many Boot features moved to per-tech modules (`spring-boot-starter-flyway`,
  `spring-boot-webmvc-test`, `spring-boot-resttestclient`+`restclient`, no `starter-aop` —
  use `aspectjweaver`); Testcontainers 2 renamed artifacts (`testcontainers-postgresql`);
  Jackson 3 is `tools.jackson.*`; use `resilience4j-spring-boot4` (the `-boot3` one fail-fasts).
- **This dev machine:** ports 5432/8080/8081/8333/8025/1025/6379 are TAKEN — overrides live in
  gitignored `docker/.env` (postgres→15432, keycloak→18081, s3→18333, valkey→16379, app via
  `SERVER_PORT=18080`). Docker is **Colima**: pre-pull images before container-heavy suites
  (pull storms have crashed the VM; after a crash run `docker context use colima`).
- **Docs that must stay current:** tick `docs/CHECKLIST.md` when a gate passes; ADRs for real
  decisions; `docs/EVENTS.md` for new event types; `docs/modulith/` + `docs/openapi/` regenerate
  on every build.

## 1. Confirm CI is green end-to-end

**Context:** `.github/workflows/ci.yml` builds on ubuntu-latest. Runs were blocked by a GitHub
**account-level billing lock** (the owner was fixing it; repo is now public). Testcontainers works
on the ubuntu runners' native Docker without any socket override.
**Do:** `gh workflow run CI` → watch. If slow/flaky, add an image pre-pull step (postgres:18.4-alpine,
keycloak 26.7.0, seaweedfs 4.40, valkey 8-alpine, ryuk 0.14.0) before `./gradlew build`, and cache
Gradle (`gradle/actions/setup-gradle` already does).
**Done when:** a green run on main; badge in README optional.

## 2. Notification module (first real cross-module event consumer)

**Context:** mailpit already runs in Compose (SMTP `${SMTP_PORT:-1025}`, UI 8025). No module
consumes events yet — this one should demo the full pattern.
**Do:** `ug.co.smsone.notification` module: `spring-boot-starter-mail` (check Boot 4.1 module
name!), `NotificationService` (email first; interface leaves room for SMS/push), an
`@ApplicationModuleListener` on `ug.co.smsone.settings.FeatureFlagChanged` that emails admins on
toggle — guarded by `EventInbox.recordIfNew("notification-flag-email", "flag:" + key + ":" + enabled)`.
Externalize SMTP via `${SMTP_HOST:localhost}/${SMTP_PORT:1025}`. IT: real mailpit container
(`axllent/mailpit`, port 1025 + HTTP API 8025 to assert the message arrived).
**Done when:** toggling a flag lands an email in mailpit in the IT; `verify()` still green;
EVENTS.md row updated.

## 3. Identity + Organization modules

**Context:** roadmap Phase 2 core. Keycloak owns credentials; these modules own the *business*
projection (profile, org membership, roles-as-data). Realm: `docker/keycloak/realm-smsone.json`.
**Do:** `identity`: `User` aggregate (subject = Keycloak `sub`, email, display name, status) +
just-in-time provisioning on first authenticated request (filter or `CurrentUserProvider` hook),
events `UserProvisioned/UserDisabled`. `organization`: `Organization`/`Membership`
(user ↔ org ↔ role), events `MemberAdded/MemberRemoved`. Follow the settings module as the
blueprint (aggregate + internal service/controller + cursor-paginated list + V8/V9 migrations +
`@ApplicationModuleTest` + API IT with `jwt()`).
**Done when:** JIT provisioning IT passes with a real Keycloak token; org CRUD + membership APIs
gated by roles; `verify()` green with 7 modules.

## 4. Kubernetes migration (config, not code — plan §5.2 has the full table)

**Do:** `deploy/k8s/base` Kustomize (Deployment + Service + probes wired to
`/actuator/health/{liveness,readiness}`, `configMapGenerator` consuming the same `.env` names the
app already reads: `POSTGRES_*`, `KEYCLOAK_ISSUER_URI`, `S3_*`, `VALKEY_*`, `OTEL_*`); overlays
`local`/`staging`/`prod`; CI job `./gradlew bootBuildImage` (Buildpacks) pushing by digest.
Secrets via External Secrets Operator in prod overlay (plaintext env only in local).
**Do NOT:** touch application code — every coordinate is already `${ENV:default}`.
**Done when:** `kubectl kustomize` renders all overlays; app boots on a kind/k3d cluster against
in-cluster Postgres+Valkey with probes passing (document the smoke run).

## 5. Rate limiting ✅ (2026-07-28)

**Done:** distributed token-bucket (`bucket4j_jdk17-core` + `bucket4j_jdk17-lettuce` 8.14.0 over
Valkey) in `shared.ratelimit`. Edge `RateLimitFilter` (`@Order(-1)`, after security) on `/api/**`:
per-route tiers keyed **tenant → sub → IP**, `429` via `EnvelopeErrorWriter` (`ErrorCode.RATE_LIMITED`)
with `Retry-After` + `RateLimit`/`RateLimit-Policy` (draft-ietf) + legacy `X-RateLimit-*`, fail-open.
Notification **egress** per-channel limits via the same `DistributedRateLimiter`. `app.rate-limit.*`
+ `app.notification.delivery.rate.*` config; IT against real Valkey (edge 429, per-tenant isolation, egress throttle).

**Deferred hardening (follow-up):**
- **Per-tenant needs a real claim.** JWTs carry no `tenant` claim yet (no organization module), so
  keying degrades to `sub`/IP. Add the claim (Keycloak mapper / organization module) to activate true
  per-tenant quotas — the resolver already reads `app.rate-limit.tenant-claim`.
- **Coarse pre-auth per-IP shield** belongs at the K8s ingress/gateway (this filter is post-auth,
  identity-aware) — add it with the Kustomize work (task 4).
- **Fail-open → local fallback.** On Valkey outage the limiter fails open; upgrade to a Resilience4j
  circuit breaker + per-instance in-memory fallback bucket (`global_limit / instanceCount`) for
  bounded protection, with a per-tier `on-backend-error: OPEN|LOCAL|CLOSED` flag.
- **Emit `RateLimit` headers on success** responses too (not just 429) so clients self-pace; add a
  Micrometer counter on throttles/429s.
- **Decouple egress from the edge flag.** `RATE_LIMIT_ENABLED=false` currently also drops the
  notification per-channel egress caps (both share the Valkey client); the limiter now WARN-logs the
  no-op state, but give egress its own `app.notification.delivery.rate-limit.enabled` +
  Valkey-availability-gated client so an API-limit toggle can't silently unthrottle SMS/SMTP.
- **Config-change latency:** Bucket4j applies a bucket's capacity only at key creation, so a raised
  limit takes up to `BUCKET_TTL` (10 min) to apply to an active key — document, or use
  `replaceConfiguration` if immediate reconfig is required.

## 6. Event externalization (when a broker arrives — Phase 5)

**Context:** events currently stay in-process (registry = outbox). When Kafka/Rabbit lands, use
`spring-modulith-events-kafka` (BOM-managed) + `@Externalized` on event records — do NOT hand-roll
a publisher. Keep `EventInbox` on all consumers (at-least-once stays at-least-once).
**Done when:** an `@Externalized` event reaches a real broker container in an IT with the registry
still tracking completion.

## 7. Notification delivery — scalability hardening (deferred from adversarial review)

**Context:** the delivery queue (`notification_delivery`) is correct and non-blocking; a 4-lens review
fixed the correctness items (SMTP timeouts, split send/markSent, re-toggle dedup, pool sizing,
graceful shutdown, `text` recipient, ms stale-lock, dead-letter log). These are the deferred
scalability/observability items (no correctness impact today):
- **Claim index:** the `claim` OR-predicate + `FOR UPDATE` falls to a seq-scan+sort; claim cost grows
  with table size. `EXPLAIN` it, then split into two index-friendly queries (due-PENDING UNION
  stale-PROCESSING) or add a partial index on `(locked_at) where status='PROCESSING'`.
- **Purge:** add `create index ... (created_at) where status='SENT'` and chunk the delete
  (`... where id in (select id ... limit 1000)` looped); also purge/alert on old `FAILED` rows.
- **Dedicated worker DataSource + batched terminal writes:** give the worker its own small pool and
  `batchUpdate` the SENT/FAILED updates grouped by outcome, decoupling send fan-out from DB-write concurrency.
- **Idempotency key + metrics:** pass the delivery row id into `NotificationMessage` metadata as an
  idempotency key (downstream dedupe of the at-least-once tail); add a Micrometer counter on
  `deadLetter()` tagged by channel+reason, with an alert.
- **Per-channel rate limits + real SMS/push providers** via the `NotificationChannelSender` SPI.
**Done when:** `EXPLAIN` shows an index scan on claim; a bulk purge doesn't lock the table; a
dead-letter increments a metric.

## Small items (bundle into any PR)

- Postman: import `docs/openapi/openapi.yaml`, verify the Keycloak authorization-code flow against
  local Keycloak (client `smsone-web`, PKCE, redirect `https://oauth.pstmn.io/v1/callback`), then
  document the 5-step setup in README.
- `docs/modulith/` renders: add a CI step or README note for rendering `.puml` (PlantUML) files.
- Revisit dasniko testcontainers-keycloak when a TC2-compatible release ships (tracked in ADR 0003)
  — would replace ~40 lines of GenericContainer setup.
- `micrometer-java21` virtual-thread dashboards: import a Grafana dashboard JSON into
  `docker/` provisioning for otel-lgtm.
