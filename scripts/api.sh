#!/usr/bin/env bash
# Authenticated API call against the running app. Fetches a token automatically.
#
#   scripts/api.sh GET  "/api/v1/settings?page[size]=5"
#   scripts/api.sh GET  /api/v1/feature-flags
#   scripts/api.sh PUT  /api/v1/settings/my.key -d '{"value":"hello","description":"demo"}'
#   scripts/api.sh GET  /api/v1/admin/users                    # platform user listing (platform-support)
#
# Override with env: API_BASE, API_USER, API_PASSWORD, TOKEN (reuse an existing token).
# Defaults to the realm's platform admin (paul); set API_USER/API_PASSWORD for a user you added.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
[ -f "$root/docker/.env" ] && { set -a; . "$root/docker/.env"; set +a; }

API_BASE="${API_BASE:-http://localhost:${SERVER_PORT:-8080}}"
TOKEN="${TOKEN:-$("$root/scripts/token.sh" "${API_USER:-paul}" ${API_PASSWORD:+"$API_PASSWORD"})}"

method="${1:?usage: api.sh METHOD PATH [curl-args...]}"; shift
path="${1:?usage: api.sh METHOD PATH [curl-args...]}"; shift

# Percent-encode reserved brackets so page[size]/page[after] can be typed naturally.
# Kept for portability: this deployment sets server.tomcat.relaxed-query-chars "[,]" and accepts
# raw brackets, but a deployment that has not will reject them before any filter runs, and
# %5B/%5D is what a spec-compliant client sends anyway.
path="${path//\[/%5B}"; path="${path//\]/%5D}"

curl -sS -X "$method" "${API_BASE}${path}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  "$@"
echo
