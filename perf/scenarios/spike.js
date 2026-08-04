// Core load — a sudden burst on top of a calm baseline, then back down.
// Answers: does the system absorb a traffic spike and recover, or does latency stay degraded after?
//
// Same rate-limit caveat as baseline.js — raise GATEWAY_RATE_* or set TARGET_URL to the modulith to
// see raw spike behaviour rather than the edge shedding load (which is itself a valid thing to watch).
import http from "k6/http";
import { check } from "k6";
import { GATEWAY, READ_PATH, bearer, getToken, record } from "../lib/common.js";

const TARGET = __ENV.TARGET_URL || GATEWAY;
const CALM = Number(__ENV.CALM || 20); // requests/second between spikes
const PEAK = Number(__ENV.PEAK || 400); // requests/second at the spike

export const options = {
  scenarios: {
    spike: {
      executor: "ramping-arrival-rate",
      startRate: CALM,
      timeUnit: "1s",
      preAllocatedVUs: Number(__ENV.PRE_VUS || 100),
      maxVUs: Number(__ENV.MAX_VUS || 600),
      stages: [
        { target: CALM, duration: "30s" }, // settle
        { target: PEAK, duration: "10s" }, // slam
        { target: PEAK, duration: "30s" }, // hold the peak
        { target: CALM, duration: "10s" }, // drop
        { target: CALM, duration: "1m" } // watch it recover
      ]
    }
  },
  thresholds: {
    // Post-spike recovery is the real signal — keep the aggregate percentiles honest.
    http_req_duration: ["p(95)<800", "p(99)<2000"],
    // Gate real failures (5xx/transport); 429s from the burst outrunning the limit are expected and
    // surface separately as edge_rate_limited_429.
    server_errors_5xx: ["rate<0.01"]
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
