CREATE TABLE quality_reports (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_item_id UUID NOT NULL REFERENCES work_items(id),
    version      INT NOT NULL,
    subject_kind TEXT NOT NULL,
    verdict      TEXT NOT NULL,
    score        INT,
    report_md    TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_quality_reports_item ON quality_reports (work_item_id, created_at DESC);
