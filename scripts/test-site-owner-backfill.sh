#!/usr/bin/env bash
# Regression qualification for R1 site ownership backfill on a disposable PostgreSQL database.
set -euo pipefail
export PGHOST="${PGHOST:-127.0.0.1}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-zavod}" PGDATABASE="${PGDATABASE:-zavod}"
psql -X -v ON_ERROR_STOP=1 <<'SQL'
DROP TABLE IF EXISTS migration_blueprint_owner_manifest, blueprint_view_layout,plant_activation_set,
 blueprint_approval,blueprint_revision,blueprint_site,blueprint_head,site CASCADE;
CREATE TABLE site(site_id varchar(64) PRIMARY KEY,active_set_id varchar(36));
CREATE TABLE blueprint_head(blueprint_id varchar(36) PRIMARY KEY,revision_no bigint NOT NULL);
CREATE TABLE blueprint_site(blueprint_id varchar(36) PRIMARY KEY,site_id varchar(64) NOT NULL);
CREATE TABLE blueprint_revision(id varchar(36) PRIMARY KEY,blueprint_id varchar(36) NOT NULL,revision_no bigint NOT NULL);
CREATE TABLE blueprint_approval(id varchar(64) PRIMARY KEY,blueprint_id varchar(36) NOT NULL,site_id varchar(64) NOT NULL,revision_no bigint NOT NULL);
CREATE TABLE plant_activation_set(id varchar(36) PRIMARY KEY,blueprint_id varchar(36) NOT NULL,site_id varchar(64) NOT NULL,
 revision_no bigint NOT NULL,previous_set_id varchar(36),restored_from_set_id varchar(36));
CREATE TABLE blueprint_view_layout(id varchar(160) PRIMARY KEY,blueprint_id varchar(36) NOT NULL,site_id varchar(64) NOT NULL,
 blueprint_revision bigint NOT NULL);
CREATE TABLE migration_blueprint_owner_manifest(blueprint_id varchar(36) PRIMARY KEY,site_id varchar(64) NOT NULL,
 evidence_reference text NOT NULL,proposed_by text NOT NULL,approved_by text NOT NULL,approved_at timestamptz NOT NULL);
INSERT INTO site(site_id) VALUES ('s1'),('s2');
INSERT INTO blueprint_head VALUES ('bp1',1),('bp2',1);
INSERT INTO blueprint_revision VALUES ('r1','bp1',1),('r2','bp2',1);
INSERT INTO blueprint_site VALUES ('bp2','s2');
INSERT INTO migration_blueprint_owner_manifest VALUES ('bp1','s1','inventory-case-123','creator1','reviewer2',now());
-- The contradictory historical approval must cause the entire transaction to abort.
INSERT INTO blueprint_approval VALUES ('app1','bp1','s2',1);
SQL

echo "Running read-only audit; pre-backfill must detect one unassigned head and a contradictory approval"
before=$(psql -X -v ON_ERROR_STOP=1 -At -f scripts/sql/r1-site-owner-audit.sql)
echo "$before" | grep -q '^unassigned_heads|1$'
echo "$before" | grep -q '^approval_site_mismatch_or_unassigned|1$'
if psql -X -v ON_ERROR_STOP=1 -f scripts/sql/r1-site-owner-backfill.sql; then
  echo "ERROR: conflicting historical approval was accepted" >&2
  exit 1
fi
count=$(psql -X -Atc "SELECT count(*) FROM blueprint_site WHERE blueprint_id='bp1'")
test "$count" = 0 || { echo "ERROR: failed migration committed rows" >&2; exit 1; }

# Fix the hostile historical row from a signed source in this *synthetic* fixture.
psql -X -v ON_ERROR_STOP=1 -c "UPDATE blueprint_approval SET site_id='s1' WHERE id='app1';" >/dev/null
psql -X -v ON_ERROR_STOP=1 -f scripts/sql/r1-site-owner-backfill.sql >/dev/null
after=$(psql -X -v ON_ERROR_STOP=1 -At -f scripts/sql/r1-site-owner-audit.sql)
echo "$after" | grep -q '^unassigned_heads|0$'
if echo "$after" | grep -Eq '\|[1-9][0-9]*$'; then
  echo "ERROR: residual ownership anomalies:" >&2
  echo "$after" >&2
  exit 1
fi
# Repeated apply must not duplicate owner mapping or alter it.
psql -X -v ON_ERROR_STOP=1 -f scripts/sql/r1-site-owner-backfill.sql >/dev/null
count=$(psql -X -Atc "SELECT count(*) FROM blueprint_site WHERE blueprint_id='bp1' AND site_id='s1'")
test "$count" = 1 || exit 1
echo "PASS: audit, contradiction rollback, explicit manifest backfill, idempotent rerun, zero anomalies"
