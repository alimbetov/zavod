-- R1-BF-02: CONTROLLED additive backfill of blueprint_site ONLY.
-- PRECONDITIONS:
-- 1) A verified PostgreSQL backup + restore rehearsal; explicit maintenance window.
-- 2) This script is run by DBA after audit and two-person approved manifest loaded
--    into public.migration_blueprint_owner_manifest in staging/production.
-- 3) The application MUST NOT be accepting writes during migration.
-- 4) You must review any nonzero anomalies from r1-site-owner-audit.sql.
-- No silent owner inference, no update of pre-existing blueprint_site rows.
-- A failed assertion rolls back the entire transaction.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='90s';
SELECT pg_advisory_xact_lock(6790118);
LOCK TABLE blueprint_head, blueprint_site, site, blueprint_approval,
    plant_activation_set, blueprint_view_layout IN SHARE ROW EXCLUSIVE MODE;

-- The approved manifest is a staging table created from a reviewed, external
-- inventory/export and signed by two distinct humans. It is intentionally NOT
-- created or populated by this script. Required columns:
-- blueprint_id varchar(36) PRIMARY KEY, site_id varchar(64) NOT NULL,
-- evidence_reference text NOT NULL, proposed_by text NOT NULL,
-- approved_by text NOT NULL, approved_at timestamptz NOT NULL.
-- Keep the signed immutable manifest and its checksums in deployment records.
DO $checks$
BEGIN
 IF EXISTS(SELECT 1 FROM blueprint_head h LEFT JOIN blueprint_site o
           ON o.blueprint_id=h.blueprint_id WHERE o.blueprint_id IS NULL
           AND NOT EXISTS(SELECT 1 FROM migration_blueprint_owner_manifest m
             WHERE m.blueprint_id=h.blueprint_id)) THEN
   RAISE EXCEPTION 'BACKFILL_MANIFEST_INCOMPLETE';
 END IF;
 IF EXISTS(SELECT 1 FROM migration_blueprint_owner_manifest m
   LEFT JOIN blueprint_head h ON h.blueprint_id=m.blueprint_id
   LEFT JOIN site s ON s.site_id=m.site_id
   WHERE h.blueprint_id IS NULL OR s.site_id IS NULL
       OR m.evidence_reference IS NULL OR btrim(m.evidence_reference)=''
       OR m.proposed_by IS NULL OR btrim(m.proposed_by)=''
       OR m.approved_by IS NULL OR btrim(m.approved_by)=''
       OR m.proposed_by=m.approved_by OR m.approved_at IS NULL) THEN
   RAISE EXCEPTION 'BACKFILL_INVALID_OR_UNAPPROVED_MANIFEST';
 END IF;
 IF EXISTS(SELECT 1 FROM migration_blueprint_owner_manifest m
   JOIN blueprint_site o ON o.blueprint_id=m.blueprint_id
   WHERE m.site_id IS DISTINCT FROM o.site_id) THEN
   RAISE EXCEPTION 'BACKFILL_EXISTING_OWNER_CONFLICT';
 END IF;
 IF EXISTS(SELECT 1 FROM migration_blueprint_owner_manifest m
   JOIN blueprint_approval a ON a.blueprint_id=m.blueprint_id
   WHERE a.site_id IS DISTINCT FROM m.site_id)
 OR EXISTS(SELECT 1 FROM migration_blueprint_owner_manifest m
   JOIN plant_activation_set a ON a.blueprint_id=m.blueprint_id
   WHERE a.site_id IS DISTINCT FROM m.site_id)
 OR EXISTS(SELECT 1 FROM migration_blueprint_owner_manifest m
   JOIN blueprint_view_layout a ON a.blueprint_id=m.blueprint_id
   WHERE a.site_id IS DISTINCT FROM m.site_id) THEN
   RAISE EXCEPTION 'BACKFILL_HISTORICAL_SITE_CONFLICT';
 END IF;
END $checks$;

INSERT INTO blueprint_site(blueprint_id,site_id)
SELECT m.blueprint_id,m.site_id FROM migration_blueprint_owner_manifest m
WHERE NOT EXISTS(SELECT 1 FROM blueprint_site o WHERE o.blueprint_id=m.blueprint_id)
ORDER BY m.blueprint_id;

DO $post$
BEGIN
 IF EXISTS(SELECT 1 FROM blueprint_head h LEFT JOIN blueprint_site o
   ON o.blueprint_id=h.blueprint_id WHERE o.blueprint_id IS NULL)
 OR EXISTS(SELECT 1 FROM blueprint_site o LEFT JOIN site s ON s.site_id=o.site_id
   WHERE s.site_id IS NULL)
 OR EXISTS(SELECT 1 FROM blueprint_approval a LEFT JOIN blueprint_site o
   ON o.blueprint_id=a.blueprint_id
   WHERE o.blueprint_id IS NULL OR a.site_id IS DISTINCT FROM o.site_id)
 OR EXISTS(SELECT 1 FROM plant_activation_set a LEFT JOIN blueprint_site o
   ON o.blueprint_id=a.blueprint_id
   WHERE o.blueprint_id IS NULL OR a.site_id IS DISTINCT FROM o.site_id)
 OR EXISTS(SELECT 1 FROM blueprint_view_layout a LEFT JOIN blueprint_site o
   ON o.blueprint_id=a.blueprint_id
   WHERE o.blueprint_id IS NULL OR a.site_id IS DISTINCT FROM o.site_id)
 THEN RAISE EXCEPTION 'BACKFILL_POSTCHECK_FAILED'; END IF;
END $post$;
COMMIT;
-- After COMMIT re-run r1-site-owner-audit.sql, verify backup/restore
-- and capture both human approvals plus before/after signed row counts.
