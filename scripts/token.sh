#!/usr/bin/env bash
# Print a Keycloak access token for a dev user.
#
#   scripts/token.sh                 # paul — the platform super-admin (ADMIN+USER)
#   scripts/token.sh paul Paul123    # explicit password
#   scripts/token.sh someone pass    # any user you added to the realm yourself
#   TOKEN=$(scripts/token.sh)        # capture into a variable
#
# Ports are read from docker/.env (falls back to template defaults).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
[ -f "$root/docker/.env" ] && { set -a; . "$root/docker/.env"; set +a; }

KC_PORT="${KEYCLOAK_PORT:-8081}"
REALM="${KEYCLOAK_REALM:-smsone}"
CLIENT="${KEYCLOAK_CLIENT:-smsone-web}"
USER_NAME="${1:-paul}"
# The realm ships exactly one dev user (paul / Paul123); pass arg 2 for any user you add yourself.
PASSWORD="${2:-${KEYCLOAK_PASSWORD:-Paul123}}"
# Request the optional 'organization' scope so the token carries the active-org claim that org-scoped
# @PreAuthorize checks require. Use e.g. KEYCLOAK_SCOPE='organization:acme' to pin a single org.
SCOPE="${KEYCLOAK_SCOPE:-organization}"

resp="$(curl -fsS -X POST \
  "http://localhost:${KC_PORT}/realms/${REALM}/protocol/openid-connect/token" \
  -d grant_type=password -d "client_id=${CLIENT}" -d "scope=${SCOPE}" \
  -d "username=${USER_NAME}" -d "password=${PASSWORD}" 2>/dev/null || true)"

token="$(printf '%s' "$resp" | { \
  if command -v jq >/dev/null 2>&1; then jq -r '.access_token // empty'; \
  else python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null; fi; })"

if [ -z "$token" ]; then
  echo "ERROR: could not obtain token for '${USER_NAME}' from http://localhost:${KC_PORT}/realms/${REALM}" >&2
  echo "Response: ${resp:-<empty>}" >&2
  exit 1
fi
printf '%s\n' "$token"
