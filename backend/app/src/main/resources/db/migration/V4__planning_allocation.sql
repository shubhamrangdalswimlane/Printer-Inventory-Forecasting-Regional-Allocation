-- V4__planning_allocation.sql  (Phase 3: rule parameters, stock per cycle, Drools allocation, approval)

-- Admin-editable rule parameters (PRD FR-AD-06). Every change is a NEW numbered version, never an
-- update, so any past proposal can be explained with the exact numbers it used. The version in force is
-- the newest one whose effective_from has been reached.
CREATE TABLE rule_parameter_set (
    version        INT PRIMARY KEY,
    params         JSONB NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    changed_by     TEXT NOT NULL,
    reason         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO rule_parameter_set (version, params, effective_from, changed_by, reason) VALUES (
    1,
    '{"regionWeights": {"NORTH": 25, "EAST": 25, "WEST": 25, "SOUTH": 25},
      "changeThresholdPct": 20, "minRegionFloor": 0, "spikeFactor": 3.0,
      "capAtDemand": false, "autoActivateForecast": true}'::jsonb,
    TIMESTAMPTZ '2000-01-01 00:00:00+00', 'system', 'PRD defaults');

-- Stock of each product for a cycle, taken from the active inventory version (PRD FR-PL-02).
-- allocatable = available - reserve, never below 0 (BR-AL-01), computed by the database.
CREATE TABLE plan_product_stock (
    cycle_id               UUID NOT NULL REFERENCES planning_cycle(id),
    product_id             UUID NOT NULL REFERENCES product(id),
    inventory_version_id   UUID REFERENCES dataset_version(id),
    available_qty          INT  NOT NULL CHECK (available_qty >= 0),
    reserve_qty            INT  NOT NULL DEFAULT 0 CHECK (reserve_qty >= 0),
    reserve_reason         TEXT,
    allocatable_qty        INT  GENERATED ALWAYS AS (GREATEST(available_qty - reserve_qty, 0)) STORED,
    classification         TEXT,               -- CONSTRAINED / SUFFICIENT, set by the last proposal
    allocation_explanation TEXT,               -- e.g. "Constrained: weighted baseline 25/25/25/25; remainder 2 -> NORTH, EAST"
    unallocated_qty        INT,                -- sufficient products: stock left after full demand
    row_version            BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (cycle_id, product_id)
);

-- Allocation stages on the working copy (one column per stage, like the forecast columns).
ALTER TABLE plan_line ADD COLUMN proposed_alloc_qty INT CHECK (proposed_alloc_qty >= 0);
ALTER TABLE plan_line ADD COLUMN approved_alloc_qty INT CHECK (approved_alloc_qty >= 0);
ALTER TABLE plan_line ADD COLUMN final_alloc_qty    INT CHECK (final_alloc_qty >= 0);

-- Which rules produced the current proposal, and whether its inputs changed since (PRD FR-PL-09).
ALTER TABLE planning_cycle ADD COLUMN proposal_at                TIMESTAMPTZ;
ALTER TABLE planning_cycle ADD COLUMN proposal_ruleset_version   TEXT;
ALTER TABLE planning_cycle ADD COLUMN proposal_parameter_version INT;
ALTER TABLE planning_cycle ADD COLUMN allocation_stale           BOOLEAN NOT NULL DEFAULT FALSE;

-- Traceability on allocation snapshots: rules and parameters used, and the inventory file behind them.
ALTER TABLE dataset_version ADD COLUMN ruleset_version      TEXT;
ALTER TABLE dataset_version ADD COLUMN parameter_version    INT;
ALTER TABLE dataset_version ADD COLUMN inventory_version_id UUID REFERENCES dataset_version(id);

-- Allocation snapshot lines (ALLOCATION_APPROVED, ALLOCATION_FINAL); immutable like forecast snapshots.
CREATE TABLE allocation_line (
    version_id  UUID NOT NULL REFERENCES dataset_version(id),
    product_id  UUID NOT NULL REFERENCES product(id),
    region_code TEXT NOT NULL REFERENCES region(code),
    qty         INT  NOT NULL CHECK (qty >= 0),
    PRIMARY KEY (version_id, product_id, region_code)
);
CREATE TRIGGER trg_allocation_line_immutable
    BEFORE UPDATE OR DELETE ON allocation_line
    FOR EACH ROW EXECUTE FUNCTION forbid_snapshot_line_change();
