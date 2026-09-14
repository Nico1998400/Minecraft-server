-- V15: company shares (P4). Shares never create money: they change who owns a company's equity.
-- Holder types: PLAYER (holder_id = uuid) and TREASURY (holder_id = the company's own id; unissued/owned by the company).

CREATE TABLE share_holdings (
    company_id  BIGINT NOT NULL REFERENCES companies (id),
    holder_type TEXT   NOT NULL,
    holder_id   TEXT   NOT NULL,
    quantity    BIGINT NOT NULL,
    PRIMARY KEY (company_id, holder_type, holder_id),
    CONSTRAINT share_holdings_type_chk CHECK (holder_type IN ('PLAYER', 'TREASURY')),
    CONSTRAINT share_holdings_quantity_chk CHECK (quantity > 0)
);

CREATE INDEX share_holdings_holder_idx ON share_holdings (holder_type, holder_id);

-- Listed shares are escrowed: removed from the seller's holding while the offer is open.
CREATE TABLE share_offers (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id      BIGINT      NOT NULL REFERENCES companies (id),
    seller_type     TEXT        NOT NULL,
    seller_id       TEXT        NOT NULL,
    created_by      UUID        NOT NULL REFERENCES players (uuid),
    quantity        BIGINT      NOT NULL,
    remaining       BIGINT      NOT NULL,
    price_per_share BIGINT      NOT NULL,
    buyer_uuid      UUID REFERENCES players (uuid),
    status          TEXT        NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    closed_at       TIMESTAMPTZ,
    CONSTRAINT share_offers_seller_type_chk CHECK (seller_type IN ('PLAYER', 'TREASURY')),
    CONSTRAINT share_offers_amount_chk CHECK (quantity > 0 AND remaining >= 0 AND remaining <= quantity AND price_per_share > 0),
    CONSTRAINT share_offers_status_chk CHECK (status IN ('OPEN', 'SOLD', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT share_offers_open_chk CHECK ((status = 'OPEN') = (closed_at IS NULL)),
    CONSTRAINT share_offers_sold_chk CHECK (status <> 'SOLD' OR remaining = 0)
);

CREATE INDEX share_offers_open_idx ON share_offers (company_id, price_per_share) WHERE status = 'OPEN';
CREATE INDEX share_offers_expiry_idx ON share_offers (expires_at) WHERE status = 'OPEN';
CREATE INDEX share_offers_seller_idx ON share_offers (seller_type, seller_id) WHERE status = 'OPEN';

CREATE TABLE share_trades (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id      BIGINT      NOT NULL REFERENCES companies (id),
    offer_id        BIGINT      NOT NULL REFERENCES share_offers (id),
    seller_type     TEXT        NOT NULL,
    seller_id       TEXT        NOT NULL,
    buyer_uuid      UUID        NOT NULL REFERENCES players (uuid),
    quantity        BIGINT      NOT NULL,
    price_per_share BIGINT      NOT NULL,
    total           BIGINT      NOT NULL,
    fee             BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT share_trades_amount_chk CHECK (quantity > 0 AND price_per_share > 0 AND total > 0 AND fee >= 0)
);

CREATE INDEX share_trades_company_idx ON share_trades (company_id, created_at DESC);

CREATE TABLE dividends (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id  BIGINT      NOT NULL REFERENCES companies (id),
    declared_by UUID        NOT NULL REFERENCES players (uuid),
    per_share   BIGINT      NOT NULL,
    shares      BIGINT      NOT NULL,
    total       BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT dividends_amount_chk CHECK (per_share > 0 AND shares > 0 AND total = per_share * shares)
);

CREATE INDEX dividends_company_idx ON dividends (company_id, id DESC);

-- Existing active companies: the owner holds the initial 1 000 shares.
INSERT INTO share_holdings (company_id, holder_type, holder_id, quantity)
SELECT id, 'PLAYER', owner_uuid::text, 1000 FROM companies WHERE status = 'ACTIVE';
