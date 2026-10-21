-- V6__partitioning.sql  (Phase 5: monthly partitions for the two ever-growing tables)
--
-- audit_log and change_log only grow (audit is kept 7 years). Partitioning them by month keeps indexes small,
-- makes "old months" cheap to export and drop under the retention policy, and lets queries on a time range
-- touch only the months concerned. Each table is rebuilt as a partitioned table with the same columns and
-- ids (the audit hash chain depends on id order and is preserved), plus a DEFAULT partition as a safety net.
-- PartitionMaintenanceJob creates future months ahead of time.

-- ---------------------------------------------------------------- audit_log
ALTER SEQUENCE audit_log_id_seq OWNED BY NONE;        -- keep the sequence when the old table is dropped
ALTER TABLE audit_log RENAME TO audit_log_old;
DROP TRIGGER trg_audit_append_only ON audit_log_old;  -- the old table is copied and dropped below
ALTER INDEX IF EXISTS audit_log_pkey RENAME TO audit_log_old_pkey;
DROP INDEX IF EXISTS ix_audit_at;
DROP INDEX IF EXISTS ix_audit_actor;
DROP INDEX IF EXISTS ix_audit_entity;

CREATE TABLE audit_log (
    id           BIGINT NOT NULL DEFAULT nextval('audit_log_id_seq'),
    at           TIMESTAMPTZ NOT NULL,
    actor        TEXT NOT NULL,
    action       TEXT NOT NULL,
    entity_type  TEXT NOT NULL,
    entity_id    TEXT,
    detail       JSONB NOT NULL DEFAULT '{}'::jsonb,
    prev_hash    CHAR(64) NOT NULL,
    hash         CHAR(64) NOT NULL,
    hash_version INT NOT NULL DEFAULT 2,
    PRIMARY KEY (id, at)                              -- the partition key must be part of the primary key
) PARTITION BY RANGE (at);
ALTER SEQUENCE audit_log_id_seq OWNED BY audit_log.id;
CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;

-- ---------------------------------------------------------------- change_log
ALTER SEQUENCE change_log_id_seq OWNED BY NONE;
ALTER TABLE change_log RENAME TO change_log_old;
ALTER INDEX IF EXISTS change_log_pkey RENAME TO change_log_old_pkey;
DROP INDEX IF EXISTS ix_change_log_cell;

CREATE TABLE change_log (
    id          BIGINT NOT NULL DEFAULT nextval('change_log_id_seq'),
    cycle_id    UUID NOT NULL,
    product_id  UUID NOT NULL,
    region_code TEXT NOT NULL,
    field       TEXT NOT NULL,
    old_value   INT,
    new_value   INT,
    reason      TEXT,
    actor       TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, at)
) PARTITION BY RANGE (at);
ALTER SEQUENCE change_log_id_seq OWNED BY change_log.id;
CREATE TABLE change_log_default PARTITION OF change_log DEFAULT;

-- ---------------------------------------------------------------- monthly partitions
-- From the oldest existing row's month up to 3 months ahead, for both tables.
DO $$
DECLARE
    m     DATE;
    stop  DATE := (date_trunc('month', now()) + interval '3 months')::date;
BEGIN
    m := LEAST(
        COALESCE((SELECT date_trunc('month', MIN(at))::date FROM audit_log_old), date_trunc('month', now())::date),
        COALESCE((SELECT date_trunc('month', MIN(at))::date FROM change_log_old), date_trunc('month', now())::date));
    WHILE m <= stop LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
                       'audit_log_' || to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
        EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF change_log FOR VALUES FROM (%L) TO (%L)',
                       'change_log_' || to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
        m := (m + interval '1 month')::date;
    END LOOP;
END $$;

-- ---------------------------------------------------------------- copy, then drop the old tables
INSERT INTO audit_log (id, at, actor, action, entity_type, entity_id, detail, prev_hash, hash, hash_version)
    SELECT id, at, actor, action, entity_type, entity_id, detail, prev_hash, hash, hash_version FROM audit_log_old ORDER BY id;
INSERT INTO change_log (id, cycle_id, product_id, region_code, field, old_value, new_value, reason, actor, at)
    SELECT id, cycle_id, product_id, region_code, field, old_value, new_value, reason, actor, at FROM change_log_old ORDER BY id;
DROP TABLE audit_log_old;
DROP TABLE change_log_old;

-- ---------------------------------------------------------------- indexes and the append-only trigger
CREATE INDEX ix_audit_at ON audit_log (at);
CREATE INDEX ix_audit_actor ON audit_log (actor);
CREATE INDEX ix_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX ix_audit_id ON audit_log (id);           -- the hash chain is walked in id order
CREATE INDEX ix_change_log_cell ON change_log (cycle_id, product_id, region_code);
CREATE TRIGGER trg_audit_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_change();

-- When old audit months are dropped under the retention policy, the first remaining row no longer follows
-- the "genesis" hash. The anchor records where the verified chain now starts.
CREATE TABLE audit_anchor (
    singleton  BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    first_id   BIGINT NOT NULL,
    prev_hash  CHAR(64) NOT NULL,
    dropped_before TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
