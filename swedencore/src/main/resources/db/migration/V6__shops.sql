-- V6: physical player/company shops. There is deliberately no global auction house: every listing is a chest at a
-- real location inside a SHOP property, and buyers must be there to buy.

CREATE TABLE shops (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id BIGINT      NOT NULL REFERENCES properties (id),
    name        TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'OPEN',
    created_by  UUID        NOT NULL REFERENCES players (uuid),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT shops_status_chk CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT shops_name_chk CHECK (length(name) BETWEEN 3 AND 32),
    CONSTRAINT shops_property_uq UNIQUE (property_id)
);

CREATE TABLE shop_listings (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id      BIGINT      NOT NULL REFERENCES shops (id) ON DELETE CASCADE,
    world        TEXT        NOT NULL,
    x            INT         NOT NULL,
    y            INT         NOT NULL,
    z            INT         NOT NULL,
    material     TEXT        NOT NULL,
    item         BYTEA       NOT NULL,
    bundle_size  INT         NOT NULL,
    price        BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT shop_listings_location_uq UNIQUE (world, x, y, z),
    CONSTRAINT shop_listings_bundle_chk CHECK (bundle_size BETWEEN 1 AND 64),
    CONSTRAINT shop_listings_price_chk CHECK (price > 0),
    CONSTRAINT shop_listings_item_size_chk CHECK (octet_length(item) <= 65536)
);

CREATE INDEX shop_listings_shop_idx ON shop_listings (shop_id);
CREATE INDEX shop_listings_material_idx ON shop_listings (material, price);

-- Sales history: revenue, pricing and competition data for companies, valuation (P4) and news (P5).
CREATE TABLE shop_sales (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shop_id        BIGINT      NOT NULL REFERENCES shops (id),
    listing_id     BIGINT,
    buyer_uuid     UUID        NOT NULL REFERENCES players (uuid),
    material       TEXT        NOT NULL,
    bundles        INT         NOT NULL,
    items          INT         NOT NULL,
    unit_price     BIGINT      NOT NULL,
    total          BIGINT      NOT NULL,
    token          UUID        NOT NULL UNIQUE,
    transaction_id BIGINT REFERENCES transactions (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT shop_sales_amounts_chk CHECK (bundles > 0 AND items > 0 AND unit_price > 0 AND total > 0)
);

CREATE INDEX shop_sales_shop_idx ON shop_sales (shop_id, id DESC);
