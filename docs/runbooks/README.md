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

The rest are not wired to an alert — nothing pages you, you come looking:

| Runbook | Read it when |
|---|---|
| [restore.md](restore.md) | restoring from backup, or running the DR drill |
| [ci-jenkins.md](ci-jenkins.md) | signing in to the self-hosted Jenkins, wiring its two CI credentials, or recovering the local k3s node after a build exhausts it |
| [tenant-promotion.md](tenant-promotion.md) | moving one organization out of `tenant_pool` into a schema of its own — or back — and recovering a promotion whose process died (ADR 0010 §6 hop 0→1) |
| [tenant-extraction.md](tenant-extraction.md) | producing the extraction bundle for an organization that is leaving — the eleven items of ADR 0010 §6, what the bundler refuses to do without, and what to do on the far side before serving anybody |

Shared context for every incident:

- **Grafana** `http://localhost:${GRAFANA_PORT:-3000}` (folder *SMSOne*): dashboards for HTTP/cache,
  deliveries/jobs, k6. Logs are in Loki (`{service_name="smsone"}`), traces in Tempo — every API
  error envelope carries the `X-Request-Id` that is also the log `rid` and trace correlation key.
- **The audit log** (`GET /api/v1/admin/audit`) answers "what changed right before this started".
- Service-level objectives and error budgets: [../SLO.md](../SLO.md). An alert firing spends
  budget; the SLO doc says how much is left and when to stop shipping and stabilize.
