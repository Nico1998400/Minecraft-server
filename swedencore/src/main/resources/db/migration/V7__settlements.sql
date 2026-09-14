-- V7: player-founded settlements. "We built this" — never "/town create".

CREATE TABLE settlements (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         TEXT        NOT NULL,
    world        TEXT        NOT NULL,
    center_x     INT         NOT NULL,
    center_z     INT         NOT NULL,
    tier         TEXT        NOT NULL DEFAULT 'OUTPOST',
    leader_uuid  UUID        NOT NULL REFERENCES players (uuid),
    status       TEXT        NOT NULL DEFAULT 'ACTIVE',
    founded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    disbanded_at TIMESTAMPTZ,
    CONSTRAINT settlements_tier_chk CHECK (tier IN ('OUTPOST', 'SETTLEMENT', 'VILLAGE', 'TOWN', 'CITY')),
    CONSTRAINT settlements_status_chk CHECK (status IN ('ACTIVE', 'DISBANDED')),
    CONSTRAINT settlements_disbanded_chk CHECK ((status = 'ACTIVE') = (disbanded_at IS NULL)),
    CONSTRAINT settlements_name_chk CHECK (length(name) BETWEEN 3 AND 32)
);

CREATE UNIQUE INDEX settlements_active_name_uq ON settlements (lower(name)) WHERE status = 'ACTIVE';
CREATE INDEX settlements_world_idx ON settlements (world) WHERE status = 'ACTIVE';

CREATE TABLE settlement_members (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_id BIGINT      NOT NULL REFERENCES settlements (id),
    player_uuid   UUID        NOT NULL REFERENCES players (uuid),
    role          TEXT        NOT NULL,
    joined_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at       TIMESTAMPTZ,
    CONSTRAINT settlement_members_role_chk CHECK (role IN ('LEADER', 'OFFICER', 'RESIDENT'))
);

-- A player lives in at most one settlement at a time.
CREATE UNIQUE INDEX settlement_members_active_player_uq ON settlement_members (player_uuid) WHERE left_at IS NULL;
CREATE INDEX settlement_members_settlement_idx ON settlement_members (settlement_id) WHERE left_at IS NULL;

CREATE TABLE settlement_invites (
    settlement_id BIGINT      NOT NULL REFERENCES settlements (id),
    player_uuid   UUID        NOT NULL REFERENCES players (uuid),
    invited_by    UUID        NOT NULL REFERENCES players (uuid),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (settlement_id, player_uuid)
);

-- When each tier was reached: the settlement's story (and future news material).
CREATE TABLE settlement_tier_history (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_id BIGINT      NOT NULL REFERENCES settlements (id),
    tier          TEXT        NOT NULL,
    reached_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    members       INT         NOT NULL,
    treasury      BIGINT      NOT NULL
);

CREATE INDEX settlement_tier_history_idx ON settlement_tier_history (settlement_id, id);
