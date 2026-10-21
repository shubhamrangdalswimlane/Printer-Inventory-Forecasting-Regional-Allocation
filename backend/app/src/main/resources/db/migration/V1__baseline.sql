-- V1__baseline.sql  (Flyway migration, runs once, never edited after release)
--
-- Phase 0 baseline. Only the region master table exists now; products, planning cycles,
-- dataset versions, forecasts, inventory and the audit log arrive in Phase 1.
--
-- Naming rule: V<number>__<description>.sql. Flyway records each applied file and its checksum,
-- so changing this file later would make startup fail. Add a new V2__... file instead.

-- Master list of regions. "code" is the stable key used in tokens (the `regions` claim) and in
-- Excel column headers; "display_order" fixes the N, E, W, S order used to break ties in
-- allocation (PRD BR-AL-05). "active" lets Admin retire a region without deleting history.
CREATE TABLE region (
    code          TEXT PRIMARY KEY,
    name          TEXT    NOT NULL,
    display_order INT     NOT NULL,
    active        BOOLEAN NOT NULL DEFAULT TRUE
);

-- Seed the four regions defined in the PRD.
INSERT INTO region (code, name, display_order) VALUES
    ('NORTH', 'North', 1),
    ('EAST',  'East',  2),
    ('WEST',  'West',  3),
    ('SOUTH', 'South', 4);
