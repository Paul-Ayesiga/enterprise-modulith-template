#!/usr/bin/env bash
# Logical Postgres backup (pg_dump custom format) with age-based rotation — the same dump the
# Helm CronJob takes, runnable by hand or from cron on any box. Restore: scripts/restore-postgres.sh
# (procedure + order: docs/runbooks/restore.md).
#
#   PGHOST=localhost PGPORT=25432 PGUSER=modulith PGPASSWORD=... \
#     ./scripts/backup-postgres.sh modulith /var/backups/smsone 14
set -euo pipefail

DB="${1:?usage: backup-postgres.sh <database> <backup-dir> [retention-days]}"
DIR="${2:?usage: backup-postgres.sh <database> <backup-dir> [retention-days]}"
RETENTION_DAYS="${3:-14}"

mkdir -p "$DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$DIR/${DB}-${STAMP}.dump"

echo ">> pg_dump ${DB} -> ${OUT}"
pg_dump -d "$DB" -Fc -f "$OUT"
echo ">> wrote $(du -h "$OUT" | cut -f1)"

echo ">> pruning dumps older than ${RETENTION_DAYS} days"
find "$DIR" -name "${DB}-*.dump" -mtime "+${RETENTION_DAYS}" -print -delete

# The dump is only a backup once it also exists somewhere this machine can die without.
# Ship it: rclone/aws s3 cp/restic — whatever the environment uses. This script stays local.
echo ">> done. Latest: $(ls -t "$DIR"/${DB}-*.dump | head -1)"
