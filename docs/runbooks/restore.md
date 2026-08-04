# Runbook — restore from backup (DR)

Not alert-driven: you are here because data is gone (bad migration past review, operator error,
volume loss, region loss). **Stated objectives: RPO ≤ 24 h** (nightly dumps — anything written
since the last dump is lost unless the provider's WAL/PITR can close the gap) and **RTO ≈ 1 h**
(the steps below, practiced). If those numbers are wrong for the business, the fix is snapshot
frequency, not optimism.

## What exists to restore from

| Store | Backup | Taken by |
|---|---|---|
| App Postgres (`modulith`) | `*.dump` (pg_dump custom) on the backup PVC / backup dir | Helm CronJob `smsone-pg-backup` / `scripts/backup-postgres.sh` |
| Keycloak Postgres | same mechanism, its own database | run the same job/script against `keycloak` |
| Kill Bill MariaDB/Postgres | provider snapshots (or its own dump job) | environment-specific |
| SeaweedFS (file/document bytes) | volume snapshots of the SeaweedFS data volume | infrastructure |
| Valkey | **nothing — by design.** Cache, rate-limit windows, idempotency leases rebuild | — |

Grafana alert rules, dashboards, Helm values, realm shape: all in git — redeploy, don't restore.

## Order (dependencies point down)

1. **Stop writers**: scale modulith and gateway to 0 (`kubectl scale deploy modulith gateway --replicas=0`).
   Enable the maintenance flag if only the app DB is being restored and you want Keycloak up.
2. **Keycloak DB** (identities first — app rows reference Keycloak subjects/org ids):
   `scripts/restore-postgres.sh keycloak <dump>` into a fresh DB, repoint `KC_DB_URL`, start
   Keycloak, verify login on the auth host.
3. **App DB**: `scripts/restore-postgres.sh modulith <dump>`. Flyway on next boot validates rather
   than migrates if the dump matches the running version; a dump older than the current code is
   fine (expand-contract means new code runs on old schema **only forward** — Flyway will apply the
   missing migrations on boot).
4. **Kill Bill DB**, then Kill Bill itself. Billing reconcile is one-way and idempotent: once the
   modulith is up, `POST /api/v1/admin/orgs/{orgId}/billing/reconcile` per org (or let the callback
   traffic converge it) repairs entitlement drift between the two restore points.
5. **SeaweedFS**: restore the volume snapshot. Rows in `document` that reference bytes lost after
   the snapshot will 404 on download — the row is the truth of what SHOULD exist; reconcile or
   communicate, don't delete.
6. **Start the apps**, watch readiness, then run the smoke: login → list orgs → upload+download a
   file → initiate a sandbox payment → fire a test webhook.

## Cross-store consistency

The stores were dumped at different instants; after restore expect edge cases, all self-healing or
tool-assisted: outbox events re-publish on restart (at-least-once by design); billing drift →
reconcile (step 4); an org in Keycloak but not in the app DB → re-run provisioning for it; a
payment the PSP completed inside the gap → refresh-on-read completes it when next queried.

## Practice

A restore that has never been rehearsed is a rumor. Quarterly: restore the latest dump into a
scratch database (`createdb modulith_drill && scripts/restore-postgres.sh modulith_drill <dump>`),
run the row-count spot check against production's counts, note the wall-clock time, delete the
scratch. Update this file when reality disagrees with it.
