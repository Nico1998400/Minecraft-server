-- V10: basic production at factories.

-- Pristine = a plain stack identical to a freshly created item of its material (no name, enchantments, damage…).
-- Only pristine stacks can be consumed as production inputs, because partially consumed stacks are re-created.
ALTER TABLE item_stash ADD COLUMN pristine BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX item_stash_pristine_idx ON item_stash (owner_type, owner_id, material, id) WHERE claimed_at IS NULL AND pristine;

CREATE TABLE production_runs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id    BIGINT      NOT NULL REFERENCES companies (id),
    recipe        TEXT        NOT NULL,
    batches       INT         NOT NULL,
    operator_uuid UUID        NOT NULL REFERENCES players (uuid),
    status        TEXT        NOT NULL DEFAULT 'RUNNING',
    xp            BIGINT      NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ NOT NULL,
    finishes_at   TIMESTAMPTZ NOT NULL,
    completed_at  TIMESTAMPTZ,
    CONSTRAINT production_runs_batches_chk CHECK (batches BETWEEN 1 AND 64),
    CONSTRAINT production_runs_status_chk CHECK (status IN ('RUNNING', 'COMPLETED')),
    CONSTRAINT production_runs_completed_chk CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX production_runs_due_idx ON production_runs (finishes_at) WHERE status = 'RUNNING';
CREATE INDEX production_runs_company_idx ON production_runs (company_id, id DESC);
