# Runbooks

One file per provisioned alert (see `docker/grafana/provisioning-alerts.yaml` — each rule's
annotation links here). Every runbook follows the same shape: **what fired and what it means →
first five minutes → diagnosis → remediation → if it keeps happening**. Keep them honest: when an
incident teaches you a step that is missing, add it in the fix's PR.

| Alert (uid) | Runbook | Pages when |
|---|---|---|
| `smsone-http-5xx` | [http-5xx.md](http-5xx.md) | >5% of API requests 5xx for 5 minutes |
| `smsone-dead-letters` | [dead-letters.md](dead-letters.md) | any webhook/notification delivery exhausted its 5 attempts |
| `smsone-breaker-open` | [breaker-open.md](breaker-open.md) | a gateway circuit breaker opens for 1 minute |
| `smsone-payment-failures` | [payment-failures.md](payment-failures.md) | >3 FAILED payments at one provider in 30 minutes |

Shared context for every incident:

- **Grafana** `http://localhost:${GRAFANA_PORT:-3000}` (folder *SMSOne*): dashboards for HTTP/cache,
  deliveries/jobs, k6. Logs are in Loki (`{service_name="smsone"}`), traces in Tempo — every API
  error envelope carries the `X-Request-Id` that is also the log `rid` and trace correlation key.
- **The audit log** (`GET /api/v1/admin/audit`) answers "what changed right before this started".
- Service-level objectives and error budgets: [../SLO.md](../SLO.md). An alert firing spends
  budget; the SLO doc says how much is left and when to stop shipping and stabilize.
