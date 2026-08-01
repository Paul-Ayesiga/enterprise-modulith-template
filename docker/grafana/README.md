# Grafana provisioning (otel-lgtm)

Two dashboards are file-provisioned into the `otel-lgtm` container's Grafana (folder **SMSOne**,
`http://localhost:3000`, anonymous admin in the dev image):

- **SMSOne · Deliveries & Jobs** — dead-letter rate (webhook + notification), exchange job outcomes
  and record throughput, retention-purge activity.
- **SMSOne · API & Cache** — HTTP p95 by route, rate-limit denials by tier, two-level cache hit
  ratio, impersonation session trend.

Metrics arrive over OTLP from the application (the Boot OTel starter exports Micrometer meters;
counter `smsone.foo.bar` surfaces in Prometheus as `smsone_foo_bar_total`). The custom meters and
where each increments are catalogued in `docs/SRS.md` §5.6.

## Example alert rules

Starting points, written as Prometheus expressions — add them under Alerting → Alert rules against
the `prometheus` datasource. Thresholds are deliberately conservative; tune to your traffic.

**Dead-letters appearing** — any give-up is a message someone expected to arrive:

    sum(increase(smsone_deliveries_dead_lettered_total[15m])) > 0

**Rate-limit pressure** — sustained denials mean a client is misbehaving or a quota is mis-sized:

    sum by (tier) (increase(smsone_ratelimit_denied_total[5m])) > 50

**A purge went silent** — every retention job runs nightly; more than a day of silence on a table
that normally has churn means the job is failing (its own log line did not happen either):

    sum by (job) (increase(smsone_purge_deleted_total[26h])) == 0

**Exchange jobs failing** — FAILED is the retries-exhausted outcome, so even one deserves a look:

    sum by (handler) (increase(smsone_exchange_jobs_total{outcome="failed"}[1h])) > 0

**Storage circuit breaker open** — Resilience4j publishes breaker state; 1 means open:

    max(resilience4j_circuitbreaker_state{name="storage", state="open"}) == 1
