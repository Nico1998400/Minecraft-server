-- V1: players, accounts and the transaction ledger.
--
-- Money is stored as BIGINT öre (1 SEK = 100 öre). Never use NUMERIC/float for balances in application code.
-- Invariant: SUM(accounts.balance) = 0. Money enters circulation only from the SYSTEM/MINT account (which may be
-- negative) and leaves it into SYSTEM/SINK. Every balance change has exactly one row in transactions.

CREATE TABLE players (
    uuid        UUID PRIMARY KEY,
    name        TEXT        NOT NULL,
    locale      TEXT,
    reputation  INT         NOT NULL DEFAULT 0,
    first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT players_name_chk CHECK (length(name) BETWEEN 1 AND 16),
    CONSTRAINT players_locale_chk CHECK (locale IS NULL OR locale IN ('sv_SE', 'en_US')),
    CONSTRAINT players_reputation_chk CHECK (reputation BETWEEN -100 AND 100)
);

CREATE INDEX players_name_lower_idx ON players (lower(name), last_seen DESC);

CREATE TABLE accounts (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_type     TEXT        NOT NULL,
    owner_id       TEXT        NOT NULL,
    purpose        TEXT        NOT NULL DEFAULT 'MAIN',
    balance        BIGINT      NOT NULL DEFAULT 0,
    allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
    frozen         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT accounts_owner_type_chk CHECK (owner_type IN ('SYSTEM', 'PLAYER', 'COMPANY', 'CITY', 'SETTLEMENT', 'CONTRACT')),
    CONSTRAINT accounts_balance_chk CHECK (allow_negative OR balance >= 0),
    CONSTRAINT accounts_owner_uq UNIQUE (owner_type, owner_id, purpose)
);

-- Only system accounts may ever go negative.
ALTER TABLE accounts ADD CONSTRAINT accounts_negative_only_system_chk CHECK (NOT allow_negative OR owner_type = 'SYSTEM');

CREATE TABLE transactions (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key    TEXT UNIQUE,
    type               TEXT        NOT NULL,
    from_account_id    BIGINT      NOT NULL REFERENCES accounts (id),
    to_account_id      BIGINT      NOT NULL REFERENCES accounts (id),
    amount             BIGINT      NOT NULL,
    from_balance_after BIGINT      NOT NULL,
    to_balance_after   BIGINT      NOT NULL,
    actor_uuid         UUID,
    reference_type     TEXT,
    reference_id       TEXT,
    memo               TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transactions_amount_chk CHECK (amount > 0),
    CONSTRAINT transactions_distinct_accounts_chk CHECK (from_account_id <> to_account_id),
    CONSTRAINT transactions_memo_chk CHECK (memo IS NULL OR length(memo) <= 200)
);

CREATE INDEX transactions_from_idx ON transactions (from_account_id, id DESC);
CREATE INDEX transactions_to_idx ON transactions (to_account_id, id DESC);
CREATE INDEX transactions_reference_idx ON transactions (reference_type, reference_id);

INSERT INTO accounts (owner_type, owner_id, purpose, allow_negative) VALUES ('SYSTEM', 'MINT', 'MAIN', TRUE);
INSERT INTO accounts (owner_type, owner_id, purpose, allow_negative) VALUES ('SYSTEM', 'SINK', 'MAIN', FALSE);
