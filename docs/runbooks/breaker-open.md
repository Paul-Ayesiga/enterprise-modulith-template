# Runbook — gateway circuit breaker open

**Alert** `smsone-breaker-open` · severity critical · fires when a Resilience4j breaker at the
gateway reports state `open` for 1 minute.

**What it means.** The breaker (sliding window 4 calls, opens at 50% failures, stays open 1s then
half-opens) watched a backend fail more than half its recent calls and is now failing fast — callers
get an immediate 503 instead of a 15s timeout each. The breaker is the messenger: the incident is
the backend behind it. A breaker that stays open across minutes means the backend keeps failing
every half-open probe.

## First five minutes

1. Which breaker (`name` is the route/backend):

       max by (name) (resilience4j_circuitbreaker_state{state="open"})

   and its failure evidence:

       sum by (name, kind) (rate(resilience4j_circuitbreaker_calls_seconds_count{kind=~"failed|not_permitted"}[5m]))

2. Hit the backend directly (bypassing the gateway) — is it down, slow, or erroring?
   For the modulith backend: `curl -s localhost:28080/actuator/health`. If the backend is healthy
   when reached directly, the problem is between gateway and backend: DNS, the LoadBalancer's
   instance list, or the 15s gateway timeout being shorter than the backend's current latency.
3. Check the gateway's own view: `GET :9090/actuator/gatewayroutes` (with `X-Admin-Token` if set)
   — confirm the route's serviceId/uri still points where you think.

## Diagnosis

| Evidence | Cause |
|---|---|
| Backend health DOWN | The backend itself — switch to its runbook (for the modulith, [http-5xx.md](http-5xx.md) applies) |
| Backend healthy, breaker cycling open/half-open | Latency above the 15s gateway timeout — timeouts count as failures |
| `not_permitted` only, no fresh `failed` | Breaker mid-recovery; if the alert clears itself, post-mortem only |
| After a route table edit | The edit (wrong uri/serviceId) — check the audit trail in the admin portal, revert the route |

## Remediation

- **Backend down** → restart/recover the backend; the breaker half-opens and closes on its own
  within seconds of real successes. Never "fix" this by removing the breaker.
- **Slow backend** → the fix is the latency (hot query, missing index, saturated pool). Raising the
  gateway timeout is a last resort and a product decision — 15s is already generous for an API.
- **Misconfigured route** → correct it via the admin API/portal (runtime) and in the YAML route
  table (durable) so a restart does not resurrect the bad config.
- **Traffic overwhelm** (rare: breaker opening because the backend is drowning) → verify the
  token-bucket rate limits are actually attached to the hot route (`rate-limited: true`) before
  scaling the backend.

## If it keeps happening

A breaker that opens routinely is a capacity or dependency-health fact nobody has acted on. Track
which `name` fires, at what traffic, and either fix the backend's p99 or resize. The breaker
config (window 4 / 50% / 1s) is deliberately twitchy in dev; production tuning belongs in the
gateway values file with a comment, not in silence. Budget: [../SLO.md](../SLO.md) §availability.
