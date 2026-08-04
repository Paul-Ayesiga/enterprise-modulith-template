#!/usr/bin/env bash
# Restore a pg_dump custom-format dump into an EMPTY database. Deliberately refuses to touch an
# existing one — dropping is a human decision typed by a human (docs/runbooks/restore.md walks
# the full incident procedure, including the order across app/Keycloak/Kill Bill databases).
#
#   PGHOST=... PGUSER=... PGPASSWORD=... ./scripts/restore-postgres.sh modulith /backups/modulith-20260801-021000.dump
set -euo pipefail

DB="${1:?usage: restore-postgres.sh <database> <dump-file>}"
DUMP="${2:?usage: restore-postgres.sh <database> <dump-file>}"

[ -f "$DUMP" ] || { echo "no such dump: $DUMP" >&2; exit 1; }

TABLES=$(psql -d "$DB" -tAc "select count(*) from pg_tables where schemaname='public'" 2>/dev/null || echo "-")
if [ "$TABLES" != "0" ]; then
  echo "refusing: database '$DB' is not empty (public tables: $TABLES)." >&2
  echo "create a fresh one (createdb ${DB}_restore) or drop deliberately, then re-run." >&2
  exit 1
fi

echo ">> pg_restore ${DUMP} -> ${DB}"
pg_restore -d "$DB" --no-owner --exit-on-error "$DUMP"
echo ">> restored. Row-count spot check:"
psql -d "$DB" -c "select 'organization' t, count(*) from organization union all select 'app_user', count(*) from app_user;" || true
