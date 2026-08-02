// Core load — a moderate rate held for a long stretch. Answers: does latency creep, memory climb, or
// a connection pool leak over time? Watch /actuator/prometheus + Grafana across the whole run.
//
// Default 10 minutes; override for a real soak: DURATION=2h perf/run.sh soak. Same rate-limit caveat
// as baseline.js if you push RATE above the default per-principal limit.
import http from "k6/http";
import { check } from "k6";
import { GATEWAY, READ_PATH, bearer, getToken, record } from "../lib/common.js";

const TARGET = __ENV.TARGET_URL || GATEWAY;
const RATE = Number(__ENV.RATE || 15);

export const options = {
  scenarios: {
    soak: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: __ENV.DURATION || "10m",
      preAllocatedVUs: Number(__ENV.PRE_VUS || 30),
      maxVUs: Number(__ENV.MAX_VUS || 100)
    }
  },
  thresholds: {
    // Real failures only; sustained 429s (if you soak above the limit) show as edge_rate_limited_429.
    server_errors_5xx: ["rate<0.01"],
    // Flat percentiles over a long run are the goal — creep here means a leak or contention.
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
