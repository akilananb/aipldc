-- Configurable agent platform, phase 2 slice 2.5 (docs/phase-2-execution-spec.md): runs of a2a
-- agents delegate to a remote A2A agent. They pin the A2A_AGENT connection in connection_id and
-- have no model. Remote identity is persisted as soon as it is known, so a retried invocation
-- reconciles through the remote task's status instead of sending again.
ALTER TABLE platform_runs ALTER COLUMN model DROP NOT NULL;
ALTER TABLE platform_runs ALTER COLUMN provider_model DROP NOT NULL;

ALTER TABLE platform_runs DROP CONSTRAINT platform_runs_status_check;
ALTER TABLE platform_runs ADD CONSTRAINT platform_runs_status_check
    CHECK (status IN ('QUEUED', 'RUNNING', 'AWAITING_APPROVAL', 'NEEDS_OPERATOR', 'AWAITING_INPUT', 'AWAITING_AUTH',
                      'SUCCEEDED', 'FAILED', 'CANCELLED'));

-- Operator replies to a remote agent (REMOTE_USER) and the remote agent's questions (REMOTE_AGENT).
ALTER TABLE platform_run_messages DROP CONSTRAINT platform_run_messages_kind_check;
ALTER TABLE platform_run_messages ADD CONSTRAINT platform_run_messages_kind_check
    CHECK (kind IN ('USER', 'ASSISTANT', 'TOOL_RESULT', 'REMOTE_USER', 'REMOTE_AGENT'));

-- The remote task an a2a run follows. cancel records what the remote agent did with a cancel request.
CREATE TABLE platform_remote_tasks (
    run_id      UUID PRIMARY KEY REFERENCES platform_runs(id),
    dialect     TEXT NOT NULL,
    task_id     TEXT,
    context_id  TEXT,
    state       TEXT NOT NULL,
    status_text TEXT,
    cancel      TEXT CHECK (cancel IN ('REQUESTED', 'ACKNOWLEDGED', 'REFUSED', 'UNSUPPORTED', 'NOT_FOUND', 'FAILED')),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every message an a2a run sends, recorded INTENDED before sending and ACKED once the remote agent
-- answered: a retry that finds SENT without an answer reconciles instead of resending.
CREATE TABLE platform_remote_sends (
    run_id     UUID NOT NULL REFERENCES platform_runs(id),
    message_id TEXT NOT NULL,
    state      TEXT NOT NULL CHECK (state IN ('INTENDED', 'SENT', 'ACKED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, message_id)
);
