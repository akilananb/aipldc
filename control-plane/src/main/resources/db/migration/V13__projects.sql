-- Admin-managed multi-repo project config, replacing the pdlc.yaml-only single-repo `profiles.*`
-- entry as the runtime source of truth. `id` is the project code (today's `profile` string;
-- every existing `profile TEXT` column elsewhere stays as-is). `config_json` is the Admin-editable
-- ai.pdlc.core.config.ProjectDocument (project meta + board + repos + gates), serialized as text
-- (same convention as demo_snapshots.gate_json) — no jsonb anywhere in this schema.
CREATE TABLE projects (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    config_json  TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   TEXT
);

-- A story can now open one PR per repo it touched; drop the old one-PR-per-story uniqueness and
-- tag every row with which project repo it targets (default 'main' backfills every pre-migration
-- single-repo row under the legacy synthesized repo id).
ALTER TABLE prs DROP CONSTRAINT prs_work_item_id_key;
ALTER TABLE prs ADD COLUMN repo_id TEXT NOT NULL DEFAULT 'main';

-- ACP build-worker presence now reports one row per repo override (or a single `payload` marker
-- row when it has none) instead of one flat repo_mode/repo_location/repo_branch triple.
ALTER TABLE agent_presence ADD COLUMN repos_json TEXT;
ALTER TABLE agent_presence DROP COLUMN repo_mode;
ALTER TABLE agent_presence DROP COLUMN repo_location;
ALTER TABLE agent_presence DROP COLUMN repo_branch;
