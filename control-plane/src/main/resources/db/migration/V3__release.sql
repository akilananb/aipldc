-- build-order phase 4: the release pack (tech-stack §3.4 "a document-level sign is a signal with
-- doc_id"). One row per document per pack version, keyed by the story's work_items.id so
-- ReleaseController can look up the exact content a checker is signing (contentHash) without
-- re-deriving it from git.
CREATE TABLE release_documents (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id       UUID NOT NULL REFERENCES work_items (id),
    release_id     TEXT NOT NULL,
    doc_id         TEXT NOT NULL,
    title          TEXT NOT NULL,
    content        TEXT NOT NULL,
    checker_role   TEXT NOT NULL,
    content_hash   TEXT NOT NULL,
    pack_version   INT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (story_id, doc_id, pack_version)
);
