ALTER TABLE runs ADD COLUMN phase TEXT;
ALTER TABLE runs ADD COLUMN finished_at TIMESTAMPTZ;
-- every pre-existing row was inserted after its agent call completed
UPDATE runs SET finished_at = created_at;
CREATE INDEX idx_runs_item_created ON runs (work_item_id, created_at DESC);
CREATE INDEX idx_runs_running ON runs (created_at DESC) WHERE outcome = 'running';
