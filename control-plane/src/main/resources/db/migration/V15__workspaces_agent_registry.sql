-- Configurable agent platform, phase 1 slice 1 (docs/phase-1-execution-spec.md): isolated
-- workspaces with per-member capabilities, and a versioned AgentDefinition registry whose
-- published versions are immutable. JSON is stored as TEXT, same convention as projects.config_json.

CREATE TABLE workspaces (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT NOT NULL
);

-- One row per (workspace, user, capability): WORKSPACE_ADMIN | AUTHOR | OPERATOR | REVIEWER | CURATOR.
-- Membership = at least one row; a user with no rows cannot discover the workspace at all.
CREATE TABLE workspace_members (
    workspace_id  TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id       TEXT NOT NULL,
    capability    TEXT NOT NULL,
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by    TEXT NOT NULL,
    PRIMARY KEY (workspace_id, user_id, capability)
);
CREATE INDEX workspace_members_user_idx ON workspace_members (user_id);

-- The mutable half: one editable draft per agent, guarded by draft_revision (optimistic
-- concurrency - a stale revision is a 409, never a silent overwrite). current_version is the
-- published version new runs resolve; publishing sets it, rollback moves it back.
CREATE TABLE agent_definitions (
    workspace_id     TEXT NOT NULL REFERENCES workspaces(id),
    id               TEXT NOT NULL,
    draft_name       TEXT NOT NULL,
    draft_spec_json  TEXT NOT NULL,
    draft_revision   INT  NOT NULL,
    status           TEXT NOT NULL DEFAULT 'ACTIVE',
    current_version  INT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       TEXT NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       TEXT NOT NULL,
    PRIMARY KEY (workspace_id, id)
);

-- The immutable half: rows are only ever inserted. No delete path exists, so a version a run
-- pinned stays available for provenance.
CREATE TABLE agent_definition_versions (
    workspace_id  TEXT NOT NULL,
    agent_id      TEXT NOT NULL,
    version       INT  NOT NULL,
    name          TEXT NOT NULL,
    spec_json     TEXT NOT NULL,
    content_hash  TEXT NOT NULL,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by  TEXT NOT NULL,
    PRIMARY KEY (workspace_id, agent_id, version),
    FOREIGN KEY (workspace_id, agent_id) REFERENCES agent_definitions(workspace_id, id)
);
