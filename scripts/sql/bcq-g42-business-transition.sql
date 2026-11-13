-- BCQ-G4.2-B: ordered, append-only business transition ledger.
-- Prerequisite: existing site, blueprint_revision, approval, activation tables.
-- Review privileges and migrate in staging before production. No historical
-- event records are fabricated for transitions preceding rollout.
BEGIN;
CREATE TABLE business_transition (
 id varchar(36) PRIMARY KEY,
 site_id varchar(64) NOT NULL REFERENCES site(site_id),
 sequence_no bigint NOT NULL CHECK(sequence_no>=1),
 event_type varchar(24) NOT NULL CHECK(event_type IN ('APPROVE','PUBLISH','ACTIVATE','REVOKE','RESTORE')),
 actor_id varchar(128) NOT NULL,
 blueprint_id varchar(36) NOT NULL,
 revision_no bigint NOT NULL CHECK(revision_no>=1),
 previous_set_id varchar(36),
 new_set_id varchar(36),
 reason varchar(1024),
 command_key varchar(128),
 content_hash varchar(64) NOT NULL,
 previous_event_hash varchar(64) NOT NULL,
 event_hash varchar(64) NOT NULL,
 created_at timestamptz NOT NULL,
 CONSTRAINT uq_business_transition_sequence UNIQUE(site_id,sequence_no),
 CONSTRAINT uq_business_transition_command UNIQUE(site_id,command_key)
);
CREATE INDEX idx_business_transition_site_time ON business_transition(site_id,created_at);
-- Guard at database level. Table owner/administrators must also control schema
-- and privileges; superusers can replace triggers and are outside this threat model.
CREATE FUNCTION zavod_block_business_transition_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'BUSINESS_LEDGER_APPEND_ONLY';
END; $$;
CREATE TRIGGER trg_business_transition_immutable
 BEFORE UPDATE OR DELETE ON business_transition
 FOR EACH ROW EXECUTE FUNCTION zavod_block_business_transition_mutation();
COMMIT;
-- Run under a separate migration owner, never the application runtime role:
-- REVOKE UPDATE, DELETE, TRUNCATE ON business_transition FROM zavod_runtime;
-- GRANT SELECT, INSERT ON business_transition TO zavod_runtime;
-- Replace zavod_runtime with the ACTUAL verified runtime DB principal.
