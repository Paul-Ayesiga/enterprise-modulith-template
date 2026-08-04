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
AGENTS.md §4.6 is the contract. Flyway runs in the modulith at boot; the previous image always
runs against the current schema. Never gate a deploy on a manual DB step.

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
| `GATEWAY_BLOCKLIST` / `GATEWAY_ALLOWLIST` | empty | durable front-door deny / never-blocked CIDRs; runtime deny adds via `gatewayblocklist`/admin portal last until restart |
| `GATEWAY_AUTOBLOCK` (+ `_THRESHOLD` / `_WINDOW` / `_DURATION` / `_STATUSES`) | on (20 denials/min → 15m) | dynamic auto-blocking of abusive sources; tune the rules or widen `_STATUSES` to 429/404; allow-list trusted infra so it's never caught |
| Grafana | anonymous admin (otel-lgtm) | real auth; alerts to a contact point (Slack/pager), not just the UI |

## E2E gate

`e2e/` holds the Playwright scaffold (login-gate smoke for both portals). It needs a running
stack, so it is not in the PR path — run it post-deploy (`cd e2e && npm ci && npx playwright test`)
pointed at the environment via `PORTAL_URL`/`ADMIN_PORTAL_URL`, or wire it into the deploy job
after the stub is promoted.
