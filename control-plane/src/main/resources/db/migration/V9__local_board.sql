-- Durable, restart-safe board state for the local/restaurant-local profiles, replacing the
-- volatile InMemoryBoardAdapter so items/comments survive control-plane restarts (tech-stack §6).
--
-- local_board_webhooks is a SEPARATE idempotency ledger from V1's ingested_events: it dedupes
-- replayed webhook payloads before WebhookController's IngestedEventStore dedupe layer runs, so a
-- replayed event never re-applies a mutation to the local_board_* rows. ingested_events is left
-- untouched (orchestration-decision §5 still owns cross-provider dedupe there).

CREATE TABLE local_board_items (
    profile         TEXT NOT NULL,
    board_id        TEXT NOT NULL,
    kind            TEXT NOT NULL,
    title           TEXT NOT NULL DEFAULT '',
    description     TEXT NOT NULL DEFAULT '',
    canonical_state TEXT NOT NULL,
    parent_id       TEXT,
    area_path       TEXT,
    idempotency_key TEXT,
    rev             BIGINT NOT NULL DEFAULT 0,
    comment_seq     BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (profile, board_id),
    -- A plain UNIQUE treats NULLs as distinct (Postgres), so many NULL keys coexist. The adapter
    -- only reaches the idempotent path with a non-NULL key, so this also keeps the adapter's
    -- ON CONFLICT (profile, idempotency_key) inference simple (no partial-index predicate needed).
    UNIQUE (profile, idempotency_key)
);

-- Matches InMemoryBoardAdapter's default floor (4412) so auto-minted numeric board ids look
-- identical across adapters.
CREATE SEQUENCE local_board_id_seq START WITH 4412;

CREATE TABLE local_board_comments (
    profile  TEXT NOT NULL,
    board_id TEXT NOT NULL,
    id       TEXT NOT NULL,
    ordinal  BIGINT NOT NULL,
    author   TEXT NOT NULL,      -- Comment.by (stored as author)
    role     TEXT NOT NULL,
    stage    TEXT NOT NULL,
    target   TEXT NOT NULL,
    text     TEXT NOT NULL,
    intent   TEXT NOT NULL,      -- change | question | note (Comment.Intent wire value)
    blocking BOOLEAN NOT NULL DEFAULT FALSE,
    version  INT NOT NULL,
    PRIMARY KEY (profile, board_id, id),
    UNIQUE (profile, board_id, ordinal),
    CONSTRAINT local_board_comments_item_fk
        FOREIGN KEY (profile, board_id) REFERENCES local_board_items (profile, board_id)
);

-- Append-only idempotency ledger keyed by (profile, board_id, rev): one kind per revision.
CREATE TABLE local_board_webhooks (
    profile  TEXT NOT NULL,
    board_id TEXT NOT NULL,
    rev      BIGINT NOT NULL,
    kind     TEXT NOT NULL,
    PRIMARY KEY (profile, board_id, rev)
);
