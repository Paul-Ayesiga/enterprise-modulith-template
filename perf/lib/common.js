// Shared config + helpers for the k6 perf scenarios.
// Everything is env-overridable so the same scripts run against local (2xxxx), staging, or CI.
import http from "k6/http";
import { fail } from "k6";
import { Counter, Rate } from "k6/metrics";

// Split responses so a run distinguishes the edge SHEDDING load (429 — expected when a single k6 box
// outruns the per-principal rate limit or per-consumer quota) from REAL failures (5xx / transport).
// The throughput SLOs sit on server_errors_5xx, NOT on k6's built-in http_req_failed, which lumps 429s
// in and so reads as a scary "88% failed" on a single-box run even when the backend is perfectly healthy.
export const rateLimited = new Counter("edge_rate_limited_429");
export const serverErrors = new Rate("server_errors_5xx");

/** Tally one response into the enforcement (429) vs real-error (5xx/transport) buckets. */
export function record(res) {
  if (res.status === 429) {
    rateLimited.add(1);
  }
  serverErrors.add(res.status >= 500 || res.status === 0);
}

export const GATEWAY = __ENV.GATEWAY_URL || "http://localhost:28090";
export const MODULITH = __ENV.MODULITH_URL || "http://localhost:28080";
export const KEYCLOAK = __ENV.KEYCLOAK_URL || "http://localhost:28081";
export const REALM = __ENV.KEYCLOAK_REALM || "smsone";
export const CLIENT = __ENV.KEYCLOAK_CLIENT || "smsone-web";
export const USERNAME = __ENV.PERF_USER || "paul";
export const PASSWORD = __ENV.PERF_PASSWORD || "Paul123";
export const SCOPE = __ENV.KEYCLOAK_SCOPE || "organization";

// A representative authenticated READ (a small page of settings) and an idempotent WRITE (upsert a
// setting). Override to point the load at your own endpoints.
export const READ_PATH = __ENV.READ_PATH || "/api/v1/settings?page%5Bsize%5D=5";
export const WRITE_PATH = __ENV.WRITE_PATH || "/api/v1/settings/perf.probe";

/** Fetch one bearer token via the Keycloak password grant. Call once in setup(); reuse across VUs. */
export function getToken() {
  const res = http.post(
    `${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token`,
    { grant_type: "password", client_id: CLIENT, scope: SCOPE, username: USERNAME, password: PASSWORD },
    { tags: { name: "token" } }
  );
  if (res.status !== 200) {
    fail(`token request failed (${res.status}) — is Keycloak up at ${KEYCLOAK}? body: ${res.body}`);
  }
  return res.json("access_token");
}

export function bearer(token) {
  return { Authorization: `Bearer ${token}`, Accept: "application/json" };
}
