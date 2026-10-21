-- V3__forecast_workflow.sql  (Phase 2: working copy, change log, cycle workflow, notifications)

-- Deadlines for the regional review and the later finalization stage (PRD FR-FC-09).
ALTER TABLE planning_cycle ADD COLUMN review_deadline   TIMESTAMPTZ;
ALTER TABLE planning_cycle ADD COLUMN finalize_deadline TIMESTAMPTZ;

-- Workflow snapshots: which part of the data a version covers. A regional FORECAST_REVISED snapshot
-- has the region code here; the consolidated one has 'ALL'. Uploads leave it NULL.
ALTER TABLE dataset_version ADD COLUMN scope TEXT;

-- The editable working copy: one row per product x region for a cycle, with one column per stage.
--   uploaded_qty  = as received in the active forecast file (never edited)
--   published_qty = Forecaster working value; frozen at publish
--   revised_qty   = Regional Manager value; NULL until the forecast is published
-- row_version is the optimistic lock: a write must quote the version it read.
CREATE TABLE plan_line (
    id              UUID PRIMARY KEY,
    cycle_id        UUID NOT NULL REFERENCES planning_cycle(id),
    product_id      UUID NOT NULL REFERENCES product(id),
    region_code     TEXT NOT NULL REFERENCES region(code),
    uploaded_qty    INT  NOT NULL CHECK (uploaded_qty >= 0),
    published_qty   INT  NOT NULL CHECK (published_qty >= 0),
    revised_qty     INT  CHECK (revised_qty >= 0),
    revision_reason TEXT,
    row_version     BIGINT NOT NULL DEFAULT 0,
    UNIQUE (cycle_id, product_id, region_code)
);
CREATE INDEX ix_plan_line_cycle_region ON plan_line (cycle_id, region_code);

-- Every cell edit: who, when, old value, new value, why. Kept even if the working copy is replaced.
CREATE TABLE change_log (
    id          BIGSERIAL PRIMARY KEY,
    cycle_id    UUID NOT NULL,
    product_id  UUID NOT NULL,
    region_code TEXT NOT NULL,
    field       TEXT NOT NULL,              -- PUBLISHED or REVISED
    old_value   INT,
    new_value   INT,
    reason      TEXT,
    actor       TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_change_log_cell ON change_log (cycle_id, product_id, region_code);

-- Per-region progress inside a cycle (PRD section 7).
CREATE TABLE cycle_region_status (
    cycle_id        UUID NOT NULL REFERENCES planning_cycle(id),
    region_code     TEXT NOT NULL REFERENCES region(code),
    review_status   TEXT NOT NULL DEFAULT 'OPEN',      -- OPEN or SUBMITTED
    finalize_status TEXT NOT NULL DEFAULT 'OPEN',      -- OPEN or FINALIZED (used from Phase 4)
    actor           TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (cycle_id, region_code)
);

-- Every state change of a cycle or region, for the audit trail and the Admin overview.
CREATE TABLE workflow_event (
    id          BIGSERIAL PRIMARY KEY,
    cycle_id    UUID NOT NULL REFERENCES planning_cycle(id),
    action      TEXT NOT NULL,
    from_state  TEXT,
    to_state    TEXT,
    region_code TEXT,
    actor       TEXT NOT NULL,
    comment     TEXT,
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_workflow_event_cycle ON workflow_event (cycle_id, id);

-- In-app notifications. Addressed to a role (and optionally one region), not to a person, so
-- every Regional Manager of North sees the same entry; read state is tracked per user.
CREATE TABLE notification (
    id            BIGSERIAL PRIMARY KEY,
    target_role   TEXT NOT NULL,
    target_region TEXT,
    title         TEXT NOT NULL,
    body          TEXT,
    cycle_id      UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE notification_read (
    notification_id BIGINT NOT NULL REFERENCES notification(id) ON DELETE CASCADE,
    username        TEXT NOT NULL,
    read_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (notification_id, username)
);

-- Transactional outbox for e-mail: written in the same transaction as the change, sent later by a
-- scheduled job, so a mail is never sent for a change that was rolled back.
CREATE TABLE outbox_event (
    id            BIGSERIAL PRIMARY KEY,
    kind          TEXT NOT NULL DEFAULT 'EMAIL',
    target_role   TEXT NOT NULL,
    target_region TEXT,
    subject       TEXT NOT NULL,
    body          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at       TIMESTAMPTZ,
    attempts      INT NOT NULL DEFAULT 0
);

-- Snapshots are immutable: lines of a snapshot version can never be changed or deleted, so a
-- published or submitted number stays provable. The session setting pifa.allow_snapshot_delete = 'on'
-- is the one escape hatch, used only by the synthetic-data purge and by test clean-up.
CREATE FUNCTION forbid_snapshot_line_change() RETURNS trigger AS $$
BEGIN
    IF current_setting('pifa.allow_snapshot_delete', true) = 'on' THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM dataset_version v
               WHERE v.id = OLD.version_id
                 AND v.type IN ('FORECAST_WORKING_SNAPSHOT', 'FORECAST_PUBLISHED', 'FORECAST_REVISED',
                                'ALLOCATION_APPROVED', 'ALLOCATION_FINAL')) THEN
        RAISE EXCEPTION 'Lines of snapshot versions are immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_forecast_line_immutable
    BEFORE UPDATE OR DELETE ON forecast_line
    FOR EACH ROW EXECUTE FUNCTION forbid_snapshot_line_change();
