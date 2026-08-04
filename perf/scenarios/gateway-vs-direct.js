// Gateway overhead — the SAME read issued through the edge and straight at the modulith, tagged so
// their latency distributions separate. The gap (edge p95 − direct p95) is what the gateway costs per
// request: routing, the filter chain, auth, and the extra network hop.
//
// Runs at a rate under the default per-principal limit so the gateway path isn't shedding load — an
// apples-to-apples latency comparison, not a throughput test.
import http from "k6/http";
import { check } from "k6";
import { GATEWAY, MODULITH, READ_PATH, bearer, getToken } from "../lib/common.js";

const RATE = Number(__ENV.RATE || 15);

export const options = {
  scenarios: {
    compare: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: __ENV.DURATION || "1m",
      preAllocatedVUs: 30,
      maxVUs: 100
    }
  },
  thresholds: {
    "http_req_duration{route:gateway}": ["p(95)<300"],
    "http_req_duration{route:direct}": ["p(95)<250"],
    http_req_failed: ["rate<0.01"]
  }
};

export function setup() {
  return { token: getToken() };
}

export default function (data) {
  const headers = bearer(data.token);

  const viaGateway = http.get(`${GATEWAY}${READ_PATH}`, { headers, tags: { route: "gateway" } });
  check(viaGateway, { "gateway 200": (r) => r.status === 200 });

  const direct = http.get(`${MODULITH}${READ_PATH}`, { headers, tags: { route: "direct" } });
  check(direct, { "direct 200": (r) => r.status === 200 });
}
