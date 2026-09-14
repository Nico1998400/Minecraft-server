-- V11: player/company loans and company bankruptcy.
-- Loans never create money: principal moves from lender to borrower, repayments move back.

CREATE TABLE loans (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lender_type       TEXT        NOT NULL,
    lender_id         TEXT        NOT NULL,
    borrower_type     TEXT        NOT NULL,
    borrower_id       TEXT        NOT NULL,
    created_by        UUID        NOT NULL REFERENCES players (uuid),
    principal         BIGINT      NOT NULL,
    total_repayment   BIGINT      NOT NULL,
    repaid            BIGINT      NOT NULL DEFAULT 0,
    installments      INT         NOT NULL,
    interval_hours    INT         NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'OFFERED',
    offered_at        TIMESTAMPTZ NOT NULL,
    accepted_at       TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    CONSTRAINT loans_party_type_chk CHECK (lender_type IN ('PLAYER', 'COMPANY') AND borrower_type IN ('PLAYER', 'COMPANY')),
    CONSTRAINT loans_distinct_chk CHECK (NOT (lender_type = borrower_type AND lender_id = borrower_id)),
    CONSTRAINT loans_amount_chk CHECK (principal > 0 AND total_repayment >= principal AND repaid >= 0 AND repaid <= total_repayment),
    CONSTRAINT loans_schedule_chk CHECK (installments BETWEEN 1 AND 104 AND interval_hours BETWEEN 1 AND 8760),
    CONSTRAINT loans_status_chk CHECK (status IN ('OFFERED', 'ACTIVE', 'REPAID', 'DEFAULTED', 'DECLINED', 'WITHDRAWN', 'SETTLED_IN_BANKRUPTCY')),
    CONSTRAINT loans_accepted_chk CHECK ((status IN ('OFFERED', 'DECLINED', 'WITHDRAWN')) = (accepted_at IS NULL))
);

CREATE INDEX loans_active_idx ON loans (status) WHERE status IN ('ACTIVE', 'DEFAULTED');
CREATE INDEX loans_borrower_idx ON loans (borrower_type, borrower_id, status);
CREATE INDEX loans_lender_idx ON loans (lender_type, lender_id, status);

CREATE TABLE loan_payments (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    loan_id        BIGINT      NOT NULL REFERENCES loans (id),
    amount         BIGINT      NOT NULL,
    kind           TEXT        NOT NULL,
    transaction_id BIGINT REFERENCES transactions (id),
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT loan_payments_amount_chk CHECK (amount > 0),
    CONSTRAINT loan_payments_kind_chk CHECK (kind IN ('INSTALLMENT', 'BANKRUPTCY_DISTRIBUTION', 'EARLY_REPAYMENT'))
);

CREATE INDEX loan_payments_loan_idx ON loan_payments (loan_id, id);

CREATE TABLE bankruptcies (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id          BIGINT      NOT NULL UNIQUE REFERENCES companies (id),
    reason              TEXT        NOT NULL,
    assets              BIGINT      NOT NULL,
    paid_wages          BIGINT      NOT NULL,
    paid_creditors      BIGINT      NOT NULL,
    unpaid_debt         BIGINT      NOT NULL,
    properties_seized   INT         NOT NULL,
    declared_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT bankruptcies_reason_chk CHECK (reason IN ('LOAN_DEFAULT', 'VOLUNTARY'))
);

ALTER TABLE company_employees DROP CONSTRAINT company_employees_reason_chk;
ALTER TABLE company_employees ADD CONSTRAINT company_employees_reason_chk
    CHECK (end_reason IS NULL OR end_reason IN ('LEFT', 'TERMINATED', 'DISSOLVED', 'BANKRUPTCY'));
