// Core load — a steady, climbing request rate on a representative read through the gateway.
// Answers: what throughput does the system sustain, and what are the latency percentiles under it?
//
// NOTE: the default /api/v1/** route is rate-limited to 20 req/s per principal (burst 40). A single
// k6 box is one principal, so to benchmark real throughput start the gateway with the limit raised:
//   GATEWAY_RATE_REPLENISH=100000 GATEWAY_RATE_BURST=100000 make gateway
// or point this script at the modulith directly:  TARGET_URL=http://localhost:28080 k6 run ...
import http from "k6/http";
import { check } from "k6";
import { GATEWAY, READ_PATH, bearer, getToken, record } from "../lib/common.js";

const TARGET = __ENV.TARGET_URL || GATEWAY;
const RATE = Number(__ENV.RATE || 100); // peak requests/second

export const options = {
  scenarios: {
    baseline: {
      executor: "ramping-arrival-rate",
      startRate: 10,
      timeUnit: "1s",
      preAllocatedVUs: Number(__ENV.PRE_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 300),
      stages: [
        { target: Math.max(1, Math.round(RATE * 0.25)), duration: "30s" },
        { target: Math.max(1, Math.round(RATE * 0.5)), duration: "1m" },
        { target: RATE, duration: "1m" },
        { target: RATE, duration: __ENV.HOLD || "2m" },
        { target: 0, duration: "30s" }
      ]
    }
  },
  thresholds: {
    // The SLO gates REAL failures (5xx/transport), not 429s — a single box hammering one principal is
    // rate-limited by design; those 429s show as edge_rate_limited_429, not as a failed SLO.
    server_errors_5xx: ["rate<0.01"],
    http_req_duration: ["p(95)<300", "p(99)<800"]
  }
};

export function setup() {
  return { token: getToken() };
}

export default function (data) {
  const res = http.get(`${TARGET}${READ_PATH}`, { headers: bearer(data.token) });
  record(res);
  check(res, { "status is 200 or 429 (never 5xx)": (r) => r.status === 200 || r.status === 429 });
}
