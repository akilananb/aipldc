-- One row per agent process ever seen: ACP build-workers (fed by /api/build-tasks claim+heartbeat)
-- and reasoning workers (fed by the scheduled Temporal DescribeTaskQueue probe). Rows are never
-- deleted, so an agent that stops polling stays listed as offline.
CREATE TABLE agent_presence (
    name             TEXT NOT NULL,
    kind             TEXT NOT NULL,          -- acp | reasoning
    profile          TEXT NOT NULL,
    acp_agent        TEXT,                   -- acp only: ACP_AGENT_CMD, e.g. "omp acp"
    repo_mode        TEXT,                   -- acp: local | remote | payload ; reasoning: configured
    repo_location    TEXT,                   -- path (local) or url (remote/configured); NULL for payload
    repo_branch      TEXT,                   -- acp local: current HEAD branch; reasoning: profile default_branch
    poll_interval_ms INT,
    first_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (name, kind)
);
