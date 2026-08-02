#!/usr/bin/env bash
# Convenience runner for the k6 perf scenarios. Sources docker/.env so it targets your local 2xxxx
# ports automatically; override GATEWAY_URL / MODULITH_URL / KEYCLOAK_URL for staging or CI.
#
#   perf/run.sh smoke                 # low-intensity sanity check (start here)
#   perf/run.sh baseline              # core throughput / latency percentiles
#   perf/run.sh spike | soak
#   perf/run.sh edge                  # rate-limit shedding (uses the DEFAULT limit — see below)
#   perf/run.sh auth-write            # JWT + Postgres write path
#   perf/run.sh overhead              # gateway vs direct latency
#   perf/run.sh baseline RATE=200 HOLD=5m   # trailing KEY=VALUE become k6 --env overrides
#   SAVE=1 perf/run.sh baseline             # also export a summary to perf/out/ for diffing runs
#   OTEL=1 perf/run.sh baseline             # also stream live metrics to Grafana (otel-lgtm) via OTLP
#
# Throughput scenarios (baseline/spike/soak) are capped by the edge's 20 req/s-per-principal limit.
# To benchmark past it, restart the gateway with the limit raised:
#   GATEWAY_RATE_REPLENISH=100000 GATEWAY_RATE_BURST=100000 make gateway
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
# shellcheck disable=SC1091
[ -f "$root/docker/.env" ] && { set -a; . "$root/docker/.env"; set +a; }

: "${GATEWAY_URL:=http://localhost:${GATEWAY_PORT:-28090}}"
: "${MODULITH_URL:=http://localhost:${SERVER_PORT:-28080}}"
: "${KEYCLOAK_URL:=http://localhost:${KEYCLOAK_PORT:-28081}}"
export GATEWAY_URL MODULITH_URL KEYCLOAK_URL

name="${1:-smoke}"
shift || true
case "$name" in
  smoke)                    script="$here/smoke.js" ;;
  baseline|spike|soak)      script="$here/scenarios/$name.js" ;;
  edge|edge-enforcement)    script="$here/scenarios/edge-enforcement.js" ;;
  auth-write|auth)          script="$here/scenarios/auth-write.js" ;;
  overhead|gateway-vs-direct) script="$here/scenarios/gateway-vs-direct.js" ;;
  *) echo "unknown scenario: '$name' (try: smoke baseline spike soak edge auth-write overhead)" >&2; exit 2 ;;
esac

# Trailing KEY=VALUE args become k6 --env overrides.
env_args=()
for kv in "$@"; do env_args+=(--env "$kv"); done

# SAVE=1 (or SAVE=<path>) also writes a machine-readable summary under perf/out/ for diffing runs later.
out_args=()
if [ -n "${SAVE:-}" ]; then
  mkdir -p "$here/out"
  case "$SAVE" in 1 | true | yes) out_file="$here/out/${name}-$(date +%Y%m%d-%H%M%S).json" ;; *) out_file="$SAVE" ;; esac
  out_args+=(--summary-export "$out_file")
fi

# OTEL=1 also streams k6's metrics to the otel-lgtm stack over OTLP, so Grafana's "SMSOne · k6 Load"
# dashboard lights up live (metrics land in the `prometheus` datasource with a k6_ prefix).
otel_args=()
if [ -n "${OTEL:-}" ]; then
  export K6_OTEL_EXPORTER_PROTOCOL="${K6_OTEL_EXPORTER_PROTOCOL:-grpc}"
  export K6_OTEL_GRPC_EXPORTER_ENDPOINT="${K6_OTEL_GRPC_EXPORTER_ENDPOINT:-localhost:${OTLP_GRPC_PORT:-24317}}"
  export K6_OTEL_GRPC_EXPORTER_INSECURE="${K6_OTEL_GRPC_EXPORTER_INSECURE:-true}"
  export K6_OTEL_METRIC_PREFIX="${K6_OTEL_METRIC_PREFIX:-k6_}"
  export K6_OTEL_SERVICE_NAME="${K6_OTEL_SERVICE_NAME:-k6-$name}"
  otel_args+=(-o opentelemetry)
fi

echo "→ k6 run ${script#"$root"/}   gateway=$GATEWAY_URL  modulith=$MODULITH_URL"
[ -n "${SAVE:-}" ] && echo "  summary → ${out_file#"$root"/}"
[ -n "${OTEL:-}" ] && echo "  streaming OTLP → ${K6_OTEL_GRPC_EXPORTER_ENDPOINT} (Grafana: SMSOne · k6 Load)"
# ${arr[@]+…} guards the empty-array expansion under `set -u` on bash 3.2 (macOS default).
exec k6 run ${env_args[@]+"${env_args[@]}"} ${out_args[@]+"${out_args[@]}"} ${otel_args[@]+"${otel_args[@]}"} "$script"
