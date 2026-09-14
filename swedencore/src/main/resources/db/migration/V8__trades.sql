-- V8: direct player-to-player trades. Items are exchanged in the world; this records the deal and moves any money
-- atomically. The token makes a completion idempotent and lets the server resolve an ambiguous commit.

CREATE TABLE trades (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token        UUID        NOT NULL UNIQUE,
    player_a     UUID        NOT NULL REFERENCES players (uuid),
    player_b     UUID        NOT NULL REFERENCES players (uuid),
    money_a_to_b BIGINT      NOT NULL DEFAULT 0,
    money_b_to_a BIGINT      NOT NULL DEFAULT 0,
    items_a      TEXT        NOT NULL,
    items_b      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT trades_distinct_chk CHECK (player_a <> player_b),
    CONSTRAINT trades_money_chk CHECK (money_a_to_b >= 0 AND money_b_to_a >= 0),
    CONSTRAINT trades_summary_chk CHECK (length(items_a) <= 2000 AND length(items_b) <= 2000)
);

CREATE INDEX trades_player_a_idx ON trades (player_a, id DESC);
CREATE INDEX trades_player_b_idx ON trades (player_b, id DESC);
