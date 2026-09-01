-- Claimable build tasks: Temporal async-completion rows for the standalone build agent.
CREATE TABLE build_tasks (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile          TEXT NOT NULL,
    story_board_id   TEXT NOT NULL,
    task_id          TEXT NOT NULL,
    attempt          INT NOT NULL,
    payload_json     TEXT NOT NULL,      -- claim payload, serialized once at enqueue (see A3)
    task_token       TEXT NOT NULL,      -- base64 Temporal async-completion token
    state            TEXT NOT NULL,      -- pending | claimed | done | failed | expired | superseded
    claimed_by       TEXT,
    lease_expires_at TIMESTAMPTZ,
    result_json      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX build_tasks_claim_idx ON build_tasks (state, profile, created_at);
