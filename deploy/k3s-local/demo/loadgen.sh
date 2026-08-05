#!/bin/sh
# Availability sampler: hammer /api/v1/me through Traefik -> gateway -> modulith while a disruption
# (rolling update / pod kill / scale) runs. Tallies 200 vs non-200; re-mints the token on expiry.
# Runs IN-cluster (as the `loadgen` pod), so it needs no /etc/hosts wiring.
KC=http://auth.smsone.local
API=http://traefik.kube-system
DURATION="${DURATION:-300}"
GAP="${GAP:-0.3}"

mint() {
  curl -fsS -X POST "$KC/realms/smsone/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=smsone-web -d scope=organization \
    -d username=paul -d password=Paul123 \
    | grep -o '"access_token":"[^"]*"' | sed 's/.*:"//;s/"$//'
}

TOKEN=$(mint)
[ -n "$TOKEN" ] || { echo "FATAL: could not mint initial token"; exit 1; }

ok=0; bad=0; n=0
END=$(( $(date +%s) + DURATION ))
echo "loadgen start: duration=${DURATION}s gap=${GAP}s target=$API (Host api.smsone.local)"
while [ "$(date +%s)" -lt "$END" ]; do
  n=$((n+1))
  code=$(curl -s -o /dev/null -w '%{http_code}' -H "Host: api.smsone.local" -H "Authorization: Bearer $TOKEN" "$API/api/v1/me")
  if [ "$code" = "200" ]; then
    ok=$((ok+1))
  else
    # A non-200 might just be an expired token — re-mint once and retry before counting it against uptime.
    TOKEN=$(mint)
    code2=$(curl -s -o /dev/null -w '%{http_code}' -H "Host: api.smsone.local" -H "Authorization: Bearer $TOKEN" "$API/api/v1/me")
    if [ "$code2" = "200" ]; then ok=$((ok+1)); else bad=$((bad+1)); echo "BAD  n=$n code=$code retry=$code2 t=$(date +%H:%M:%S)"; fi
  fi
  [ $((n % 25)) -eq 0 ] && echo "..  n=$n ok=$ok bad=$bad t=$(date +%H:%M:%S)"
  sleep "$GAP"
done
echo "RESULT total=$n ok=$ok bad=$bad"
sleep 60   # linger so `kubectl logs loadgen` can be read after the disruption completes
