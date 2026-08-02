# Performance & Load-Test Plan

How we measure this platform under stress — the scenarios, the pass/fail gates (SLOs), what to watch
while it runs, and how to run it. The load generator is **[k6](https://k6.io)**; the scripts live in
[`perf/`](../perf) and are committed and runnable as-is.

> **The one thing to know first.** The production `/api/v1/**` route is **rate-limited to 20 req/s per
> principal** (burst 40). A single k6 box is one principal, so *throughput* scenarios can't push past
> that until you raise the limit — while the *edge-enforcement* scenario depends on that exact limit to
> prove it fires. The plan treats those as two different jobs. See [Throughput vs enforcement](#throughput-vs-enforcement).

---

## What we're measuring

Four questions, one scenario group each:

| # | Group | The question it answers | Scripts |
|---|---|---|---|
| 1 | **Core load** | What throughput does the system sustain, and what are its latency percentiles — steady, spiking, and over a long haul? | `baseline.js` · `spike.js` · `soak.js` |
| 2 | **Edge enforcement** | Under a flood, does the edge shed the excess (429s) while still serving the allowed share — never 5xx? | `edge-enforcement.js` |
| 3 | **Auth + write path** | What does an authenticated read (JWT/JWKS) cost, and what does a mutation (Postgres/Hikari) add on top? | `auth-write.js` |
| 4 | **Gateway overhead** | How much latency does the edge add versus hitting the modulith directly? | `gateway-vs-direct.js` |

A fifth script, `smoke.js`, is a 20-second low-intensity sanity check — run it first to prove the
wiring (token → gateway → modulith) before any real load.

---

## System under test

Two processes plus the Compose stack, on this machine's `2xxxx` local ports (a clean machine drops the
`2` — see `docker/.env.example`):

```
 k6  ──►  gateway :28090  ──►  modulith :28080  ──►  Postgres :25432
          (edge)                (app)                 Valkey   :26379  (rate-limit buckets, cache)
          admin  :29090                               Keycloak :28081  (JWKS)
```

- **Edge** — `:28090` serves `/api/v1/**`; its admin/metrics live on the separate `:29090`.
- **App** — `:28080` serves the same API directly (bypasses the edge), used by the overhead comparison.
- **Auth** — tokens come from Keycloak (`paul` / `Paul123`, client `smsone-web`, scope `organization`).
  k6 fetches one token in `setup()` and reuses it across VUs (JWKS validation is what we're timing, not
  the login round-trip).

The representative **read** is `GET /api/v1/settings?page[size]=5` (auth `USER`); the representative
**write** is `PUT /api/v1/settings/perf.probe` with `{"value":…}` (auth `platform-admin` — `paul`
qualifies). Both are overridable via `READ_PATH` / `WRITE_PATH`.

---

## The scenarios

### 1 — Core load

| Script | Shape | Default intensity |
|---|---|---|
| `baseline.js` | Ramp to a peak arrival rate, hold, ramp down | peak `RATE=100` req/s over ~5 min |
| `spike.js` | Calm → sudden slam → hold → drop → watch recovery | `CALM=20` → `PEAK=400` req/s |
| `soak.js` | Constant moderate rate held for a long stretch | `RATE=15` req/s for `DURATION=10m` |

**Watching for:** flat latency percentiles under baseline; graceful recovery after the spike (latency
returns to baseline, no lingering errors); and over the soak, *no creep* — steady p95, flat heap, no
climbing Hikari wait time.

### 2 — Edge enforcement (`edge-enforcement.js`)

Floods the rate-limited route at **~10× the limit** (`RATE=200` req/s) from one principal against the
**default** limit. Custom metrics split the outcome: `edge_allowed_200`, `edge_limited_429`,
`edge_limited_ratio`. A healthy edge returns only 200 (served) or 429 (shed) — **never a 5xx** — and
sheds the majority.

Two more enforcement mechanisms are exercised as [variants](#variants) below: the per-consumer **quota**
(a slower window than the token bucket) and the **circuit breaker** (needs a deliberately-failing route).

### 3 — Auth + write path (`auth-write.js`)

A read-heavy mix (≈4 reads : 1 write) at a rate under the limit, with **read and write latency tracked
separately** (`read_latency`, `write_latency`). The gap between them is the cost of the write path:
transaction, Hikari checkout, Postgres round-trip, plus any cache eviction the write triggers.

### 4 — Gateway overhead (`gateway-vs-direct.js`)

Issues the *same* read through the edge and straight at the modulith, tagged `route:gateway` /
`route:direct`. **`edge p95 − direct p95` is the per-request cost** of routing + the filter chain +
auth + the extra hop. Runs under the limit so the edge isn't shedding (that would flatter the gap).

---

## SLOs — the pass/fail gates

These are k6 `thresholds`; a breached threshold fails the run (non-zero exit — CI-friendly). They are
**starting targets** — tune them to your hardware once you have a baseline, then treat regressions
against the tuned numbers as real.

| Scenario | Gate |
|---|---|
| `baseline` | error rate < 1% · p95 < 300 ms · p99 < 800 ms |
| `spike` | error rate < 5% · p95 < 800 ms · p99 < 2 s (recovery is judged on the timeline, not just the aggregate) |
| `soak` | error rate < 1% · p95 < 300 ms · p99 < 800 ms held flat for the whole run |
| `edge-enforcement` | some 429s **and** some 200s · shed ratio > 50% · zero 5xx |
| `auth-write` | error rate < 1% · read p95 < 300 ms · write p95 < 500 ms |
| `gateway-vs-direct` | gateway p95 < 300 ms · direct p95 < 250 ms · error rate < 1% |

---

## What to watch (server-side)

k6 reports the client's view (latency percentiles, RPS, error/threshold status). Pair it with the
server's view for the *why*:

- **Gateway metrics** — `http://localhost:29090/actuator/prometheus` (edge). Rate-limiter rejections,
  circuit-breaker state, per-route latency, JVM heap/GC.
- **Modulith metrics** — `http://localhost:28080/actuator/prometheus`. HTTP server timings, **Hikari
  pool** (`hikaricp_connections_pending`, `_active`, `_timeout`), cache hit ratios, JVM.
- **Grafana** — `http://localhost:23000` (anonymous admin), **SMSOne** folder. Three provisioned
  dashboards: *deliveries & jobs*, *API & cache*, and ***k6 Load*** — the last shows the load run
  itself (latency percentiles, gateway-vs-direct overhead, status/429 split, checks, VUs), live when
  you run with `OTEL=1` (below). Traces/metrics/logs land via OTLP.
- **Valkey** — the rate-limit token buckets and L2 cache live here; watch it stays healthy under the flood.

The signals that explain a latency knee, in order of likelihood: Hikari pending > 0 (pool starvation),
GC pauses (heap pressure), cache hit-ratio collapse, then CPU saturation on the app or edge.

---

## Throughput vs enforcement

The edge's per-principal token bucket is a **feature**, not an obstacle — but it means one load box
can't benchmark raw throughput through the front door. Two honest ways past it:

**Raise the limit for a throughput run** (restart the edge with it lifted):

```bash
GATEWAY_RATE_REPLENISH=100000 GATEWAY_RATE_BURST=100000 make gateway
```

**Or bypass the edge** and benchmark the app alone (point the throughput scripts straight at the modulith):

```bash
TARGET_URL=http://localhost:28080 perf/run.sh baseline
```

Leave the limit at its default (20/40) for `edge-enforcement.js` — shedding the excess is the whole point.

---

## How to run

**Prerequisites:** the stack up (`make run` + `make gateway`, per [LOCAL_ACCESS](LOCAL_ACCESS.md)) and
k6 installed (`brew install k6`, or see [k6 install docs](https://grafana.com/docs/k6/latest/set-up/install-k6/)).

```bash
perf/run.sh smoke                    # 1. sanity check (start here)
perf/run.sh baseline                 # 2. core throughput/latency (raise the limit first — see above)
perf/run.sh spike
perf/run.sh soak
perf/run.sh edge                     # default limit — expects 429s
perf/run.sh auth-write
perf/run.sh overhead
```

`run.sh` sources `docker/.env`, so it targets your `2xxxx` ports automatically. Trailing `KEY=VALUE`
args pass straight through to k6, e.g. `perf/run.sh baseline RATE=200 HOLD=5m`. `SAVE` keeps a
machine-readable summary for diffing; `OTEL` streams live metrics to Grafana (both compose):

```bash
SAVE=1 perf/run.sh baseline                                                    # → perf/out/baseline-<timestamp>.json
OTEL=1 perf/run.sh baseline                                                    # → live in Grafana's "SMSOne · k6 Load"
k6 run --summary-export perf/out/baseline-$(date +%F).json perf/scenarios/baseline.js
```

Every knob is env-driven (`RATE`, `PEAK`, `CALM`, `DURATION`, `HOLD`, `TARGET_URL`, `READ_PATH`,
`WRITE_PATH`, `PERF_USER`, `PERF_PASSWORD`, `GATEWAY_URL`, `MODULITH_URL`, `KEYCLOAK_URL`) — the same
scripts run against staging or CI by overriding the URLs.

---

## Variants

**Per-consumer quota.** Beyond the token bucket, the edge enforces a per-consumer plan quota (a longer
window). To exercise it, drive a sustained rate just above the plan's allowance for a single consumer
and watch for quota 429s in the gateway metrics — distinct from the rate-limiter's, and slower to trip.

**Circuit breaker.** Needs a route whose backend fails. Temporarily register a route pointing at a dead
port (via the gateway catalog endpoint or a test route), drive load at it, and confirm the breaker opens
(fast-fails rather than hanging) and half-opens on recovery. The breaker trips on the configured 5xx
status set — see `TrafficPolicy` / the circuit-breaker filter.

**Load balancing.** With more than one modulith instance registered under the `lb://` service id, a
throughput run should spread across instances; watch per-instance metrics to confirm even distribution.

---

## Interpreting a run

1. **Thresholds first** — k6 prints `✓`/`✗` per threshold and exits non-zero on any breach. A green run
   met every SLO above.
2. **Percentiles, not the mean** — p95/p99 are the gates; a healthy mean hides a bad tail.
3. **Correlate the knee** — when latency climbs, line it up against the server signals above to name the
   cause (pool, GC, cache, CPU) rather than guessing.
4. **Compare like-for-like** — keep intensity and hardware fixed between runs; a regression is a shift
   against *your* captured baseline, not against these starter numbers.

Captured local baselines live in the section below; refresh them when the hardware or the code path changes.

---

## Baseline numbers (local smoke)

_Captured on the local `2xxxx` stack with `perf/smoke.js` — a low-intensity wiring check, not a
benchmark. Refresh with `perf/run.sh smoke` after significant changes._

<!-- BASELINE:START -->
Run: `perf/run.sh smoke` · 4 VUs · 20 s · ~22 req/s total (≈10 req/s per path, under the 20/s limit) ·
darwin/arm64, local Colima stack · 2026-08-02. **All thresholds green, 0% errors, 457/457 checks.**

| Path | p50 | p95 | max |
|---|---|---|---|
| Read **direct** (`:28080`) | 14 ms | 36 ms | 168 ms |
| Read **via gateway** (`:28090`) | 28 ms | 69 ms | 193 ms |

**Gateway overhead:** ≈ **14 ms** median, ≈ **33 ms** at p95 (edge − direct) — routing + the filter
chain + auth + one extra hop, on a warm cache. The first uncached gateway read was ~1.3 s (JWKS fetch +
proxy warmup), which is why real runs discard warmup and read percentiles, not the max.
<!-- BASELINE:END -->
