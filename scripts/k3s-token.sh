#!/usr/bin/env bash
# Print a Keycloak access token minted through the LOCAL k3s ingress. Needs `auth.smsone.local` in the
# Mac's /etc/hosts pointing at the VM IP (see deploy/k3s-local/README.md). Mirrors scripts/token.sh but
# points at the cluster's issuer host so the token's `iss` matches what the in-cluster app validates.
#
#   scripts/k3s-token.sh                 # paul / Paul123
#   TOKEN=$(scripts/k3s-token.sh)
set -euo pipefail

KC="${KC:-http://auth.smsone.local}"
REALM="${REALM:-smsone}"
CLIENT="${CLIENT:-smsone-web}"
USER_NAME="${1:-paul}"
PASSWORD="${2:-${KEYCLOAK_PASSWORD:-Paul123}}"
SCOPE="${SCOPE:-organization}"

resp="$(curl -fsS -X POST "$KC/realms/${REALM}/protocol/openid-connect/token" \
  -d grant_type=password -d "client_id=${CLIENT}" -d "scope=${SCOPE}" \
  -d "username=${USER_NAME}" -d "password=${PASSWORD}" 2>/dev/null || true)"

token="$(printf '%s' "$resp" | { \
  if command -v jq >/dev/null 2>&1; then jq -r '.access_token // empty'; \
  else python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null; fi; })"

if [ -z "$token" ]; then
  echo "ERROR: no token from ${KC}/realms/${REALM}. Is 'auth.smsone.local' in /etc/hosts -> VM IP?" >&2
  echo "Response: ${resp:-<empty>}" >&2
  exit 1
fi
printf '%s\n' "$token"
