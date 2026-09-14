-- V9: buy orders ("Buying 10 000 iron ore at 18 SEK each"). Escrowed, fillable by many sellers.

ALTER TABLE accounts DROP CONSTRAINT accounts_owner_type_chk;
ALTER TABLE accounts ADD CONSTRAINT accounts_owner_type_chk
    CHECK (owner_type IN ('SYSTEM', 'PLAYER', 'COMPANY', 'CITY', 'SETTLEMENT', 'CONTRACT', 'ORDER'));

CREATE TABLE buy_orders (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issuer_type        TEXT        NOT NULL,
    issuer_player_uuid UUID REFERENCES players (uuid),
    issuer_company_id  BIGINT REFERENCES companies (id),
    created_by         UUID        NOT NULL REFERENCES players (uuid),
    material           TEXT        NOT NULL,
    quantity           INT         NOT NULL,
    filled             INT         NOT NULL DEFAULT 0,
    unit_price         BIGINT      NOT NULL,
    status             TEXT        NOT NULL DEFAULT 'OPEN',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deadline_at        TIMESTAMPTZ NOT NULL,
    closed_at          TIMESTAMPTZ,
    CONSTRAINT buy_orders_issuer_chk CHECK (
        (issuer_type = 'PLAYER' AND issuer_player_uuid IS NOT NULL AND issuer_company_id IS NULL) OR
        (issuer_type = 'COMPANY' AND issuer_company_id IS NOT NULL AND issuer_player_uuid IS NULL)),
    CONSTRAINT buy_orders_quantity_chk CHECK (quantity BETWEEN 1 AND 1000000 AND filled >= 0 AND filled <= quantity),
    CONSTRAINT buy_orders_price_chk CHECK (unit_price > 0),
    CONSTRAINT buy_orders_status_chk CHECK (status IN ('OPEN', 'FILLED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT buy_orders_closed_chk CHECK ((status = 'OPEN') = (closed_at IS NULL))
);

CREATE INDEX buy_orders_open_idx ON buy_orders (material, unit_price DESC) WHERE status = 'OPEN';
CREATE INDEX buy_orders_deadline_idx ON buy_orders (deadline_at) WHERE status = 'OPEN';
CREATE INDEX buy_orders_issuer_player_idx ON buy_orders (issuer_player_uuid, status);
CREATE INDEX buy_orders_issuer_company_idx ON buy_orders (issuer_company_id, status);

CREATE TABLE buy_order_fills (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id       BIGINT      NOT NULL REFERENCES buy_orders (id),
    seller_uuid    UUID        NOT NULL REFERENCES players (uuid),
    token          UUID        NOT NULL UNIQUE,
    quantity       INT         NOT NULL,
    payout         BIGINT      NOT NULL,
    transaction_id BIGINT REFERENCES transactions (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT buy_order_fills_chk CHECK (quantity > 0 AND payout > 0)
);

CREATE INDEX buy_order_fills_order_idx ON buy_order_fills (order_id);
