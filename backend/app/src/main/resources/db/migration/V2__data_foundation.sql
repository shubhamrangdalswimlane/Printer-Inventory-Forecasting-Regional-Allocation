-- V2__data_foundation.sql  (Phase 1: master data, cycles, dataset versions, ingestion, audit)
--
-- Simplifications versus architecture.md section 8, on purpose for Phase 1:
--  * Only VALID files become dataset_version rows. A rejected file is recorded in ingestion_job
--    (status REJECTED) with its ingestion_error rows, so versions never contain bad data.
--  * plan_line / change_log / allocation tables arrive with the workflow phases (2-4).

-- Product master. normalized_name is the unique match key (trimmed, single spaces, case-folded).
CREATE TABLE product (
    id              UUID PRIMARY KEY,
    product_name    TEXT    NOT NULL,
    normalized_name TEXT    NOT NULL UNIQUE,
    product_code    TEXT,                       -- reserved: future primary key (PRD Q1)
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    source          TEXT    NOT NULL DEFAULT 'MANUAL'   -- MANUAL or SYNTHETIC (purged before go-live)
);

-- Alternative spellings of a product name, so slightly different files still match the master.
CREATE TABLE product_alias (
    id               UUID PRIMARY KEY,
    product_id       UUID NOT NULL REFERENCES product(id),
    normalized_alias TEXT NOT NULL UNIQUE
);

-- One planning cycle per month. state follows PRD section 7; only FORECAST_DRAFT is used in Phase 1.
CREATE TABLE planning_cycle (
    id          UUID PRIMARY KEY,
    month       CHAR(7) NOT NULL UNIQUE,        -- YYYY-MM
    state       TEXT    NOT NULL DEFAULT 'FORECAST_DRAFT',
    row_version BIGINT  NOT NULL DEFAULT 0,     -- optimistic lock for state changes
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Immutable numbered snapshot of a dataset (every accepted upload; later also sign-off snapshots).
CREATE TABLE dataset_version (
    id                UUID PRIMARY KEY,
    cycle_id          UUID NOT NULL REFERENCES planning_cycle(id),
    type              TEXT NOT NULL,            -- FORECAST_UPLOAD, INVENTORY_UPLOAD, later snapshot types
    version_no        INT  NOT NULL,            -- numbered per cycle + type
    label             TEXT NOT NULL,            -- e.g. FC-2026-11-v3
    source            TEXT NOT NULL,            -- SFTP, UI, SYNTHETIC, SYSTEM
    status            TEXT NOT NULL,            -- VALID, ACTIVE, SUPERSEDED, DELETED, PURGED
    is_active         BOOLEAN NOT NULL DEFAULT FALSE,
    original_filename TEXT,
    object_key        TEXT,                     -- location of the original bytes in the file store
    sha256            CHAR(64),                 -- content hash of the original file
    row_count         INT NOT NULL DEFAULT 0,
    parent_version_id UUID REFERENCES dataset_version(id),
    created_by        TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_by        TEXT,
    deleted_at        TIMESTAMPTZ,
    delete_reason     TEXT,
    UNIQUE (cycle_id, type, version_no)
);
-- Only ONE active version per cycle and type (PRD 8.5), enforced by the database itself.
CREATE UNIQUE INDEX uq_dataset_active ON dataset_version (cycle_id, type) WHERE is_active;
-- The same file content can only be ingested once (DUPLICATE_FILE), even after soft delete or purge.
CREATE UNIQUE INDEX uq_dataset_sha ON dataset_version (sha256) WHERE sha256 IS NOT NULL;

-- Forecast quantities of a version: one row per product and region.
CREATE TABLE forecast_line (
    version_id UUID NOT NULL REFERENCES dataset_version(id),
    product_id UUID NOT NULL REFERENCES product(id),
    region_code TEXT NOT NULL REFERENCES region(code),
    qty        INT  NOT NULL CHECK (qty >= 0),
    comment    TEXT,
    PRIMARY KEY (version_id, product_id, region_code)
);

-- Inventory of a version: one row per product.
CREATE TABLE inventory_line (
    version_id    UUID NOT NULL REFERENCES dataset_version(id),
    product_id    UUID NOT NULL REFERENCES product(id),
    available_qty INT  NOT NULL CHECK (available_qty >= 0),
    as_of_date    DATE NOT NULL,
    PRIMARY KEY (version_id, product_id)
);

-- One row per ingestion attempt (accepted or rejected, SFTP or UI).
CREATE TABLE ingestion_job (
    id                UUID PRIMARY KEY,
    channel           TEXT NOT NULL,            -- SFTP, UI, SYNTHETIC
    source_account    TEXT,                     -- SFTP account or Keycloak username
    dataset_type      TEXT NOT NULL,            -- FORECAST_UPLOAD or INVENTORY_UPLOAD
    original_filename TEXT,
    sha256            CHAR(64),
    status            TEXT NOT NULL,            -- RECEIVED, VALID, REJECTED
    error_count       INT NOT NULL DEFAULT 0,
    warning_count     INT NOT NULL DEFAULT 0,
    version_id        UUID REFERENCES dataset_version(id),
    created_by        TEXT NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ
);

-- Findings of a job (PRD 8.4 codes). Source of the downloadable error report.
CREATE TABLE ingestion_error (
    id          BIGSERIAL PRIMARY KEY,
    job_id      UUID NOT NULL REFERENCES ingestion_job(id) ON DELETE CASCADE,
    row_no      INT  NOT NULL,                  -- 0 = whole file
    column_name TEXT,
    code        TEXT NOT NULL,
    severity    TEXT NOT NULL,                  -- ERROR or WARNING
    message     TEXT NOT NULL
);
CREATE INDEX ix_ingestion_error_job ON ingestion_error (job_id);

-- Monthly sales per product and region; feeds the spike warning now and Regional Manager analysis later.
CREATE TABLE sales_history (
    product_id  UUID NOT NULL REFERENCES product(id),
    region_code TEXT NOT NULL REFERENCES region(code),
    month       CHAR(7) NOT NULL,
    qty         INT NOT NULL CHECK (qty >= 0),
    source      TEXT NOT NULL DEFAULT 'SYNTHETIC',
    PRIMARY KEY (product_id, region_code, month)
);

-- Append-only, hash-chained audit log: each row stores the hash of the previous row, so any later
-- edit or deletion breaks the chain and can be detected by a verification job.
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL,
    actor       TEXT NOT NULL,
    action      TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id   TEXT,
    detail      JSONB NOT NULL DEFAULT '{}'::jsonb,
    prev_hash   CHAR(64) NOT NULL,
    hash        CHAR(64) NOT NULL
);
