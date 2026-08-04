# Grafana provisioning (otel-lgtm)

Two dashboards are file-provisioned into the `otel-lgtm` container's Grafana (folder **SMSOne**,
`http://localhost:3000`, anonymous admin in the dev image):

- **SMSOne · Deliveries & Jobs** — dead-letter rate (webhook + notification), exchange job outcomes
  and record throughput, retention-purge activity.
- **SMSOne · API & Cache** — HTTP p95 by route, rate-limit denials by tier, two-level cache hit
  ratio, impersonation session trend.
- **SMSOne · k6 Load** — live load-test metrics (latency percentiles, gateway-vs-direct overhead,
  status/429 breakdown, checks, VUs), filterable by scenario. Populated only while a k6 run streams
  over OTLP — `OTEL=1 perf/run.sh <scenario>`; see `perf/README.md` and `docs/PERF_PLAN.md`.

Metrics arrive over OTLP from the application (the Boot OTel starter exports Micrometer meters;
counter `smsone.foo.bar` surfaces in Prometheus as `smsone_foo_bar_total`). The custom meters and
where each increments are catalogued in `docs/SRS.md` §5.6. k6 uses the same path — its metrics arrive
with a `k6_` prefix (histograms as `k6_..._milliseconds_bucket`).

## Alert rules

Four critical alerts are **file-provisioned** from `provisioning-alerts.yaml` (folder **SMSOne**,
read-only in the UI — thresholds change in git, in review): API 5xx ratio, dead-letters, gateway
breaker open, payment failures clustering. Each rule's annotation links its runbook in
`docs/runbooks/`. The rules below are additional starting points, written as Prometheus
expressions — add them under Alerting → Alert rules against the `prometheus` datasource.
Thresholds are deliberately conservative; tune to your traffic.

**Dead-letters appearing** — any give-up is a message someone expected to arrive:

    sum(increase(smsone_deliveries_dead_lettered_total[15m])) > 0

**Rate-limit pressure** — sustained denials mean a client is misbehaving or a quota is mis-sized:

    sum by (tier) (increase(smsone_ratelimit_denied_total[5m])) > 50

**A purge went silent** — every retention job runs nightly; more than a day of silence on a table
that normally has churn means the job is failing (its own log line did not happen either). Zero
deletions are deliberately NOT recorded, so absence of the series — not a zero value — is the
signal; alert per job with `absent_over_time`:

    absent_over_time(smsone_purge_deleted_total{job="soft-delete-purge"}[26h])
    absent_over_time(smsone_purge_deleted_total{job="webhook-delivery-retention"}[26h])

(One caveat: a table with genuinely nothing to purge also produces no series — scope this alert to
jobs whose tables always have churn in your deployment.)

**Exchange jobs failing** — FAILED is the retries-exhausted outcome, so even one deserves a look:

    sum by (handler) (increase(smsone_exchange_jobs_total{outcome="failed"}[1h])) > 0

**Storage circuit breaker open** — Resilience4j publishes breaker state; 1 means open:

    max(resilience4j_circuitbreaker_state{name="storage", state="open"}) == 1
