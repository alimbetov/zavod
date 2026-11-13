-- BCQ-035: additive schema migration for already initialized PostgreSQL databases.
-- Run under a reviewed deployment transaction before starting the application with
-- spring.jpa.hibernate.ddl-auto=validate. Take a verified backup before applying.
-- The application does not execute this script automatically.
BEGIN;
ALTER TABLE blueprint_approval
    ADD COLUMN IF NOT EXISTS revoked_by varchar(128),
    ADD COLUMN IF NOT EXISTS revoked_at timestamptz,
    ADD COLUMN IF NOT EXISTS revocation_reason varchar(1024);
ALTER TABLE plant_activation_set
    ADD COLUMN IF NOT EXISTS restored_from_set_id varchar(36),
    ADD COLUMN IF NOT EXISTS change_reason varchar(1024);
COMMIT;
-- Verify using information_schema.columns and a restore drill.
-- Do not drop columns or edit historical activation/approval records during rollback.
