-- Central movement subset (owner: Dong A). A projection of farm-edge facts; the edge store stays the source of truth.

-- Inbox / dedupe: one row per event ever applied. Same eventId again = duplicate, no second effect.
CREATE TABLE received_event (
    event_id       UUID PRIMARY KEY,
    farm_id        TEXT        NOT NULL,
    edge_seq       BIGINT      NOT NULL,
    tag_id         TEXT        NOT NULL,
    direction      TEXT        NOT NULL,
    presence_after TEXT        NOT NULL,
    anomaly        BOOLEAN     NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    payload        TEXT        NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (farm_id, edge_seq)
);

-- Current presence per farm/tag. Only a newer edge_seq may overwrite (reorder-safe).
CREATE TABLE tag_presence (
    farm_id       TEXT        NOT NULL,
    tag_id        TEXT        NOT NULL,
    presence      TEXT        NOT NULL,
    last_edge_seq BIGINT      NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (farm_id, tag_id)
);
