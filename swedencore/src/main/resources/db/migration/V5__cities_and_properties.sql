-- V5: cities and properties.

CREATE TABLE cities (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT        NOT NULL,
    world      TEXT        NOT NULL,
    center_x   INT         NOT NULL,
    center_z   INT         NOT NULL,
    radius     INT         NOT NULL,
    founded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cities_name_chk CHECK (length(name) BETWEEN 2 AND 32),
    CONSTRAINT cities_radius_chk CHECK (radius BETWEEN 16 AND 10000)
);

CREATE UNIQUE INDEX cities_name_uq ON cities (lower(name));

CREATE TABLE properties (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         TEXT        NOT NULL,
    type         TEXT        NOT NULL,
    world        TEXT        NOT NULL,
    min_x        INT         NOT NULL,
    min_y        INT         NOT NULL,
    min_z        INT         NOT NULL,
    max_x        INT         NOT NULL,
    max_y        INT         NOT NULL,
    max_z        INT         NOT NULL,
    city_id      BIGINT REFERENCES cities (id),
    owner_type   TEXT,
    owner_id     TEXT,
    status       TEXT        NOT NULL DEFAULT 'AVAILABLE',
    price        BIGINT      NOT NULL,
    market_value BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT properties_type_chk CHECK (type IN ('APARTMENT', 'HOUSE', 'SHOP', 'OFFICE', 'FACTORY', 'WAREHOUSE',
                                                   'INDUSTRIAL_LAND', 'FARM', 'MINE')),
    CONSTRAINT properties_bounds_chk CHECK (min_x <= max_x AND min_y <= max_y AND min_z <= max_z),
    CONSTRAINT properties_owner_type_chk CHECK (owner_type IS NULL OR owner_type IN ('PLAYER', 'COMPANY')),
    CONSTRAINT properties_status_chk CHECK (status IN ('AVAILABLE', 'OWNED', 'FOR_SALE')),
    -- AVAILABLE = nobody owns it (sold by the city/server); OWNED/FOR_SALE = has an owner.
    CONSTRAINT properties_owner_chk CHECK ((status = 'AVAILABLE') = (owner_type IS NULL AND owner_id IS NULL)),
    CONSTRAINT properties_price_chk CHECK (price >= 0 AND market_value >= 0),
    CONSTRAINT properties_name_chk CHECK (length(name) BETWEEN 3 AND 40)
);

CREATE INDEX properties_world_idx ON properties (world, min_x, max_x);
CREATE INDEX properties_owner_idx ON properties (owner_type, owner_id);
CREATE INDEX properties_market_idx ON properties (status, price) WHERE status IN ('AVAILABLE', 'FOR_SALE');

-- Players allowed to build in a property besides the owner (and owning company's staff).
CREATE TABLE property_trusted (
    property_id BIGINT      NOT NULL REFERENCES properties (id) ON DELETE CASCADE,
    player_uuid UUID        NOT NULL REFERENCES players (uuid),
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (property_id, player_uuid)
);

CREATE TABLE property_sales (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id    BIGINT      NOT NULL REFERENCES properties (id),
    seller_type    TEXT,
    seller_id      TEXT,
    buyer_type     TEXT        NOT NULL,
    buyer_id       TEXT        NOT NULL,
    price          BIGINT      NOT NULL,
    transaction_id BIGINT REFERENCES transactions (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT property_sales_price_chk CHECK (price >= 0)
);

CREATE INDEX property_sales_property_idx ON property_sales (property_id, id DESC);
