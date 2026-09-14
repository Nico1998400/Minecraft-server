-- V16: share bids — the buy side of the peer-to-peer share market. A bid escrows price × quantity in its own account
-- (owner type SHARE_BID); sellers fill it with free shares and are paid from escrow. Unfilled escrow is refunded.

ALTER TABLE accounts DROP CONSTRAINT accounts_owner_type_chk;
ALTER TABLE accounts ADD CONSTRAINT accounts_owner_type_chk
    CHECK (owner_type IN ('SYSTEM', 'PLAYER', 'COMPANY', 'CITY', 'SETTLEMENT', 'CONTRACT', 'ORDER', 'TRANSPORT', 'SHARE_BID'));

CREATE TABLE share_bids (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id      BIGINT      NOT NULL REFERENCES companies (id),
    buyer_uuid      UUID        NOT NULL REFERENCES players (uuid),
    quantity        BIGINT      NOT NULL,
    remaining       BIGINT      NOT NULL,
    price_per_share BIGINT      NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    closed_at       TIMESTAMPTZ,
    CONSTRAINT share_bids_amount_chk CHECK (quantity > 0 AND remaining >= 0 AND remaining <= quantity AND price_per_share > 0),
    CONSTRAINT share_bids_status_chk CHECK (status IN ('OPEN', 'FILLED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT share_bids_open_chk CHECK ((status = 'OPEN') = (closed_at IS NULL)),
    CONSTRAINT share_bids_filled_chk CHECK (status <> 'FILLED' OR remaining = 0)
);

CREATE INDEX share_bids_open_idx ON share_bids (company_id, price_per_share DESC) WHERE status = 'OPEN';
CREATE INDEX share_bids_expiry_idx ON share_bids (expires_at) WHERE status = 'OPEN';
CREATE INDEX share_bids_buyer_idx ON share_bids (buyer_uuid) WHERE status = 'OPEN';

-- A trade fills either an offer (ask) or a bid.
ALTER TABLE share_trades ALTER COLUMN offer_id DROP NOT NULL;
ALTER TABLE share_trades ADD COLUMN bid_id BIGINT REFERENCES share_bids (id);
ALTER TABLE share_trades ADD CONSTRAINT share_trades_source_chk CHECK ((offer_id IS NULL) <> (bid_id IS NULL));
