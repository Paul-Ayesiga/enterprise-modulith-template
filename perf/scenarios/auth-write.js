// Auth + write path — authenticated reads (the JWT/JWKS validation overhead) mixed with idempotent
// writes (the Postgres/Hikari round-trip). Read and write latency are tracked separately so you can
// see what a mutation actually costs versus a cached-token read.
//
// Runs at a rate under the default 20/s per-principal limit so nothing is shed. The default write is
// an upsert of a throwaway setting key (perf.probe) — override WRITE_PATH/body for your own endpoint.
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";
import { GATEWAY, READ_PATH, WRITE_PATH, bearer, getToken } from "../lib/common.js";

const readLatency = new Trend("read_latency", true);
const writeLatency = new Trend("write_latency", true);

const TARGET = __ENV.TARGET_URL || GATEWAY;
const RATE = Number(__ENV.RATE || 15);

export const options = {
  scenarios: {
    mixed: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: __ENV.DURATION || "1m",
      preAllocatedVUs: 30,
      maxVUs: 100
    }
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    read_latency: ["p(95)<300"],
    write_latency: ["p(95)<500"]
  }
};

export function setup() {
  return { token: getToken() };
}

export default function (data) {
  const headers = bearer(data.token);
  // Roughly one write for every four reads — a read-heavy API shape.
  if (__ITER % 5 === 0) {
    const body = JSON.stringify({ value: `perf-${__VU}-${__ITER}` });
    const res = http.put(`${TARGET}${WRITE_PATH}`, body, {
      headers: { ...headers, "Content-Type": "application/json" },
      tags: { op: "write" }
    });
    writeLatency.add(res.timings.duration);
    check(res, { "write 2xx": (r) => r.status >= 200 && r.status < 300 });
  } else {
    const res = http.get(`${TARGET}${READ_PATH}`, { headers, tags: { op: "read" } });
    readLatency.add(res.timings.duration);
    check(res, { "read 200": (r) => r.status === 200 });
  }
}
