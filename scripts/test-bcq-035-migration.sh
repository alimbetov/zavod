#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${ZAVOD_DB_URL:-}" ]]; then
  echo "Refusing to run migration qualification with ZAVOD_DB_URL set" >&2
  exit 2
fi
name="zavod-bcq-migration-$$"
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$name" \
  -e POSTGRES_DB=zavod -e POSTGRES_USER=zavod -e POSTGRES_PASSWORD=ci-only \
  postgres:16.6 >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$name" pg_isready -U zavod -d zavod >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$name" pg_isready -U zavod -d zavod >/dev/null
# pg_isready can succeed during the Docker image's temporary init postmaster.
# Require successful SQL on the intended database after bootstrap settles.
ready_sql=false
for _ in $(seq 1 80); do
  if docker exec "$name" psql -v ON_ERROR_STOP=1 -U zavod -d zavod \
      -c 'SELECT 1' >/dev/null 2>&1; then ready_sql=true; break; fi
  sleep 0.5
done
[[ "$ready_sql" == true ]] || { docker logs "$name" >&2; exit 1; }
# Minimal pre-upgrade schema for the exact ALTER TABLE contract. This is NOT
# a substitute for a rehearsal against the actual production schema.
docker exec -i "$name" psql -v ON_ERROR_STOP=1 -U zavod -d zavod <<'SQL'
CREATE TABLE blueprint_approval(id varchar(64) PRIMARY KEY);
CREATE TABLE plant_activation_set(id varchar(36) PRIMARY KEY);
INSERT INTO blueprint_approval(id) VALUES('legacy:2');
INSERT INTO plant_activation_set(id) VALUES('legacy-set');
SQL
for pass in 1 2; do
  docker exec -i "$name" psql -v ON_ERROR_STOP=1 -U zavod -d zavod < scripts/sql/bcq-035-revoke-restore.sql
done
result="$(docker exec -i "$name" psql -v ON_ERROR_STOP=1 -At -U zavod -d zavod <<'SQL'
SELECT count(*) FROM information_schema.columns WHERE
 (table_name='blueprint_approval' AND column_name IN ('revoked_by','revoked_at','revocation_reason'))
 OR (table_name='plant_activation_set' AND column_name IN ('restored_from_set_id','change_reason'));
SQL
)"
test "$result" = "5"
docker exec -i "$name" psql -v ON_ERROR_STOP=1 -U zavod -d zavod <<'SQL'
DO $$
BEGIN
 IF (SELECT count(*) FROM blueprint_approval WHERE id='legacy:2')<>1
    OR (SELECT count(*) FROM plant_activation_set WHERE id='legacy-set')<>1 THEN
   RAISE EXCEPTION 'Historical records mutated by BCQ-035 migration';
 END IF;
 IF EXISTS (SELECT 1 FROM information_schema.columns WHERE
   table_name='blueprint_approval' AND column_name='revoked_at' AND data_type <> 'timestamp with time zone') THEN
   RAISE EXCEPTION 'revoked_at has wrong type';
 END IF;
END $$;
SQL
echo "BCQ-035 migration smoke PASS: PostgreSQL 16, additive, repeatable, historical records preserved"
