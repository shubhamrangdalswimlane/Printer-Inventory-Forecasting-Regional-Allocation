-- V5__finalization_admin.sql  (Phase 4: transfers, finalization, admin, audit hardening)

-- Transfers of units of one product between two regions during REGIONAL_FINALIZATION (PRD FR-RM-07).
--   REQUEST: the receiving region asks the donor; the donor decides.
--   OFFER:   the donor offers to the receiver; the receiver decides.
--   DIRECT:  one manager owns both regions and rebalances directly (FR-RM-10); applied at once.
-- The Planner may decide any pending transfer on a region's behalf (escalation, PRD Q3).
CREATE TABLE transfer_request (
    id               UUID PRIMARY KEY,
    cycle_id         UUID NOT NULL REFERENCES planning_cycle(id),
    product_id       UUID NOT NULL REFERENCES product(id),
    from_region      TEXT NOT NULL REFERENCES region(code),
    to_region        TEXT NOT NULL REFERENCES region(code),
    qty              INT  NOT NULL CHECK (qty > 0),
    kind             TEXT NOT NULL,                 -- REQUEST, OFFER, DIRECT
    status           TEXT NOT NULL,                 -- REQUESTED, ACCEPTED, REJECTED, CANCELLED
    reason           TEXT,
    requested_by     TEXT NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by       TEXT,
    decided_at       TIMESTAMPTZ,
    decision_comment TEXT,
    CHECK (from_region <> to_region)
);
CREATE INDEX ix_transfer_cycle_status ON transfer_request (cycle_id, status);

-- Small key/value store for operational status shown on the Admin dashboard
-- (last SFTP poll, last audit-chain verification ...).
CREATE TABLE system_status (
    key        TEXT PRIMARY KEY,
    value      TEXT,
    ok         BOOLEAN,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Audit hardening.
-- 1) hash_version: rows written before this migration hashed the JSON text exactly as the application
--    wrote it, but PostgreSQL normalises JSONB on storage, so those hashes cannot be recomputed from the
--    stored row. Version 2 rows hash the normalised text and CAN be recomputed; version 1 rows are checked
--    for chain linkage (prev_hash = previous hash) only.
ALTER TABLE audit_log ADD COLUMN hash_version INT NOT NULL DEFAULT 1;
ALTER TABLE audit_log ALTER COLUMN hash_version SET DEFAULT 2;

-- 2) Append-only in the database itself, not only by convention: no UPDATE, no DELETE.
CREATE FUNCTION forbid_audit_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_change();

-- Indexes for the Admin audit search.
CREATE INDEX ix_audit_at ON audit_log (at);
CREATE INDEX ix_audit_actor ON audit_log (actor);
CREATE INDEX ix_audit_entity ON audit_log (entity_type, entity_id);
