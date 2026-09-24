-- Configurable agent platform, phase 2 slice 2.2 (docs/phase-2-execution-spec.md): write effects
-- and approvals. A run can pause (AWAITING_APPROVAL / NEEDS_OPERATOR) and resume, so its
-- conversation lives here rather than in one activity's memory (and never in Temporal history).

ALTER TABLE platform_runs DROP CONSTRAINT platform_runs_status_check;
ALTER TABLE platform_runs ADD CONSTRAINT platform_runs_status_check
    CHECK (status IN ('QUEUED', 'RUNNING', 'AWAITING_APPROVAL', 'NEEDS_OPERATOR', 'SUCCEEDED', 'FAILED', 'CANCELLED'));
-- The agent's timeoutSeconds bounds active time only; waiting for a human does not consume it.
ALTER TABLE platform_runs ADD COLUMN active_ms BIGINT NOT NULL DEFAULT 0;

-- The tool loop's conversation, in order: USER (rendered prompt), ASSISTANT (text, tool calls,
-- token usage), TOOL_RESULT (one per call, what the model was shown).
CREATE TABLE platform_run_messages (
    run_id       UUID NOT NULL REFERENCES platform_runs(id),
    seq          INT  NOT NULL,
    kind         TEXT NOT NULL CHECK (kind IN ('USER', 'ASSISTANT', 'TOOL_RESULT')),
    content_json TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);

ALTER TABLE platform_tool_calls DROP CONSTRAINT platform_tool_calls_decision_check;
ALTER TABLE platform_tool_calls ADD CONSTRAINT platform_tool_calls_decision_check
    CHECK (decision IN ('ALLOWED', 'DENIED', 'PENDING_APPROVAL'));

-- One approval per requested WRITE call, bound to the exact tool version and canonical args hash.
-- Decided only by a workspace REVIEWER who did not start the run; never approved by time.
CREATE TABLE platform_approvals (
    id            UUID PRIMARY KEY,
    run_id        UUID NOT NULL REFERENCES platform_runs(id),
    workspace_id  TEXT NOT NULL REFERENCES workspaces(id),
    turn          INT  NOT NULL,
    call_id       TEXT NOT NULL,
    tool_id       TEXT NOT NULL,
    tool_version  INT  NOT NULL,
    args_json     TEXT NOT NULL,
    args_hash     TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    escalated_at  TIMESTAMPTZ,
    decided_by    TEXT,
    decided_at    TIMESTAMPTZ,
    reason        TEXT,
    UNIQUE (run_id, turn, call_id)
);
CREATE INDEX platform_approvals_inbox_idx ON platform_approvals (workspace_id, status, requested_at);

-- The effect intent recorded before every write, keyed by run:turn:callId (reused on every retry).
-- UNKNOWN = the request may have reached the target; resent only with the target's
-- Idempotency-Key support, otherwise an operator resolves it.
CREATE TABLE platform_effects (
    id              UUID PRIMARY KEY,
    run_id          UUID NOT NULL REFERENCES platform_runs(id),
    approval_id     UUID NOT NULL REFERENCES platform_approvals(id),
    tool_id         TEXT NOT NULL,
    tool_version    INT  NOT NULL,
    args_hash       TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    state           TEXT NOT NULL CHECK (state IN ('INTENDED', 'SENT', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    send_count      INT  NOT NULL DEFAULT 0,
    http_status     INT,
    result_content  TEXT,
    resolution      TEXT CHECK (resolution IN ('SUCCEEDED', 'FAILED', 'RETRY')),
    resolved_by     TEXT,
    resolved_at     TIMESTAMPTZ,
    note            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX platform_effects_run_idx ON platform_effects (run_id);
