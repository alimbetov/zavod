-- BCQ-G4 phase 1: atomic activation receipt and transition audit.
-- Apply with reviewed migration and backup; production retains ddl-auto=validate.
BEGIN;
CREATE TABLE IF NOT EXISTS activation_command (
    id varchar(36) PRIMARY KEY,
    site_id varchar(64) NOT NULL REFERENCES site(site_id),
    command_key varchar(128) NOT NULL,
    actor_id varchar(128) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    result_set_id varchar(36) NOT NULL REFERENCES plant_activation_set(id),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_activation_site_key UNIQUE(site_id,command_key)
);
CREATE TABLE IF NOT EXISTS activation_transition (
    id varchar(36) PRIMARY KEY,
    command_id varchar(36) NOT NULL REFERENCES activation_command(id),
    site_id varchar(64) NOT NULL REFERENCES site(site_id),
    actor_id varchar(128) NOT NULL,
    blueprint_id varchar(36) NOT NULL,
    revision_no bigint NOT NULL,
    previous_set_id varchar(36),
    new_set_id varchar(36) NOT NULL REFERENCES plant_activation_set(id),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_activation_transition_command UNIQUE(command_id)
);
COMMIT;
-- No historical backfill is fabricated: earlier activations have no BCQ-G4 command receipt.
-- Application writes remain internal; no production HTTP API or PLC commands.
