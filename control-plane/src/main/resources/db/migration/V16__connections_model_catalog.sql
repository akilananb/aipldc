-- Configurable agent platform, phase 1 slice 3 (docs/phase-1-execution-spec.md): connections and
-- the DB-backed model catalog. A connection stores only a secret *reference* (kv://name), resolved
-- by the execution adapter that uses it - never a secret value. Owned by the connections module
-- (configurable-agent-platform.md "Scalability and service boundaries").

CREATE TABLE connections (
    id           TEXT PRIMARY KEY,
    scope        TEXT NOT NULL CHECK (scope IN ('ENTERPRISE', 'WORKSPACE')),
    workspace_id TEXT REFERENCES workspaces(id),
    kind         TEXT NOT NULL,
    auth_type    TEXT NOT NULL,
    secret_ref   TEXT,
    base_url     TEXT,
    status       TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    expires_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   TEXT NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   TEXT NOT NULL,
    revoked_at   TIMESTAMPTZ,
    revoked_by   TEXT,
    CHECK ((scope = 'WORKSPACE') = (workspace_id IS NOT NULL))
);

-- Catalog id is what an AgentSpec binds (e.g. "sonnet", "anthropic/claude-sonnet");
-- provider_model is the name sent to the provider behind the connection.
CREATE TABLE models (
    id             TEXT PRIMARY KEY,
    connection_id  TEXT NOT NULL REFERENCES connections(id),
    provider_model TEXT NOT NULL,
    display_name   TEXT NOT NULL,
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     TEXT NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     TEXT NOT NULL
);

-- One row per one-time import from deployment config (pdlc.yaml), so an import never re-runs
-- over admin edits. Also used by the PDLC seed import (slice 6).
CREATE TABLE platform_imports (
    id          TEXT PRIMARY KEY,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    details     TEXT
);
