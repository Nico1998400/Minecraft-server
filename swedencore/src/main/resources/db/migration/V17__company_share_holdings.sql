-- V17: companies as shareholders (P4 #45). A company may hold shares of *another* company; its own shares stay in
-- the treasury. Share bids and trades record an optional buyer company (null = the acting player bought personally).
-- Extraction (buying an accomplice's shares at an inflated price with outside shareholders' money) is enforced in
-- ShareService against book value, not here.

ALTER TABLE share_holdings DROP CONSTRAINT share_holdings_type_chk;
ALTER TABLE share_holdings ADD CONSTRAINT share_holdings_type_chk
    CHECK (holder_type IN ('PLAYER', 'TREASURY', 'COMPANY'));
ALTER TABLE share_holdings ADD CONSTRAINT share_holdings_not_self_chk
    CHECK (holder_type <> 'COMPANY' OR holder_id <> company_id::text);

ALTER TABLE share_offers DROP CONSTRAINT share_offers_seller_type_chk;
ALTER TABLE share_offers ADD CONSTRAINT share_offers_seller_type_chk
    CHECK (seller_type IN ('PLAYER', 'TREASURY', 'COMPANY'));
ALTER TABLE share_offers ADD CONSTRAINT share_offers_not_self_chk
    CHECK (seller_type <> 'COMPANY' OR seller_id <> company_id::text);

ALTER TABLE share_trades ADD COLUMN buyer_company_id BIGINT REFERENCES companies (id);

ALTER TABLE share_bids ADD COLUMN buyer_company_id BIGINT REFERENCES companies (id);
ALTER TABLE share_bids ADD CONSTRAINT share_bids_not_self_chk
    CHECK (buyer_company_id IS NULL OR buyer_company_id <> company_id);

CREATE INDEX share_bids_company_buyer_idx ON share_bids (buyer_company_id) WHERE status = 'OPEN' AND buyer_company_id IS NOT NULL;
CREATE INDEX share_offers_company_seller_idx ON share_offers (seller_id) WHERE status = 'OPEN' AND seller_type = 'COMPANY';
