-- Configurable agent platform, phase 2 slice 2.1 (docs/phase-2-execution-spec.md): governed API
-- tools. Tool definitions follow the agent registry's draft + immutable-version shape (owned by the
-- registry module); connection grants belong to the connections module; the tool-call trace is
-- owned by the runs module and written only by the agents worker's ToolExecutor.

CREATE TABLE tool_definitions (
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

CREATE TABLE tool_definition_versions (
    workspace_id  TEXT NOT NULL,
    tool_id       TEXT NOT NULL,
    version       INT  NOT NULL,
    name          TEXT NOT NULL,
    spec_json     TEXT NOT NULL,
    content_hash  TEXT NOT NULL,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by  TEXT NOT NULL,
    PRIMARY KEY (workspace_id, tool_id, version),
    FOREIGN KEY (workspace_id, tool_id) REFERENCES tool_definitions(workspace_id, id)
);

-- An enterprise connection is usable by a workspace's tools only while granted. Deleting the row
-- is the revocation: the executor re-checks it before every call, so a revoke takes effect on the
-- next call of a running agent.
CREATE TABLE connection_grants (
    connection_id TEXT NOT NULL REFERENCES connections(id),
    workspace_id  TEXT NOT NULL REFERENCES workspaces(id),
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by    TEXT NOT NULL,
    PRIMARY KEY (connection_id, workspace_id)
);

-- Every tool call a run's model requested, allowed or denied. Arguments are stored (they are run
-- data, access-controlled like inputs); credentials never are. Response bodies are not stored.
CREATE TABLE platform_tool_calls (
    id              BIGSERIAL PRIMARY KEY,
    run_id          UUID NOT NULL REFERENCES platform_runs(id),
    attempt         INT  NOT NULL,
    turn            INT  NOT NULL,
    call_id         TEXT,
    tool_id         TEXT NOT NULL,
    tool_version    INT,
    args_json       TEXT,
    args_hash       TEXT,
    decision        TEXT NOT NULL CHECK (decision IN ('ALLOWED', 'DENIED')),
    reason          TEXT,
    http_status     INT,
    duration_ms     BIGINT,
    response_bytes  BIGINT,
    truncated       BOOLEAN NOT NULL DEFAULT FALSE,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX platform_tool_calls_run_idx ON platform_tool_calls (run_id, id);
