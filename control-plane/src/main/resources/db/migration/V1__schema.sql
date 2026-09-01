-- tech-stack-architecture.md §5 "Data model (Postgres)" - column names verbatim, pilot subset.
-- Two audit trails that must agree: review_events (here) and review.md (git) - tech-stack §3.3.

CREATE TABLE work_items (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile           TEXT NOT NULL,
    board_provider    TEXT NOT NULL,
    board_id          TEXT NOT NULL,
    kind              TEXT NOT NULL,
    parent_id         TEXT,
    canonical_state   TEXT NOT NULL,
    spec_change_path  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile, board_id)
);

CREATE TABLE artifacts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_item_id   UUID NOT NULL REFERENCES work_items (id),
    kind           TEXT NOT NULL,             -- pilot: 'story'
    version        INT NOT NULL,
    content_hash   TEXT NOT NULL,
    git_ref        TEXT,
    created_by     TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (work_item_id, version)
);

CREATE TABLE comments (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    artifact_id           UUID NOT NULL REFERENCES artifacts (id),
    version               INT NOT NULL,
    author_sub            TEXT NOT NULL,
    role                  TEXT NOT NULL,
    anchor_json           TEXT,
    text                  TEXT NOT NULL,
    intent                TEXT NOT NULL,      -- change | question | note
    blocking              BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_in_version   INT,
    agent_reply           TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- append-only: no UPDATE/DELETE in code
CREATE TABLE approvals (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    artifact_id    UUID NOT NULL REFERENCES artifacts (id),
    version        INT NOT NULL,
    content_hash   TEXT NOT NULL,
    author_sub     TEXT NOT NULL,
    role           TEXT NOT NULL,
    stage          TEXT NOT NULL,
    at             TIMESTAMPTZ NOT NULL
);

-- append-only; mirrors review.md
CREATE TABLE review_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_item_id   UUID NOT NULL REFERENCES work_items (id),
    ts             TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind           TEXT NOT NULL,      -- drafted | comment | request-changes | revision | approve | gate-passed | stale-escalation
    payload_json   TEXT NOT NULL
);

CREATE TABLE runs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    work_item_id      UUID NOT NULL REFERENCES work_items (id),
    agent             TEXT NOT NULL,
    workflow_run_id   TEXT,
    trace_url         TEXT,
    tokens            BIGINT,
    iterations        INT,
    outcome           TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- orchestration-decision.md §5: event ingress dedupes by item+rev.
CREATE TABLE ingested_events (
    profile    TEXT NOT NULL,
    item_id    TEXT NOT NULL,
    rev        BIGINT NOT NULL,
    kind       TEXT NOT NULL,
    ts         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (profile, item_id, rev)
);
