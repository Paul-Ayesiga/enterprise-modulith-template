# Production deployment

The road from `docker compose up` to a cluster. The shape: **stateless deployables in the Helm
chart** (`deploy/helm/smsone/` — modulith, gateway, two portals, Keycloak), **state external**
(Postgres, Valkey, SeaweedFS, Kill Bill — managed services or infrastructure you snapshot), CI
building and publishing every image on main (`.github/workflows/ci.yml`), and the ops layer from
[SLO.md](SLO.md) / [runbooks/](runbooks/) watching it.

## What CI already does

Every PR: modulith + gateway suites on real containers, portal typecheck+build, CycloneDX SBOM
(30-day artifact), trivy scan failing on fixable CRITICALs, `helm lint` + render. Every main push:
images to GHCR — `modulith` and `gateway` via Boot buildpacks, the portals via their Dockerfiles —
tagged with the commit SHA (immutable) and `main` (moving). The `deploy` job is a stub printing the
real `helm upgrade` command; promote it by adding `KUBECONFIG` to the `production` environment and
replacing the echo.

## First deploy, in order

1. **Provision state**: Postgres (one instance, three databases: `modulith`, `keycloak`, and Kill
   Bill's), Valkey, SeaweedFS (or any S3), Kill Bill + Kaui. None of these live in the chart, on
   purpose.
2. **Create the credentials Secret** — the exact `kubectl create secret` incantation is in the
   chart's NOTES.txt (rendered on install). Nothing in values.yaml is ever a credential.
3. **DNS + TLS**: the four hosts in `ingress.hosts` (api/auth/portal/admin) → the ingress
   controller; cert-manager or a wildcard cert in `ingress.tlsSecretName`.
4. **Install**:

       helm upgrade --install smsone deploy/helm/smsone \
         --namespace smsone --create-namespace \
         --set global.imageTag=<commit-sha> \
         -f my-environment-values.yaml

   The install blocks on `{release}-tenant-migration`, a pre-install hook Job that builds the
   platform schema and then the tenant schemas before any modulith pod starts. If it fails, the
   release fails with nothing deployed — read its log (`kubectl logs job/<release>-tenant-migration`)
   and see *Migration discipline* below.
5. **Keycloak first boot** (see below), then restart the modulith once so its admin-client secret
   and issuer line up.
6. **Kill Bill**: run `scripts/killbill-init.sh` against the prod Kill Bill (tenant + catalog),
   or set `BILLING_BOOTSTRAP=true` on the modulith for its self-bootstrap path.
7. **Smoke**: login at the portal host → create an org → upload a file → sandbox payment →
   test webhook. Watch the *SMSOne* Grafana folder while you do it.

## Keycloak in production

Dev compose runs `start-dev --import-realm` — **both halves are dev-only.** The chart runs
`start --optimized` against its own Postgres, with `KC_HOSTNAME` pinned to the auth host and
proxy headers on (TLS terminates at the ingress). No `--import-realm`: a re-import on restart
resets users and secrets; in prod the realm is data, owned by the DB and its backups.

First boot only:

- The bootstrap admin comes from the Secret (`KC_BOOTSTRAP_ADMIN_*`).
- Import the realm shape ONCE — Admin Console → Realm import with
  `docker/keycloak/realm-smsone.json`, or `kcadm.sh create realms -f realm-smsone.json`. Then
  rotate every secret the import carried: the `smsone-api` client secret (goes into the modulith's
  env), SMTP, and delete any demo users. `BRAND_NAME` substitution happens at import; the display
  name is editable in the console afterwards.
- The custom login theme ships in `docker/keycloak/themes/smsone` — in K8s, bake it into a derived
  Keycloak image (`COPY themes/smsone /opt/keycloak/themes/smsone`) rather than a volume; dev's
  theme-cache-off flags must NOT be set in prod.
- Platform admin seeding stays what it is everywhere: exactly one `platform-super-admin` from the
  modulith's seed path; humans get platform roles through the admin surface, audited.

## Migration discipline (why rollback is safe)

Deploys are rolling and rollbacks are instant **because** every migration is expand-contract —
AGENTS.md §4.6 is the contract. The previous image always runs against the current schema. Never
gate a deploy on a manual DB step.

**Two sequences, and only one of them is at boot** (ADR 0010 §4.2):

- `db/migration/platform` — autoconfigured Flyway in the modulith at startup, as it always was. One
  schema, advisory-lock-serialized across replicas.
- `db/migration/tenant` — applied to `tenant_pool` and to every silo by the
  `{release}-tenant-migration` **Job**, a `pre-install,pre-upgrade` Helm hook (Argo CD `PreSync`)
  that Helm waits for before it touches the modulith Deployment. Same image as the release it
  precedes. It is not a manual step and it is not optional: with `tenantMigration.enabled: false`
  nothing else migrates tenant schemas, and a fresh install would bring up pods against an empty
  `tenant_pool`.

Not at boot because the chart has **no `startupProbe`** and the liveness settings give a pod ~105 s
before the kubelet kills it, while Flyway runs before the servlet container serves — a per-tenant
fan-out at startup is a rollout that works until the fleet grows. See the Job template's header.

**The deploy order inverts for tenant migrations.** A tenant migration ships in release N; the code
that depends on it ships no earlier than N+1, and only once the fan-out has recorded 100% of tenant
schemas at that version. Ask before shipping the reader:

    kubectl logs job/<release>-tenant-migration        # the manifest, one line per schema
    select schema_name, state, schema_version, last_error from platform.tenant_placement;

**When the Job exits non-zero.** It never aborts on one tenant — it records the failure and finishes
the fleet, so the exit code says *some* tenant is behind, not *which*. Read the manifest, then:

- exit **1** — one or more tenant schemas failed. Each is at V(n−1) with **no** history row (tenant
  migrations are transactional by rule), so the schema is internally consistent and a re-run retries
  it. The binary serves that tenant at its old version, or answers 503 + `Retry-After` if it is below
  the release's floor. Every other tenant is unaffected.
- exit **2** — the platform sequence failed and the fan-out was never attempted. Nothing moved.
- a schema wedged at `success = false` (only reachable through a non-transactional *platform*
  migration, or a checksum drift) needs `--mode=repair`, which repairs and then finishes the
  migration. Target one silo rather than the fleet with
  `--schemas=t_<32hex>`.

## Backups & DR

Nightly `pg_dump` CronJob (chart), `scripts/backup-postgres.sh` for anything outside the cluster,
provider snapshots as the first line where they exist, SeaweedFS by volume snapshot, Valkey never.
Objectives, restore order, and the quarterly drill: [runbooks/restore.md](runbooks/restore.md).

## The knobs that change meaning in prod

| Env / value | Dev | Prod |
|---|---|---|
| `SIGNUP_ENABLED` | true for demos | deliberate product decision |
| `TRIAL_ON_SIGNUP` | true | product decision |
| `BILLING_BOOTSTRAP` | true (self-seeds Kill Bill) | true on first boot, then off |
| Payments `*_MODE` (pesapal/yo) | `sandbox` | `live` — creds are per-mode; mode is stamped per payment row |
| `SMS_STUB` | often true | false, real Speeda creds in the Secret store |
| `GATEWAY_ADMIN_TOKEN` | optional | set — the admin port is cluster-internal AND token-gated |
| `TRUSTED_PROXY_HOPS` / `GATEWAY_TRUSTED_PROXY_HOPS` | 0 (direct — XFF ignored) | the chart sets 2 (modulith) / 1 (gateway) for ingress → gateway → modulith; wrong values make IP allowlists/blocklists judge the wrong address |
| `GATEWAY_BLOCKLIST` / `GATEWAY_ALLOWLIST` | empty | YAML front-door deny / never-blocked CIDRs. UI-added blocks are runtime by default; tick "make permanent" for a durable block (`GATEWAY_PERSISTENT_BLOCKLIST`, Valkey-backed, on by default) |
| `GATEWAY_AUTOBLOCK` (+ `_THRESHOLD` / `_WINDOW` / `_DURATION` / `_STATUSES`) | on (20 denials/min → 15m) | dynamic auto-blocking of abusive sources; tune the rules or widen `_STATUSES` to 429/404; allow-list trusted infra so it's never caught |
| Grafana | anonymous admin (otel-lgtm) | real auth; alerts to a contact point (Slack/pager), not just the UI |

## E2E gate

`e2e/` holds the Playwright scaffold (login-gate smoke for both portals). It needs a running
stack, so it is not in the PR path — run it post-deploy (`cd e2e && npm ci && npx playwright test`)
pointed at the environment via `PORTAL_URL`/`ADMIN_PORTAL_URL`, or wire it into the deploy job
after the stub is promoted.
