-- TF-02: internal simulated telemetry history; prerequisite TF-01.
-- Review staging schema/migration and back up DB before manual deployment.
BEGIN;
CREATE TABLE IF NOT EXISTS telemetry_sample (
    signal_id varchar(36) NOT NULL REFERENCES telemetry_signal_binding(id),
    source_event_id varchar(128) NOT NULL,
    source_kind varchar(32) NOT NULL CHECK (source_kind IN ('SIMULATED','EDGE_READ_ONLY')),
    sample_value numeric(38,18) NOT NULL,
    unit_code varchar(32) NOT NULL,
    source_time timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    quality varchar(32) NOT NULL CHECK (quality IN ('GOOD','UNCERTAIN','BAD','STALE','DISCONNECTED')),
    CONSTRAINT pk_telemetry_sample_event PRIMARY KEY (signal_id,source_event_id)
);
CREATE INDEX IF NOT EXISTS idx_telemetry_sample_time
    ON telemetry_sample (signal_id,source_time DESC,source_event_id);
COMMIT;
-- Does not enable live OPC UA, Modbus, or commands to industrial equipment.
-- Actual retention and partition strategy require per-site pilot measurements.
