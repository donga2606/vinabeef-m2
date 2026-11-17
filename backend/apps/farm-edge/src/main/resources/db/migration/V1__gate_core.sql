-- Farm-edge local store (owner: Dong A). Source of truth for farm gate movements and count.

-- One row per farm; locked FOR UPDATE by every ingest, which serialises gate processing
-- (prototype: one gate) and gives a gapless edge_seq.
CREATE TABLE gate_state (
    farm_id       TEXT PRIMARY KEY,
    last_edge_seq BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE reader_state (
    reader_id TEXT PRIMARY KEY,
    last_seq  BIGINT NOT NULL
);

-- Missed reads detected from reader seq jumps (evidence; the reader may not buffer).
CREATE TABLE reader_gap (
    id          BIGSERIAL PRIMARY KEY,
    reader_id   TEXT        NOT NULL,
    from_seq    BIGINT      NOT NULL,
    to_seq      BIGINT      NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE passage (
    passage_id           UUID PRIMARY KEY,
    edge_seq             BIGINT      NOT NULL UNIQUE,
    farm_id              TEXT        NOT NULL,
    tag_id               TEXT        NOT NULL,
    direction            TEXT        NOT NULL CHECK (direction IN ('IN', 'OUT')),
    presence_after       TEXT        NOT NULL CHECK (presence_after IN ('INSIDE', 'OUTSIDE')),
    anomaly              BOOLEAN     NOT NULL,
    observed_at          TIMESTAMPTZ NOT NULL,
    reader_id            TEXT        NOT NULL,
    first_observation_id UUID        NOT NULL,
    observation_count    INT         NOT NULL DEFAULT 1,
    committed_at         TIMESTAMPTZ NOT NULL
);
CREATE INDEX passage_tag_idx ON passage (tag_id, edge_seq DESC);

-- Raw source evidence: every accepted observation, with what it was decided to mean.
CREATE TABLE observation (
    observation_id UUID PRIMARY KEY,
    reader_id      TEXT        NOT NULL,
    seq            BIGINT      NOT NULL,
    tag_id         TEXT        NOT NULL,
    direction      TEXT        NOT NULL,
    observed_at    TIMESTAMPTZ NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL,
    outcome        TEXT        NOT NULL CHECK (outcome IN ('NEW_PASSAGE', 'MERGED', 'LATE_NEEDS_REVIEW')),
    passage_id     UUID REFERENCES passage (passage_id)
);

CREATE TABLE tag_presence (
    tag_id          TEXT PRIMARY KEY,
    presence        TEXT        NOT NULL CHECK (presence IN ('INSIDE', 'OUTSIDE')),
    last_passage_id UUID        NOT NULL REFERENCES passage (passage_id),
    updated_at      TIMESTAMPTZ NOT NULL
);

-- Transactional outbox: written in the same transaction as the passage; relayed to central later.
CREATE TABLE outbox (
    event_id   UUID PRIMARY KEY REFERENCES passage (passage_id),
    edge_seq   BIGINT      NOT NULL UNIQUE,
    payload    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at    TIMESTAMPTZ,
    attempts   INT         NOT NULL DEFAULT 0,
    last_error TEXT
);
CREATE INDEX outbox_pending_idx ON outbox (edge_seq) WHERE sent_at IS NULL;
