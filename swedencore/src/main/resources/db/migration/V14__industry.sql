-- Industry: recipes run at a facility property type; runs keep a snapshot of their outputs so that editing or
-- removing a recipe in production.yml never destroys goods whose inputs were already consumed.
ALTER TABLE production_runs
    ADD COLUMN facility TEXT NOT NULL DEFAULT 'FACTORY',
    ADD COLUMN outputs  TEXT;

ALTER TABLE production_runs
    ADD CONSTRAINT production_runs_facility_chk CHECK (facility IN ('FACTORY', 'INDUSTRIAL_LAND', 'FARM', 'MINE'));

CREATE INDEX production_runs_running_idx ON production_runs (company_id, facility) WHERE status = 'RUNNING';
