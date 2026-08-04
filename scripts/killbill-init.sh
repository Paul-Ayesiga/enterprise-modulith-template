#!/usr/bin/env bash
# Create the Kill Bill tenant + simple catalog straight against the Kill Bill REST API — the same two
# calls the app's BILLING_BOOTSTRAP makes on boot (ensureTenant + ensureSimplePlan), but standalone,
# so `make killbill-init` readies billing without booting the app.
#
# Idempotent: an existing tenant or plan answers 409, which we treat as success. Reads the same
# variables the app reads (sourced from docker/.env by the Makefile target); dev defaults otherwise.
set -euo pipefail

KB="${KILLBILL_URL:-http://localhost:${KILLBILL_PORT:-8082}}"
KEY="${KILLBILL_API_KEY:-smsone}"
SECRET="${KILLBILL_API_SECRET:-smsone-secret}"
USER="${KILLBILL_USER:-admin}"
PASS="${KILLBILL_PASSWORD:-password}"
CUR="${BILLING_CURRENCY:-USD}"
PRO="${BILLING_PLAN_PRO:-pro-monthly}"
ENT="${BILLING_PLAN_ENTERPRISE:-enterprise-monthly}"

AUTH=(-u "${USER}:${PASS}")
COMMON=(-H "Content-Type: application/json" -H "X-Killbill-CreatedBy: killbill-init")
TENANT=(-H "X-Killbill-ApiKey: ${KEY}" -H "X-Killbill-ApiSecret: ${SECRET}")

report() { printf '  %-26s %s\n' "$1" "$2"; }   # label -> HTTP status

echo "Kill Bill init → ${KB} (tenant '${KEY}')"

# 1) the tenant (the whole platform's billing world)
code=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "${COMMON[@]}" -X POST \
  -d "{\"apiKey\":\"${KEY}\",\"apiSecret\":\"${SECRET}\"}" \
  "${KB}/1.0/kb/tenants" || echo "000")
report "tenant ${KEY}" "${code}"

# 2) the catalog (the plans you sell) — "planId:ProductName:amount"
for spec in "${PRO}:Pro:49.00" "${ENT}:Enterprise:499.00"; do
  id="${spec%%:*}"; rest="${spec#*:}"; product="${rest%%:*}"; amount="${rest##*:}"
  code=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "${COMMON[@]}" "${TENANT[@]}" -X POST \
    -d "{\"planId\":\"${id}\",\"productName\":\"${product}\",\"productCategory\":\"BASE\",\"currency\":\"${CUR}\",\"amount\":${amount},\"billingPeriod\":\"MONTHLY\",\"trialLength\":0,\"trialTimeUnit\":\"DAYS\"}" \
    "${KB}/1.0/kb/catalog/simplePlan" || echo "000")
  report "plan ${id}" "${code}"
done

echo "Done — 201=created, 409/other-4xx=already-exists (fine). Point Kaui at ${KEY}/${SECRET}."
