-- Phase 0 baseline. Domain tables arrive in Phase 1.
CREATE TABLE region (
    code          TEXT PRIMARY KEY,
    name          TEXT    NOT NULL,
    display_order INT     NOT NULL,
    active        BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO region (code, name, display_order) VALUES
    ('NORTH', 'North', 1),
    ('EAST',  'East',  2),
    ('WEST',  'West',  3),
    ('SOUTH', 'South', 4);
