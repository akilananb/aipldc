-- build-order phase 3: one PR per story (the shared branch every task in the build loop commits
-- onto). Tracks the RepoPort-native PR id so control-plane can call getDiff/getPRStatus/
-- commentOnPR again later (e.g. from the UI) without re-deriving it.
CREATE TABLE prs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_item_id   UUID NOT NULL REFERENCES work_items (id),
    pr_id          TEXT NOT NULL,
    branch         TEXT NOT NULL,
    target         TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (work_item_id)
);
