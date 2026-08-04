// A low-intensity smoke: proves the wiring end to end (token → gateway → modulith, plus direct) at a
// rate below the edge's token bucket, so nothing here should 429. Not a benchmark — a sanity check.
//   k6 run perf/smoke.js
import http from "k6/http";
import { check, group, sleep } from "k6";
import { GATEWAY, MODULITH, READ_PATH, bearer, getToken } from "./lib/common.js";

export const options = {
  scenarios: {
    smoke: { executor: "constant-vus", vus: 4, duration: "20s" }
  },
  thresholds: {
    checks: ["rate>0.95"],
    http_req_failed: ["rate<0.10"],
    "http_req_duration{route:gateway}": ["p(95)<500"],
    "http_req_duration{route:direct}": ["p(95)<400"]
  }
};

export function setup() {
  const token = getToken();
  check(token, { "obtained a token": (t) => !!t });
  return { token };
}

export default function (data) {
  const params = { headers: bearer(data.token) };

  group("read via gateway", () => {
    const res = http.get(`${GATEWAY}${READ_PATH}`, { ...params, tags: { route: "gateway" } });
    check(res, { "gateway read 200": (r) => r.status === 200 });
  });

  group("read direct", () => {
    const res = http.get(`${MODULITH}${READ_PATH}`, { ...params, tags: { route: "direct" } });
    check(res, { "direct read 200": (r) => r.status === 200 });
  });

  sleep(0.3);
}
