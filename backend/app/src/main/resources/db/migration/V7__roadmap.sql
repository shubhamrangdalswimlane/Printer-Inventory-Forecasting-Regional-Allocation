-- V7: Phase 7 roadmap items.
--  1) Deadline reminders: remembers which reminder was sent for which deadline, so the hourly job sends each one once.
--  2) Optional Product_code: a stable product key that files may carry next to (or instead of matching on) Product_name.

-- One row per reminder sent. The deadline itself is part of the key: if the Planner or Admin moves a deadline,
-- reminders for the new deadline are sent again.
CREATE TABLE deadline_reminder (
    cycle_id    UUID        NOT NULL REFERENCES planning_cycle(id),
    region_code TEXT        NOT NULL REFERENCES region(code),
    stage       TEXT        NOT NULL,          -- REVIEW or FINALIZE
    kind        TEXT        NOT NULL,          -- DUE_SOON (within 24 h) or OVERDUE
    deadline    TIMESTAMPTZ NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (cycle_id, region_code, stage, kind, deadline)
);

-- product.product_code was reserved in V2 (PRD Q1) but never used. It now holds the upper-case, trimmed code
-- (e.g. "PRN-M100"); NULL = no code assigned. Normalise anything already there, then make codes unique.
UPDATE product SET product_code = NULLIF(upper(btrim(product_code)), '') WHERE product_code IS NOT NULL;
CREATE UNIQUE INDEX uq_product_code ON product (product_code) WHERE product_code IS NOT NULL;
