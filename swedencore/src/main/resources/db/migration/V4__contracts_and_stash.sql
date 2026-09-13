-- V4: contracts with escrow, deliveries and the item stash.

CREATE TABLE contracts (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issuer_type        TEXT        NOT NULL,
    issuer_player_uuid UUID REFERENCES players (uuid),
    issuer_company_id  BIGINT REFERENCES companies (id),
    created_by         UUID        NOT NULL REFERENCES players (uuid),
    type               TEXT        NOT NULL,
    title              TEXT        NOT NULL,
    material           TEXT,
    quantity           INT,
    delivered          INT         NOT NULL DEFAULT 0,
    reward             BIGINT      NOT NULL,
    paid_out           BIGINT      NOT NULL DEFAULT 0,
    required_skill     TEXT REFERENCES skills (id),
    required_level     INT         NOT NULL DEFAULT 1,
    xp_reward          BIGINT      NOT NULL DEFAULT 0,
    status             TEXT        NOT NULL DEFAULT 'OPEN',
    contractor_uuid    UUID REFERENCES players (uuid),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at        TIMESTAMPTZ,
    deadline_at        TIMESTAMPTZ NOT NULL,
    closed_at          TIMESTAMPTZ,
    CONSTRAINT contracts_issuer_type_chk CHECK (issuer_type IN ('PLAYER', 'COMPANY')),
    CONSTRAINT contracts_issuer_chk CHECK (
        (issuer_type = 'PLAYER' AND issuer_player_uuid IS NOT NULL AND issuer_company_id IS NULL) OR
        (issuer_type = 'COMPANY' AND issuer_company_id IS NOT NULL AND issuer_player_uuid IS NULL)),
    CONSTRAINT contracts_type_chk CHECK (type IN ('ITEM_DELIVERY', 'SERVICE')),
    CONSTRAINT contracts_delivery_chk CHECK (
        (type = 'ITEM_DELIVERY' AND material IS NOT NULL AND quantity BETWEEN 1 AND 1000000) OR
        (type = 'SERVICE' AND material IS NULL AND quantity IS NULL)),
    CONSTRAINT contracts_delivered_chk CHECK (delivered >= 0 AND (quantity IS NULL OR delivered <= quantity)),
    CONSTRAINT contracts_reward_chk CHECK (reward > 0 AND paid_out >= 0 AND paid_out <= reward),
    CONSTRAINT contracts_title_chk CHECK (length(title) BETWEEN 3 AND 60),
    CONSTRAINT contracts_level_chk CHECK (required_level BETWEEN 1 AND 10000),
    CONSTRAINT contracts_xp_chk CHECK (xp_reward >= 0),
    CONSTRAINT contracts_status_chk CHECK (status IN ('OPEN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT contracts_contractor_chk CHECK (
        (status IN ('IN_PROGRESS', 'COMPLETED') AND contractor_uuid IS NOT NULL) OR
        (status = 'OPEN' AND contractor_uuid IS NULL) OR
        status IN ('CANCELLED', 'EXPIRED')),
    CONSTRAINT contracts_closed_chk CHECK ((status IN ('OPEN', 'IN_PROGRESS')) = (closed_at IS NULL))
);

CREATE INDEX contracts_open_idx ON contracts (reward DESC, id) WHERE status = 'OPEN';
CREATE INDEX contracts_active_deadline_idx ON contracts (deadline_at) WHERE status IN ('OPEN', 'IN_PROGRESS');
CREATE INDEX contracts_contractor_idx ON contracts (contractor_uuid, status);
CREATE INDEX contracts_issuer_player_idx ON contracts (issuer_player_uuid, status);
CREATE INDEX contracts_issuer_company_idx ON contracts (issuer_company_id, status);

CREATE TABLE contract_deliveries (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id     BIGINT      NOT NULL REFERENCES contracts (id),
    contractor_uuid UUID        NOT NULL REFERENCES players (uuid),
    -- Client-generated token: after an ambiguous failure (e.g. lost commit acknowledgement) the server checks whether
    -- the delivery was recorded before returning items, so items can never be duplicated.
    token           UUID        NOT NULL UNIQUE,
    quantity        INT         NOT NULL,
    payout          BIGINT      NOT NULL,
    transaction_id  BIGINT REFERENCES transactions (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT contract_deliveries_quantity_chk CHECK (quantity > 0),
    CONSTRAINT contract_deliveries_payout_chk CHECK (payout >= 0)
);

CREATE INDEX contract_deliveries_contract_idx ON contract_deliveries (contract_id);

-- Items held by the server on behalf of a player or company (e.g. contract deliveries awaiting pickup).
-- `item` is the platform's serialized item stack; `material`/`amount` are kept for display and validation.
CREATE TABLE item_stash (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_type  TEXT        NOT NULL,
    owner_id    TEXT        NOT NULL,
    item        BYTEA       NOT NULL,
    material    TEXT        NOT NULL,
    amount      INT         NOT NULL,
    source_type TEXT,
    source_id   TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at  TIMESTAMPTZ,
    claimed_by  UUID,
    CONSTRAINT item_stash_owner_type_chk CHECK (owner_type IN ('PLAYER', 'COMPANY')),
    CONSTRAINT item_stash_amount_chk CHECK (amount BETWEEN 1 AND 99),
    CONSTRAINT item_stash_size_chk CHECK (octet_length(item) <= 65536),
    CONSTRAINT item_stash_claim_chk CHECK ((claimed_at IS NULL) = (claimed_by IS NULL))
);

CREATE INDEX item_stash_unclaimed_idx ON item_stash (owner_type, owner_id, id) WHERE claimed_at IS NULL;
