#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# BCQ-G4.2-D: genuine PostgreSQL 16 physical basebackup + archived WAL PITR.
# It is an ISOLATED CI drill, NOT an assertion of production RPO/RTO.
if [[ -n "${ZAVOD_DB_URL:-}" || -n "${PGDATA:-}" ]]; then
  echo 'Refusing to run PITR with external DB/PGDATA configured' >&2
  exit 2
fi
TMP=$(mktemp -d)
SOURCE="zavod-g42-pitr-src-$$"
RESTORE="zavod-g42-pitr-restore-$$"
cleanup() {
  docker rm -f "$SOURCE" "$RESTORE" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT
mkdir -p "$TMP/archive" "$TMP/restore"
chmod 777 "$TMP/archive"
docker run -d --name "$SOURCE" \
  -e POSTGRES_DB=g42 -e POSTGRES_USER=zavod -e POSTGRES_PASSWORD=pitr-test-only \
  -v "$TMP/archive:/wal_archive" postgres:16.6 \
  -c wal_level=replica -c archive_mode=on \
  -c "archive_command=test ! -f /wal_archive/%f && cp %p /wal_archive/%f" \
  -c archive_timeout=1s >/dev/null
for _ in $(seq 1 80); do
  if docker exec "$SOURCE" pg_isready -U zavod -d g42 >/dev/null 2>&1; then break; fi
  sleep 0.5
done
docker exec "$SOURCE" pg_isready -U zavod -d g42 >/dev/null
psql_source() {
  docker exec -i "$SOURCE" psql -q -t -A -v ON_ERROR_STOP=1 -U zavod -d g42 -c "$1"
}
psql_source "CREATE TABLE pitr_marker(marker text PRIMARY KEY, committed_at timestamptz NOT NULL DEFAULT clock_timestamp());" >/dev/null
psql_source "INSERT INTO pitr_marker(marker) VALUES ('baseline');" >/dev/null

# PostgreSQL physical backup before the recovery target; include WAL via -X stream.
docker exec -e PGPASSWORD=pitr-test-only "$SOURCE" \
  pg_basebackup -h 127.0.0.1 -U zavod -D /tmp/g42-base -Fp -X stream -c fast >/dev/null
docker cp "$SOURCE:/tmp/g42-base/." "$TMP/restore/"
test -s "$TMP/restore/PG_VERSION"
# Commit the target, mark a named restore boundary, then commit an excluded event.
psql_source "INSERT INTO pitr_marker(marker) VALUES ('target-committed');" >/dev/null
psql_source "SELECT pg_create_restore_point('g42_verified_cut');" >/dev/null
psql_source "INSERT INTO pitr_marker(marker) VALUES ('must-be-excluded');" >/dev/null
psql_source 'SELECT pg_switch_wal();' >/dev/null

# Ensure the WAL needed for recovery was actually archived.
archived=false
for _ in $(seq 1 80); do
  count=$(psql_source 'SELECT archived_count FROM pg_stat_archiver;')
  if [[ "$count" -ge 1 ]]; then archived=true; break; fi
  sleep 0.5
done
[[ "$archived" == true ]] || { echo 'PITR WAL archive did not complete' >&2; exit 1; }

# The backup is before target-committed; only archived WAL can reproduce it.
touch "$TMP/restore/recovery.signal"
cat >> "$TMP/restore/postgresql.auto.conf" <<'PGCONFIG'
restore_command = 'cp /wal_archive/%f %p'
recovery_target_name = 'g42_verified_cut'
recovery_target_inclusive = on
recovery_target_action = 'promote'
PGCONFIG
# A separate PostgreSQL server restores from the base backup and archived WAL.
start_time=$(date +%s)
docker run -d --name "$RESTORE" \
  -e POSTGRES_PASSWORD=pitr-test-only \
  -v "$TMP/restore:/var/lib/postgresql/data" \
  -v "$TMP/archive:/wal_archive:ro" \
  postgres:16.6 >/dev/null
ready=false
for _ in $(seq 1 120); do
  if docker exec "$RESTORE" pg_isready -U zavod -d g42 >/dev/null 2>&1; then
    ready=true; break
  fi
  if [[ "$(docker inspect -f '{{.State.Running}}' "$RESTORE")" != true ]]; then
    docker logs "$RESTORE" >&2; exit 1
  fi
  sleep 0.5
done
[[ "$ready" == true ]] || { docker logs "$RESTORE" >&2; exit 1; }
result=$(docker exec "$RESTORE" psql -t -A -v ON_ERROR_STOP=1 -U zavod -d g42 \
  -c 'SELECT string_agg(marker, '\''|'\'' ORDER BY marker) FROM pitr_marker;' | tr -d '[:space:]')
[[ "$result" == 'baseline|target-committed' ]] || {
  echo "PITR content mismatch: $result" >&2
  docker logs "$RESTORE" >&2
  exit 1
}
rto_seconds=$(($(date +%s)-start_time))
echo "BCQ-G4.2 PITR PASS: archived-WAL target reached; target committed retained; subsequent commit excluded"
echo "BCQ-G4.2 measured CI drill RTO_SECONDS=$rto_seconds"
echo "BCQ-G4.2 observed drill RPO=0 committed marker loss at selected restore point"
echo "NOTE: CI-local physical restore is not production PITR/RPO/RTO certification."
