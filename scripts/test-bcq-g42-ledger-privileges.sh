#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Isolated migration qualification: NEVER run against a configured production DB.
if [[ -n "${ZAVOD_DB_URL:-}" ]]; then
  echo "BCQ-G4.2 refuses an existing ZAVOD_DB_URL" >&2
  exit 2
fi
PG_NAME="zavod-g42-permissions-$$"
cleanup() { docker rm -f "$PG_NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$PG_NAME" \
  -e POSTGRES_DB=zavod_g42 -e POSTGRES_USER=zavod -e POSTGRES_PASSWORD=g42-ci-only \
  postgres:16.6 >/dev/null
for _ in $(seq 1 60); do
  docker exec "$PG_NAME" pg_isready -U zavod -d zavod_g42 >/dev/null 2>&1 && break
  sleep 1
done
PSQL=(docker exec -i "$PG_NAME" psql -v ON_ERROR_STOP=1 -U zavod -d zavod_g42)

# Minimal trusted prerequisite for this additive migration.
printf '%s\n' 'CREATE TABLE site (site_id varchar(64) PRIMARY KEY);' \
  'INSERT INTO site(site_id) VALUES ('\''g42-review-site'\'');' | "${PSQL[@]}" >/dev/null
"${PSQL[@]}" < scripts/sql/bcq-g42-business-transition.sql >/dev/null

# Deliberately separate application principal from the DB/schema owner.
cat <<'SQL' | "${PSQL[@]}" >/dev/null
CREATE ROLE g42_runtime NOLOGIN;
GRANT USAGE ON SCHEMA public TO g42_runtime;
GRANT SELECT, INSERT ON business_transition TO g42_runtime;
REVOKE UPDATE, DELETE, TRUNCATE ON business_transition FROM g42_runtime;
DO $$
BEGIN
 IF has_table_privilege('g42_runtime','business_transition','UPDATE')
    OR has_table_privilege('g42_runtime','business_transition','DELETE')
    OR has_table_privilege('g42_runtime','business_transition','TRUNCATE')
    OR NOT has_table_privilege('g42_runtime','business_transition','INSERT')
    OR NOT has_table_privilege('g42_runtime','business_transition','SELECT') THEN
   RAISE EXCEPTION 'G42_RUNTIME_PERMISSIONS_WRONG';
 END IF;
END $$;
SET ROLE g42_runtime;
INSERT INTO business_transition
 (id,site_id,sequence_no,event_type,actor_id,blueprint_id,revision_no,
  content_hash,previous_event_hash,event_hash,created_at)
 VALUES ('review-001','g42-review-site',1,'APPROVE','reviewer',
  '00000000-0000-0000-0000-000000000001',1,
  repeat('a',64),'',repeat('b',64),now());
RESET ROLE;
SQL

# Application role must be unable to change evidence, even using raw SQL.
for statement in \
  "UPDATE business_transition SET actor_id='forged' WHERE id='review-001'" \
  "DELETE FROM business_transition WHERE id='review-001'" \
  "TRUNCATE business_transition"; do
  if printf '%s\n' 'SET ROLE g42_runtime;' "$statement;" | "${PSQL[@]}" >/dev/null 2>&1; then
    echo "G42 SECURITY FAILURE: runtime role mutated append-only journal" >&2
    exit 1
  fi
done
# Even the migration owner cannot casually UPDATE/DELETE while trigger remains.
for statement in \
  "UPDATE business_transition SET actor_id='forged' WHERE id='review-001'" \
  "DELETE FROM business_transition WHERE id='review-001'"; do
  if printf '%s\n' "$statement;" | "${PSQL[@]}" >/dev/null 2>&1; then
    echo "G42 SECURITY FAILURE: immutable trigger did not reject owner mutation" >&2
    exit 1
  fi
done
row_count=$(printf '%s\n' 'SELECT count(*) FROM business_transition;' |
  "${PSQL[@]}" -t -A | tr -d '[:space:]')
[[ "$row_count" == "1" ]] || { echo "G42 immutable row lost: $row_count" >&2; exit 1; }
echo "BCQ-G4.2 PASS: migration applied; restricted runtime may insert/read but cannot update/delete/truncate; owner trigger blocks tampering"
