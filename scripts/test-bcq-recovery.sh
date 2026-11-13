#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# BCQ-G3 isolated PostgreSQL. Do not run against a production connection.
if [[ ${ZAVOD_DB_URL:-} != "" ]]; then
  echo "BCQ-G3 refuses a preconfigured ZAVOD_DB_URL" >&2
  exit 2
fi
TMP="$(mktemp -d)"
PG_NAME="zavod-bcq-$$"
SEED_PID=""
cleanup() {
  if [[ -n "$SEED_PID" ]]; then kill -9 "$SEED_PID" 2>/dev/null || true; fi
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

docker run -d --name "$PG_NAME" \
    -e POSTGRES_DB=zavod -e POSTGRES_USER=zavod -e POSTGRES_PASSWORD=bcq-ci-only \
    -p 127.0.0.1:55432:5432 postgres:16.6 >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$PG_NAME" pg_isready -U zavod -d zavod >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$PG_NAME" pg_isready -U zavod -d zavod >/dev/null

mvn -B --no-transfer-progress -DskipTests test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/bcq-classpath.txt -DincludeScope=test
CP="target/test-classes:target/classes:$(cat target/bcq-classpath.txt)"
export ZAVOD_DB_URL="jdbc:postgresql://127.0.0.1:55432/zavod"
export ZAVOD_DB_USER="zavod"
export ZAVOD_DB_PASSWORD="bcq-ci-only"
export SPRING_JPA_HIBERNATE_DDL_AUTO="update"
java -cp "$CP" kz.zavod.blueprint.BcqRecoveryProcess seed \
    "$TMP/manifest.properties" "$TMP/ready" > "$TMP/seed.log" 2>&1 &
SEED_PID="$!"
for _ in $(seq 1 120); do
  if [[ -s "$TMP/ready" ]]; then break; fi
  if ! kill -0 "$SEED_PID" 2>/dev/null; then
    cat "$TMP/seed.log" >&2
    echo "BCQ-G3 seed process failed before DB commit" >&2
    exit 1
  fi
  sleep 0.5
done
if [[ ! -s "$TMP/ready" ]]; then
  cat "$TMP/seed.log" >&2
  echo "BCQ-G3 seed timeout" >&2
  exit 1
fi
echo "BCQ-G3: committed seed; intentionally SIGKILL JVM PID=$SEED_PID"
kill -9 "$SEED_PID"
wait "$SEED_PID" 2>/dev/null || true
SEED_PID=""

export SPRING_JPA_HIBERNATE_DDL_AUTO="validate"
java -cp "$CP" kz.zavod.blueprint.BcqRecoveryProcess verify \
    "$TMP/manifest.properties" "$TMP/ready"

echo "BCQ-G3: dump PostgreSQL and restore to a separate empty database"
docker exec "$PG_NAME" pg_dump -U zavod -Fc zavod > "$TMP/backup.dump"
test -s "$TMP/backup.dump"
docker exec "$PG_NAME" createdb -U zavod zavod_restored
docker exec -i "$PG_NAME" pg_restore -U zavod -d zavod_restored \
    --no-owner --no-acl < "$TMP/backup.dump"
export ZAVOD_DB_URL="jdbc:postgresql://127.0.0.1:55432/zavod_restored"
java -cp "$CP" kz.zavod.blueprint.BcqRecoveryProcess verify \
    "$TMP/manifest.properties" "$TMP/ready"
# G4.2-E.3: audit actual restored history from a separate offline JVM and
# produce a machine-readable report. This does not run an industrial scheduler.
java -cp "$CP" kz.zavod.blueprint.BusinessLedgerAuditProcess "$TMP/full-ledger-audit.json"
python3 - "$TMP/full-ledger-audit.json" "$TMP/trusted-anchors.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as fh:
    report = json.load(fh)
assert report["passed"] and len(report["sites"]) >= 1
anchors = {entry["siteId"]: {"sequenceNo": entry["sequenceNo"],
                              "eventHash": entry["eventHash"]}
           for entry in report["sites"]}
assert any(anchor["sequenceNo"] >= 8 for anchor in anchors.values())
with open(sys.argv[2], "w", encoding="utf-8") as fh:
    json.dump(anchors, fh)
PY
java -cp "$CP" kz.zavod.blueprint.BusinessLedgerAuditProcess \
    "$TMP/verified-with-anchor.json" "$TMP/trusted-anchors.json"
echo "BCQ_G42_FULL_AUDIT_PASS: offline JVM verified complete restored chain against external baseline"
echo "BCQ-G3 PASS: forced JVM crash, cold-process recovery and PostgreSQL dump/restore"
