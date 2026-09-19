-- Per-scenario review state for the Preview tab's acceptance-criteria accordion (plan "Story
-- preview redesign"). One row per (artifact version, scenario name); rows are upserted (not
-- append-only) so a reviewer can un-mark a scenario - history of who changed what lives in
-- review_events (kind = 'scenario-reviewed') and review.md, mirroring the approvals dual trail
-- (tech-stack §3.3).
CREATE TABLE scenario_reviews (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    artifact_id   UUID NOT NULL REFERENCES artifacts (id),
    version       INT NOT NULL,
    scenario      TEXT NOT NULL,
    status        TEXT NOT NULL,      -- meets | not-reviewed
    reviewer_sub  TEXT NOT NULL,
    role          TEXT NOT NULL,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (artifact_id, scenario)
);
