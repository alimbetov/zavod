-- TF-01: optional additive telemetry metadata migration for pre-qualified environments.
-- Apply under reviewed DB migration procedures. No PLC/OT connectivity enabled.
BEGIN;
CREATE TABLE IF NOT EXISTS telemetry_signal_binding (
    id varchar(36) PRIMARY KEY,
    site_id varchar(64) NOT NULL REFERENCES site(site_id),
    blueprint_id varchar(36) NOT NULL REFERENCES blueprint_site(blueprint_id),
    revision_no bigint NOT NULL,
    unit_id varchar(128) NOT NULL,
    equipment_id varchar(128) NOT NULL,
    signal_key varchar(128) NOT NULL,
    unit_code varchar(32) NOT NULL,
    source_kind varchar(32) NOT NULL CHECK (source_kind IN ('SIMULATED','EDGE_READ_ONLY')),
    stale_after_ms bigint NOT NULL CHECK (stale_after_ms BETWEEN 1 AND 604800000),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_telemetry_signal_mapping UNIQUE (
        site_id,blueprint_id,revision_no,unit_id,equipment_id,signal_key)
);
CREATE INDEX IF NOT EXISTS idx_telemetry_signal_site_revision
    ON telemetry_signal_binding(site_id,blueprint_id,revision_no);
COMMIT;
-- Site ownership and referenced immutable Blueprint revision are also verified
-- by SignalRegistryService; production DB operations must use the trusted service.
-- No sample table or PLC credentials in TF-01. Ingestion requires separate review.
