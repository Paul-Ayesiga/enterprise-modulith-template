# Service-level objectives

What the platform promises, how each promise is measured (the exact Prometheus expression — no
objective without a measurement), and what happens when the error budget runs out. The provisioned
alerts (`docker/grafana/provisioning-alerts.yaml`) are the fast-burn tripwires for these objectives;
the runbooks (`runbooks/`) are the response. Windows are calendar months, matching billing.

The discipline this document exists to enforce: **an exhausted error budget stops feature work.**
When a budget is spent before month-end, the next sprint is reliability work on the objective that
spent it — that trade is agreed here, in advance, so it never has to be argued mid-incident.

## Availability — API success ratio

| | |
|---|---|
| **SLO** | 99.9% of API requests succeed (non-5xx) per calendar month |
| **Error budget** | 0.1% ≈ 43.8 minutes of full outage, or proportionally more of partial degradation |
| **SLI** | `1 - (sum(increase(http_server_requests_milliseconds_count{status=~"5.."}[30d])) / sum(increase(http_server_requests_milliseconds_count[30d])))` |
| **Excluded** | 4xx of every kind: validation 422, auth 401/403, rate-limit 429, payment-required 402, maintenance-mode 503 *when platform-initiated maintenance was announced* |
| **Fast burn** | `smsone-http-5xx` (>5% for 5m — that pace spends a month's budget in ~14 hours) → [runbooks/http-5xx.md](runbooks/http-5xx.md) |

Rationale: 99.9% is honest for a single-region deployment with planned maintenance windows;
promising 99.99% without multi-region is fiction. The gateway inherits this objective — its 503s
(breaker open, no backend) count against the same budget, measured at the gateway's own
`http_server_requests` once it exports over OTLP.

## Latency — API responsiveness

| | |
|---|---|
| **SLO** | 95% of API requests complete in < 500 ms (excluding file transfer and export/exchange endpoints, whose duration is payload-bound) |
| **SLI** | `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_milliseconds_bucket{uri!~"/api/v1/files.*\|/api/v1/orgs/.*/exchange.*"}[30d])))` |
| **Fast burn** | none paged — latency degrades visibly on the *API & Cache* dashboard first; page only if it collides with availability |

Rationale: p95 not p99 — at this scale p99 is dominated by JIT warm-up and GC noise; 500 ms is the
threshold where interactive portal UX visibly drags. The two-level cache is the main defense;
watch the hit-ratio panel when this drifts.

## Delivery — webhooks and notifications

| | |
|---|---|
| **SLO** | 99% of deliveries reach DELIVERED within 15 minutes of the triggering event, per calendar month |
| **SLI (proxy)** | dead-letter rate: `sum(increase(smsone_deliveries_dead_lettered_total[30d]))` vs total dispatched; plus the backlog gauge on the *Deliveries & Jobs* dashboard |
| **Excluded** | failures whose cause is the org's receiver (connect-refused, receiver 4xx/5xx, stale receiver-side secret) — our promise is attempts-with-backoff, not their uptime |
| **Fast burn** | `smsone-dead-letters` (first DEAD row) → [runbooks/dead-letters.md](runbooks/dead-letters.md) |

Rationale: at-least-once with 5 attempts over ~2 h of backoff means "within 15 minutes" holds
whenever the receiver is up on the first or second attempt — the 1% budget absorbs redeliveries.

## Payments — collection pipeline health

| | |
|---|---|
| **SLO** | 99% of payment initiations that the PSP accepts reach a terminal status (COMPLETED/FAILED — not stuck PENDING) within 24 h |
| **SLI** | terminal outcomes: `sum by (status) (increase(smsone_payments_outcomes_total[30d]))`; stuck rows: `select count(*) from payment where status='PENDING' and created_at < now() - interval '24 hours'` |
| **Excluded** | payer-caused FAILED (insufficient funds, canceled handset prompt) — those are *successful* pipeline outcomes; the objective is that the pipeline answers, not that payers pay |
| **Fast burn** | `smsone-payment-failures` (>3 FAILED/provider/30m) → [runbooks/payment-failures.md](runbooks/payment-failures.md) |

Rationale: PENDING resolves on read (refresh-on-get) and via IPN/callback; a row still PENDING
after 24 h means both paths went quiet — that is our defect or a dead IPN registration, never
normal.

## Reporting

Month-end: read each SLI over the closed month, record attainment vs objective, and carry the
verdict into planning — met with budget to spare (ship), met narrowly (watch), missed (the next
sprint's priority is that objective, per the discipline above). Attainment queries run in Grafana
Explore against the `prometheus` datasource; there is deliberately no automation here until the
numbers have been read by a human for a few months and the objectives have survived contact with
real traffic.
