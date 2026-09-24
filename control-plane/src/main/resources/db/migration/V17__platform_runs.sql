-- Configurable agent platform, phase 1 slice 4 (docs/phase-1-execution-spec.md): durable
-- single-agent runs. The row pins the exact agent version + content hash and the model/connection
-- resolved at start; inputs and outputs live here (access-controlled), never in Temporal history.
-- Owned by the runs module; the agents worker's runner reads the pinned definition and writes the
-- outcome through status-guarded updates only.
CREATE TABLE platform_runs (
    id                UUID PRIMARY KEY,
    workspace_id      TEXT NOT NULL REFERENCES workspaces(id),
    agent_id          TEXT NOT NULL,
    agent_version     INT  NOT NULL,
    content_hash      TEXT NOT NULL,
    model             TEXT NOT NULL,
    provider_model    TEXT NOT NULL,
    connection_id     TEXT NOT NULL REFERENCES connections(id),
    fallback          BOOLEAN NOT NULL,
    input_json        TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'QUEUED'
                      CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    output_text       TEXT,
    output_json       TEXT,
    error             TEXT,
    prompt_tokens     INT,
    completion_tokens INT,
    attempts          INT NOT NULL DEFAULT 0,
    idempotency_key   TEXT,
    workflow_id       TEXT NOT NULL,
    created_by        TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    FOREIGN KEY (workspace_id, agent_id, agent_version)
        REFERENCES agent_definition_versions(workspace_id, agent_id, version),
    UNIQUE (workspace_id, idempotency_key)
);
CREATE INDEX platform_runs_agent_idx ON platform_runs (workspace_id, agent_id, created_at DESC);
