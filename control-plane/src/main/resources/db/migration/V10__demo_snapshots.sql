-- Curated restaurant-demo catalog metadata (plan step 4). Snapshot seed rows themselves live in
-- the normal work_items/artifacts/comments/approvals/quality_reports/release_documents/
-- review_events tables (V1-V8) plus local_board_items/local_board_comments (V9) - this migration
-- only adds the seed-marker table and the per-work-item snapshot provenance/read-cache table.

-- One row per (profile, seed_version) once DemoInitializer has successfully imported the bundle.
-- manifest_hash is sha256 of the raw bundled JSON bytes: a startup with a changed bundle under the
-- same seed_version is a hard-fail (explicit reset required), never a silent partial overwrite.
CREATE TABLE demo_seeds (
    profile         TEXT NOT NULL,
    seed_version    TEXT NOT NULL,
    manifest_hash   TEXT NOT NULL,
    initialized_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (profile, seed_version)
);

-- One row per seeded work item (snapshot rows only - the live demo-live feature never gets one).
-- gate_json/grill_json are pre-serialized DTO JSON (ReviewStateDto / GrillQuestionsDto) computed
-- once at seed time from the fixture's frozen gate/grill data, read back verbatim at request time
-- instead of querying Temporal (the snapshot has no running workflow). git_ref is the immutable
-- commit sha (never a moving branch name) DemoInitializer wrote this item's latest version to.
CREATE TABLE demo_snapshots (
    work_item_id   UUID PRIMARY KEY REFERENCES work_items (id),
    seed_version   TEXT NOT NULL,
    snapshot_key   TEXT NOT NULL,
    label          TEXT NOT NULL,
    ordinal        INT NOT NULL,
    source_ref     TEXT NOT NULL,
    replay         BOOLEAN NOT NULL,
    git_ref        TEXT,
    gate_json      TEXT,
    grill_json     TEXT
);
