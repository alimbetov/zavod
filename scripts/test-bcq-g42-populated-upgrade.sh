#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Destructive qualification NEVER points to an external DB.
if [[ -n "${ZAVOD_DB_URL:-}" || -n "${PGDATA:-}" ]]; then
  echo 'Refusing to run destructive database upgrade qualification with external DB/PGDATA configured' >&2
  exit 2
fi
NAME="zavod-g42-upgrade-$$"
TMP=$(mktemp -d)
SEED_PID=""
cleanup() {
  if [[ -n "$SEED_PID" ]]; then kill -9 "$SEED_PID" 2>/dev/null || true; fi
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT
docker run -d --name "$NAME" -p 127.0.0.1::5432 \
  -e POSTGRES_DB=zavod -e POSTGRES_USER=zavod -e POSTGRES_PASSWORD=g42-migration-only \
  postgres:16.6 >/dev/null
for _ in $(seq 1 80); do
  if docker exec "$NAME" psql -U zavod -d zavod -tAc 'SELECT 1' >/dev/null 2>&1; then break; fi
  sleep 0.5
done
docker exec "$NAME" psql -U zavod -d zavod -v ON_ERROR_STOP=1 -c 'SELECT 1' >/dev/null
PGPORT=$(docker port "$NAME" 5432/tcp | head -1 | awk -F: '{print $NF}')
[[ "$PGPORT" =~ ^[0-9]+$ ]] || { echo 'Could not discover Docker PostgreSQL port' >&2; exit 1; }
mvn -B --no-transfer-progress -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/g42-migration-classpath.txt -DincludeScope=test
CP="target/test-classes:target/classes:$(cat target/g42-migration-classpath.txt)"
export ZAVOD_DB_URL="jdbc:postgresql://127.0.0.1:$PGPORT/zavod"
export ZAVOD_DB_USER=zavod
export ZAVOD_DB_PASSWORD=g42-migration-only
export SPRING_JPA_HIBERNATE_DDL_AUTO=update

# Seed real populated application state in separate JVM; the source remains intact.
java -cp "$CP" kz.zavod.blueprint.BcqRecoveryProcess seed \
  "$TMP/manifest.properties" "$TMP/ready" > "$TMP/seed.log" 2>&1 &
SEED_PID=$!
for _ in $(seq 1 120); do
  [[ -s "$TMP/ready" ]] && break
  if ! kill -0 "$SEED_PID" 2>/dev/null; then cat "$TMP/seed.log" >&2;exit 1;fi
  sleep 0.5
done
[[ -s "$TMP/ready" ]] || { cat "$TMP/seed.log" >&2;exit 1; }
kill -9 "$SEED_PID"
wait "$SEED_PID" 2>/dev/null || true
SEED_PID=""

# A physical user-data snapshot is restored to a SEPARATE database.
docker exec "$NAME" pg_dump -U zavod -Fc zavod > "$TMP/source.dump"
test -s "$TMP/source.dump"
docker exec "$NAME" createdb -U zavod zavod_stage
docker exec -i "$NAME" pg_restore -U zavod -d zavod_stage --no-owner --no-acl \
  < "$TMP/source.dump"

STAGE=(docker exec -i "$NAME" psql -v ON_ERROR_STOP=1 -U zavod -d zavod_stage)
# Emulate the earlier schema version by removing ONLY recently introduced
# G4/G4.2 tables. Existing Blueprint, Approval, Site and Selection rows remain.
# THIS IS AN EMULATED LEGACY SCHEMA, NOT A REAL PRODUCTION SNAPSHOT.
cat <<'SQL' | "${STAGE[@]}" >/dev/null
DROP TABLE business_transition;
DROP TABLE activation_transition;
DROP TABLE activation_command;
DO $$
BEGIN
 IF (SELECT count(*) FROM plant_activation_set)<>3
 OR (SELECT count(*) FROM blueprint_approval)<>2
 OR (SELECT count(*) FROM blueprint_revision)<3 THEN
   RAISE EXCEPTION 'STAGED_LEGACY_DATA_NOT_POPULATED';
 END IF;
END $$;
SQL
before=$(printf '%s\n' \
 "SELECT (SELECT count(*) FROM blueprint_revision)||':'||" \
 "(SELECT count(*) FROM blueprint_approval)||':'||" \
 "(SELECT count(*) FROM plant_activation_set)||':'||" \
 "(SELECT count(*) FROM site)" | "${STAGE[@]}" -t -A | tr -d '[:space:]')

# Apply BOTH real, reviewed SQL migration files in the required order.
"${STAGE[@]}" < scripts/sql/bcq-g4-activation-command.sql >/dev/null
"${STAGE[@]}" < scripts/sql/bcq-g42-business-transition.sql >/dev/null
after=$(printf '%s\n' \
 "SELECT (SELECT count(*) FROM blueprint_revision)||':'||" \
 "(SELECT count(*) FROM blueprint_approval)||':'||" \
 "(SELECT count(*) FROM plant_activation_set)||':'||" \
 "(SELECT count(*) FROM site)" | "${STAGE[@]}" -t -A | tr -d '[:space:]')
[[ "$before" == "$after" ]] || { echo "Legacy row counts changed $before -> $after" >&2;exit 1; }

# Runtime login is deliberately NOT the migration/schema owner.
cat <<'SQL' | "${STAGE[@]}" >/dev/null
CREATE ROLE g42_stage_runtime LOGIN PASSWORD 'g42-stage-runtime-only';
GRANT CONNECT ON DATABASE zavod_stage TO g42_stage_runtime;
GRANT USAGE ON SCHEMA public TO g42_stage_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO g42_stage_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO g42_stage_runtime;
REVOKE UPDATE, DELETE, TRUNCATE ON business_transition FROM g42_stage_runtime;
DO $$
BEGIN
 IF has_table_privilege('g42_stage_runtime','business_transition','UPDATE')
 OR has_table_privilege('g42_stage_runtime','business_transition','DELETE')
 OR has_table_privilege('g42_stage_runtime','business_transition','TRUNCATE')
 OR NOT has_table_privilege('g42_stage_runtime','business_transition','INSERT') THEN
   RAISE EXCEPTION 'RUNTIME_ROLE_LEDGER_PRIVILEGE_BREACH';
 END IF;
END $$;
SQL
# Test migration under the restricted principal with PRODUCTION schema-validation.
export ZAVOD_DB_URL="jdbc:postgresql://127.0.0.1:$PGPORT/zavod_stage"
export ZAVOD_DB_USER=g42_stage_runtime
export ZAVOD_DB_PASSWORD=g42-stage-runtime-only
export SPRING_JPA_HIBERNATE_DDL_AUTO=validate
java -cp "$CP" kz.zavod.blueprint.BcqMigrationVerifyProcess "$TMP/manifest.properties"
echo "BCQ_G42_POPULATED_UPGRADE_PASS: source data snapshot; emulated pre-G4 tables; both real SQL migrations; validate under restricted login"
echo "NOTE: requires separate rehearsal on a representative real pre-upgrade production schema before deployed release."
