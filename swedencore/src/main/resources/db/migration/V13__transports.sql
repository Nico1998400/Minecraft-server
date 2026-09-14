-- V13: logistics — transport jobs that require physically carrying goods between two locations.

ALTER TABLE accounts DROP CONSTRAINT accounts_owner_type_chk;
ALTER TABLE accounts ADD CONSTRAINT accounts_owner_type_chk
    CHECK (owner_type IN ('SYSTEM', 'PLAYER', 'COMPANY', 'CITY', 'SETTLEMENT', 'CONTRACT', 'ORDER', 'TRANSPORT'));

CREATE TABLE transports (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issuer_type        TEXT        NOT NULL,
    issuer_player_uuid UUID REFERENCES players (uuid),
    issuer_company_id  BIGINT REFERENCES companies (id),
    created_by         UUID        NOT NULL REFERENCES players (uuid),
    material           TEXT        NOT NULL,
    quantity           INT         NOT NULL,
    reward             BIGINT      NOT NULL,
    collateral         BIGINT      NOT NULL,
    xp_reward          BIGINT      NOT NULL,
    pickup_world       TEXT        NOT NULL,
    pickup_x           INT         NOT NULL,
    pickup_y           INT         NOT NULL,
    pickup_z           INT         NOT NULL,
    dest_world         TEXT        NOT NULL,
    dest_x             INT         NOT NULL,
    dest_y             INT         NOT NULL,
    dest_z             INT         NOT NULL,
    status             TEXT        NOT NULL DEFAULT 'OPEN',
    carrier_uuid       UUID REFERENCES players (uuid),
    pickup_token       UUID UNIQUE,
    delivery_token     UUID UNIQUE,
    created_at         TIMESTAMPTZ NOT NULL,
    picked_up_at       TIMESTAMPTZ,
    deadline_at        TIMESTAMPTZ NOT NULL,
    closed_at          TIMESTAMPTZ,
    CONSTRAINT transports_issuer_chk CHECK (
        (issuer_type = 'PLAYER' AND issuer_player_uuid IS NOT NULL AND issuer_company_id IS NULL) OR
        (issuer_type = 'COMPANY' AND issuer_company_id IS NOT NULL AND issuer_player_uuid IS NULL)),
    CONSTRAINT transports_amounts_chk CHECK (quantity BETWEEN 1 AND 100000 AND reward > 0 AND collateral >= 0 AND xp_reward >= 0),
    CONSTRAINT transports_status_chk CHECK (status IN ('OPEN', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED', 'FAILED')),
    CONSTRAINT transports_carrier_chk CHECK ((status IN ('IN_TRANSIT', 'DELIVERED', 'FAILED')) = (carrier_uuid IS NOT NULL)),
    CONSTRAINT transports_closed_chk CHECK ((status IN ('OPEN', 'IN_TRANSIT')) = (closed_at IS NULL))
);

CREATE INDEX transports_open_idx ON transports (reward DESC) WHERE status = 'OPEN';
CREATE INDEX transports_deadline_idx ON transports (deadline_at) WHERE status IN ('OPEN', 'IN_TRANSIT');
CREATE INDEX transports_carrier_idx ON transports (carrier_uuid, status);
