// Edge enforcement — flood a rate-limited route from a single principal and prove the token bucket
// sheds the excess with 429s while still serving the allowed share. This one runs against the DEFAULT
// production limit (20 req/s, burst 40), so do NOT raise GATEWAY_RATE_* here — the shedding is the point.
//
// Two other enforcement mechanisms are documented in docs/PERF_PLAN.md as variants:
//   • per-consumer quota (a slower window than the token bucket — see the plan to exercise it)
//   • circuit breaker (needs a deliberately-failing route — see the plan for the temporary route to add)
import http from "k6/http";
import { check } from "k6";
import { Counter, Rate } from "k6/metrics";
import { GATEWAY, READ_PATH, bearer, getToken } from "../lib/common.js";

const allowed = new Counter("edge_allowed_200");
const limited = new Counter("edge_limited_429");
const limitedRatio = new Rate("edge_limited_ratio");

export const options = {
  scenarios: {
    flood: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 200), // ~10x the 20/s default limit
      timeUnit: "1s",
      duration: __ENV.DURATION || "1m",
      preAllocatedVUs: 100,
      maxVUs: 300
    }
  },
  thresholds: {
    // The bucket must fire (some 429s) AND keep letting the allowed share through (some 200s)...
    edge_limited_429: ["count>0"],
    edge_allowed_200: ["count>0"],
    // ...and at ~10x over, most requests should be shed. That ratio IS the protection working.
    edge_limited_ratio: ["rate>0.5"]
  }
};

export function setup() {
  return { token: getToken() };
}

export default function (data) {
  const res = http.get(`${GATEWAY}${READ_PATH}`, { headers: bearer(data.token) });
  if (res.status === 200) {
    allowed.add(1);
    limitedRatio.add(false);
  } else if (res.status === 429) {
    limited.add(1);
    limitedRatio.add(true);
  }
  // A healthy edge under flood returns only 200 (served) or 429 (shed) — never a 5xx.
  check(res, { "200 or 429, never 5xx": (r) => r.status === 200 || r.status === 429 });
}
