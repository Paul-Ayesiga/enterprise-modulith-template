# Runbook — API 5xx ratio above 5%

**Alert** `smsone-http-5xx` · severity critical · fires when more than 5% of API requests return
5xx over a 5-minute window, sustained for 5 minutes.

**What it means.** The modulith is failing requests it accepted. 4xx (validation, auth, rate-limit
429, payment-required 402) never trips this — a genuine 5xx is a defect, a dead dependency, or
resource exhaustion. The envelope never leaks stack traces, so the log line matching the response's
`X-Request-Id` is where the truth is.

## First five minutes

1. Scope it: is the ratio one route or everything?

       sum by (uri, status) (rate(http_server_requests_milliseconds_count{status=~"5.."}[5m]))

   - **One route** → a code defect or one dependency (e.g. `/files/**` → SeaweedFS,
     `/billing/**` → Kill Bill, `/analytics/**` → DuckDB).
   - **Everything** → shared infrastructure: Postgres, Valkey, or the pod itself.
2. Check readiness: `curl -s localhost:28080/actuator/health | jq`. A DOWN component names the
   dependency; readiness DOWN + traffic still arriving means the orchestrator has not pulled the
   pod yet.
3. Read one failing request end to end: take an `X-Request-Id` from a 5xx response (or Loki:
   `{service_name="smsone"} |= "status=500"`), then query Loki for that `rid` and open the trace in
   Tempo. The first exception in the chain is the cause; everything after is fallout.

## Diagnosis by shape

| Shape | Likely cause | Confirm with |
|---|---|---|
| All routes, sudden | Postgres down/full, connection pool exhausted | health endpoint; `HikariPool` log lines; `pg_isready` |
| All routes, gradual | Memory/CPU exhaustion, GC death spiral | container stats; `jvm_memory_used_bytes` in Grafana |
| One module's routes | That module's dependency (SeaweedFS, Kill Bill, Keycloak admin, DuckDB) | the dependency's own container logs |
| Only writes failing | Postgres read-only (disk full) or lock pile-up | `pg_stat_activity` for waiting queries |
| Spike right after a deploy | The deploy | `git log`; roll back first, diagnose second |

## Remediation

- **Dependency down**: restart it (`docker compose -f docker/docker-compose.yml restart <svc>` in
  dev; the K8s equivalent in prod). The app needs no restart — pools and breakers recover.
- **Bad deploy**: roll back to the previous image. Migrations are expand-contract (AGENTS §4.6), so
  the previous version runs against the new schema — that is what makes rollback always safe.
- **Pool exhaustion without a dead dependency**: something is holding connections (a stuck batch
  job, a slow query). Find it in `pg_stat_activity`, kill the offender, then fix the code path that
  held a connection across a remote call.
- **Disk full**: prune (old WAL, logs, dead containers), then fix retention so it stays fixed.

## If it keeps happening

A recurring 5xx alert is a defect with a stack trace on file — open an issue with the request id,
the trace, and the log excerpt, and link the incident. Do not tune the threshold to make it quiet;
5% of requests failing is never normal. Budget accounting: [../SLO.md](../SLO.md) §availability.
