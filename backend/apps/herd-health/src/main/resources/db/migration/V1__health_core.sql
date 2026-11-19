CREATE TABLE farm (
    farm_id   TEXT PRIMARY KEY,
    farm_name TEXT NOT NULL
);

CREATE TABLE barn (
    barn_id   TEXT PRIMARY KEY,
    farm_id   TEXT NOT NULL REFERENCES farm (farm_id),
    barn_name TEXT NOT NULL,
    UNIQUE (farm_id, barn_id)
);

CREATE TABLE cattle (
    cattle_id          UUID PRIMARY KEY,
    rfid               TEXT NOT NULL UNIQUE,
    display_name       TEXT NOT NULL,
    registration_status TEXT NOT NULL CHECK (registration_status IN ('ACTIVE', 'INACTIVE', 'UNKNOWN')),
    resource_version   BIGINT NOT NULL DEFAULT 0 CHECK (resource_version >= 0),
    registered_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE cattle_custody (
    cattle_id       UUID PRIMARY KEY REFERENCES cattle (cattle_id),
    farm_id         TEXT NOT NULL REFERENCES farm (farm_id),
    barn_id         TEXT NOT NULL,
    custody_version BIGINT NOT NULL DEFAULT 0 CHECK (custody_version >= 0),
    updated_at      TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (farm_id, barn_id) REFERENCES barn (farm_id, barn_id)
);
CREATE INDEX cattle_custody_farm_barn_idx ON cattle_custody (farm_id, barn_id, cattle_id);

CREATE TABLE vaccination_schedule (
    schedule_id UUID PRIMARY KEY,
    cattle_id   UUID NOT NULL REFERENCES cattle (cattle_id),
    vaccine_code TEXT NOT NULL,
    due_at      TIMESTAMPTZ NOT NULL,
    status      TEXT NOT NULL CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX vaccination_schedule_cattle_idx ON vaccination_schedule (cattle_id, due_at);

CREATE TABLE health_intent (
    intent_id          UUID PRIMARY KEY,
    cattle_id          UUID NOT NULL REFERENCES cattle (cattle_id),
    submitted_farm_id  TEXT NOT NULL REFERENCES farm (farm_id),
    submitted_barn_id  TEXT NOT NULL,
    actor_sub          TEXT NOT NULL,
    payload_hash       CHAR(64) NOT NULL,
    submitted_payload  JSONB NOT NULL,
    expected_version   BIGINT NOT NULL CHECK (expected_version >= 0),
    schedule_id        UUID REFERENCES vaccination_schedule (schedule_id),
    correction_of_record_id UUID,
    correction_reason  TEXT,
    status             TEXT NOT NULL CHECK (status IN ('PROCESSING', 'ACCEPTED', 'NEEDS_REVIEW', 'REJECTED')),
    created_at         TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (submitted_farm_id, submitted_barn_id)
        REFERENCES barn (farm_id, barn_id),
    CHECK ((correction_of_record_id IS NULL AND correction_reason IS NULL)
        OR (correction_of_record_id IS NOT NULL AND correction_reason IS NOT NULL))
);
CREATE INDEX health_intent_cattle_created_idx ON health_intent (cattle_id, created_at DESC);

-- Clinical records are append-only. Corrections insert a linked row; they never rewrite history.
CREATE TABLE clinical_record (
    record_id              UUID PRIMARY KEY,
    cattle_id              UUID NOT NULL REFERENCES cattle (cattle_id),
    intent_id              UUID NOT NULL UNIQUE REFERENCES health_intent (intent_id),
    record_version         BIGINT NOT NULL CHECK (record_version > 0),
    record_type            TEXT NOT NULL CHECK (record_type IN ('VACCINATION', 'HEALTH_STATUS', 'TREATMENT')),
    payload                JSONB NOT NULL,
    occurred_at            TIMESTAMPTZ NOT NULL,
    recorded_at            TIMESTAMPTZ NOT NULL,
    device_ref             TEXT NOT NULL,
    origin_farm_id         TEXT NOT NULL REFERENCES farm (farm_id),
    schedule_id            UUID REFERENCES vaccination_schedule (schedule_id),
    correction_of_record_id UUID REFERENCES clinical_record (record_id),
    correction_reason      TEXT,
    UNIQUE (cattle_id, record_version),
    CHECK ((correction_of_record_id IS NULL AND correction_reason IS NULL)
        OR (correction_of_record_id IS NOT NULL AND correction_reason IS NOT NULL))
);
CREATE INDEX clinical_record_cattle_version_idx ON clinical_record (cattle_id, record_version DESC);
CREATE INDEX clinical_record_cattle_occurred_idx ON clinical_record (cattle_id, occurred_at DESC, record_version DESC);

-- One effective record per scheduled dose; correction changes this pointer, not the old row.
CREATE TABLE schedule_completion (
    schedule_id        UUID PRIMARY KEY REFERENCES vaccination_schedule (schedule_id),
    effective_record_id UUID NOT NULL UNIQUE REFERENCES clinical_record (record_id),
    completed_at       TIMESTAMPTZ NOT NULL
);
ALTER TABLE health_intent
    ADD CONSTRAINT health_intent_correction_record_fk
    FOREIGN KEY (correction_of_record_id) REFERENCES clinical_record (record_id);

CREATE TABLE operation_receipt (
    intent_id    UUID PRIMARY KEY REFERENCES health_intent (intent_id),
    http_status  SMALLINT NOT NULL CHECK (http_status IN (201, 409)),
    response_json JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

-- Source evidence is a required local audit fact and is committed with the clinical outcome.
CREATE TABLE source_evidence (
    evidence_id UUID PRIMARY KEY,
    intent_id   UUID NOT NULL UNIQUE REFERENCES health_intent (intent_id),
    actor_sub   TEXT NOT NULL,
    cattle_id   UUID NOT NULL REFERENCES cattle (cattle_id),
    farm_id     TEXT NOT NULL REFERENCES farm (farm_id),
    barn_id     TEXT NOT NULL,
    action      TEXT NOT NULL CHECK (action = 'HEALTH_INTENT_OUTCOME'),
    payload_hash CHAR(64) NOT NULL,
    outcome     TEXT NOT NULL CHECK (outcome IN ('ACCEPTED', 'NEEDS_REVIEW', 'REJECTED')),
    captured_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (farm_id, barn_id) REFERENCES barn (farm_id, barn_id)
);

-- Durable hand-off to the canonical audit collector. Publishing is asynchronous; local source
-- evidence remains authoritative for whether the Health transaction committed.
CREATE TABLE health_audit_outbox (
    event_id     UUID PRIMARY KEY,
    intent_id    UUID NOT NULL UNIQUE REFERENCES health_intent (intent_id),
    event_type   TEXT NOT NULL,
    payload      JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error   TEXT
);
CREATE INDEX health_audit_outbox_pending_idx
    ON health_audit_outbox (created_at, event_id) WHERE published_at IS NULL;
