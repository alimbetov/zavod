-- Enterprise R1 engineering project metadata. Manual reviewed migration for existing sites.
-- Run before deploying code with spring.jpa.hibernate.ddl-auto=validate.
-- Non-destructive. Back up the database, audit schema and site policies first.
BEGIN;
CREATE TABLE IF NOT EXISTS engineering_project (
    project_id varchar(36) PRIMARY KEY,
    site_id varchar(64) NOT NULL REFERENCES site(site_id),
    name varchar(160) NOT NULL,
    description varchar(2000) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    archived_at timestamptz,
    row_version bigint NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_engineering_project_site ON engineering_project(site_id,status);
COMMIT;
-- Existing legacy Blueprints remain outside this new metadata table until a
-- separately reviewed, site-aware backfill has been executed.
-- This script does NOT expose production REST endpoints.
