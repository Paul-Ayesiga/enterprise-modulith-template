# perf — k6 load tests

Committed, runnable [k6](https://k6.io) scripts that stress the platform against four scenario groups.
The **plan, SLOs, and what to watch** live in [`docs/PERF_PLAN.md`](../docs/PERF_PLAN.md); this is the
operational quick-start.

## Prerequisites

- The stack up: `make run` (or `make seed`) **and** `make gateway` — see [`docs/LOCAL_ACCESS.md`](../docs/LOCAL_ACCESS.md).
- k6 installed: `brew install k6` (or [other platforms](https://grafana.com/docs/k6/latest/set-up/install-k6/)).

## Run

```bash
perf/run.sh smoke        # 20s wiring check — start here
perf/run.sh baseline     # core throughput / latency percentiles
perf/run.sh spike        # burst + recovery
perf/run.sh soak         # sustained rate (DURATION=10m default)
perf/run.sh edge         # rate-limit shedding (uses the DEFAULT limit — expects 429s)
perf/run.sh auth-write   # JWT read + Postgres write path
perf/run.sh overhead     # gateway vs direct latency
```

`run.sh` sources `docker/.env` and targets your local `2xxxx` ports automatically. Trailing `KEY=VALUE`
args pass through to k6 as `--env`, e.g. `perf/run.sh baseline RATE=200 HOLD=5m`. You can also run a
script directly: `k6 run perf/scenarios/spike.js`.

## Save a run for comparison

k6 prints a summary to the terminal. To keep a machine-readable copy — so you can diff a later run
against a baseline and catch p95/p99 or error-rate regressions — set `SAVE`:

```bash
SAVE=1 perf/run.sh baseline                       # → perf/out/baseline-<timestamp>.json
SAVE=perf/out/before.json perf/run.sh overhead    # ...or name it yourself
```

Or drive k6 directly for full control of its flags:

```bash
k6 run --summary-export perf/out/baseline.json perf/scenarios/baseline.js
```

`perf/out/` is gitignored — the scripts are committed, their results are yours.

## Live in Grafana

Add `OTEL=1` to stream k6's metrics to the `otel-lgtm` stack (the same one the app uses) while a
scenario runs. The **SMSOne · k6 Load** dashboard then updates live at `http://localhost:23000`
(folder SMSOne):

```bash
OTEL=1 perf/run.sh baseline
```

It shows latency percentiles, gateway-vs-direct overhead, the status/429 breakdown, checks, and VUs —
filterable by scenario. Metrics travel over OTLP (gRPC `:24317`) into the `prometheus` datasource with
a `k6_` prefix. Combine with `SAVE=1` to keep a summary file too. The stack must be up.

## The rate-limit caveat

The default `/api/v1/**` route is rate-limited to **20 req/s per principal** (burst 40). One k6 box is
one principal, so throughput scenarios (`baseline`/`spike`/`soak`) can't push past it until you raise it:

```bash
GATEWAY_RATE_REPLENISH=100000 GATEWAY_RATE_BURST=100000 make gateway   # restart the edge, limit lifted
# ...or benchmark the app directly, bypassing the edge:
TARGET_URL=http://localhost:28080 perf/run.sh baseline
```

Leave the limit at its default for `edge` — shedding the excess is exactly what that scenario proves.

## Layout

```
perf/
  run.sh                     convenience runner (sources docker/.env)
  smoke.js                   low-intensity sanity check
  lib/common.js              shared config, token helper, env knobs
  scenarios/
    baseline.js  spike.js  soak.js          core load
    edge-enforcement.js                     rate-limit shedding
    auth-write.js                           JWT + write path
    gateway-vs-direct.js                    gateway overhead
```

## Config knobs (env)

`RATE` · `PEAK` · `CALM` · `DURATION` · `HOLD` · `PRE_VUS` · `MAX_VUS` — intensity per scenario.
`TARGET_URL` · `READ_PATH` · `WRITE_PATH` — what gets hit.
`GATEWAY_URL` · `MODULITH_URL` · `KEYCLOAK_URL` · `PERF_USER` · `PERF_PASSWORD` · `KEYCLOAK_SCOPE` — where/who.

Nothing here writes anything permanent except `auth-write.js`, which upserts a throwaway setting key
(`perf.probe`).
