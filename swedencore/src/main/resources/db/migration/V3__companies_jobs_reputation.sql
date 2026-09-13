-- V3: reputation, companies, jobs, employment and payroll.
--
-- Lock order for multi-row operations: companies → job_positions → job_applications → company_employees
-- → payroll_entries → players/companies (reputation) → accounts.

-- ─── Reputation ────────────────────────────────────────────────────────────────
CREATE TABLE reputation_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_type    TEXT        NOT NULL,
    subject_id      TEXT        NOT NULL,
    delta           INT         NOT NULL,
    score_after     INT         NOT NULL,
    reason          TEXT        NOT NULL,
    reference_type  TEXT,
    reference_id    TEXT,
    idempotency_key TEXT UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT reputation_events_subject_chk CHECK (subject_type IN ('PLAYER', 'COMPANY')),
    CONSTRAINT reputation_events_delta_chk CHECK (delta BETWEEN -200 AND 200)
);

CREATE INDEX reputation_events_subject_idx ON reputation_events (subject_type, subject_id, id DESC);

-- ─── Companies ─────────────────────────────────────────────────────────────────
CREATE TABLE companies (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         TEXT        NOT NULL,
    owner_uuid   UUID        NOT NULL REFERENCES players (uuid),
    status       TEXT        NOT NULL DEFAULT 'ACTIVE',
    reputation   INT         NOT NULL DEFAULT 0,
    founded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    dissolved_at TIMESTAMPTZ,
    CONSTRAINT companies_status_chk CHECK (status IN ('ACTIVE', 'DISSOLVED', 'BANKRUPT')),
    CONSTRAINT companies_reputation_chk CHECK (reputation BETWEEN -100 AND 100),
    CONSTRAINT companies_name_chk CHECK (length(name) BETWEEN 3 AND 32),
    CONSTRAINT companies_dissolved_chk CHECK ((status = 'ACTIVE') = (dissolved_at IS NULL))
);

-- Names are unique among active companies; dissolved names may be reused.
CREATE UNIQUE INDEX companies_active_name_uq ON companies (lower(name)) WHERE status = 'ACTIVE';
CREATE INDEX companies_owner_idx ON companies (owner_uuid) WHERE status = 'ACTIVE';

-- ─── Jobs ──────────────────────────────────────────────────────────────────────
CREATE TABLE jobs (
    id         TEXT PRIMARY KEY,
    skill_id   TEXT REFERENCES skills (id),
    sort_order INT NOT NULL
);

INSERT INTO jobs (id, skill_id, sort_order) VALUES
    ('MINER', 'MINING', 1),
    ('FARMER', 'FARMING', 2),
    ('HERBALIST', 'HERBALISM', 3),
    ('BUILDER', 'BUILDING', 4),
    ('LUMBERJACK', 'FORESTRY', 5),
    ('FISHER', 'FISHING', 6),
    ('ENGINEER', 'ENGINEERING', 7),
    ('LOGISTICS_WORKER', 'LOGISTICS', 8),
    ('SHOP_ASSISTANT', NULL, 9),
    ('MANAGER', NULL, 10),
    ('GENERAL_WORKER', NULL, 11);

CREATE TABLE job_positions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id      BIGINT      NOT NULL REFERENCES companies (id),
    job_id          TEXT        NOT NULL REFERENCES jobs (id),
    title           TEXT        NOT NULL,
    required_level  INT         NOT NULL DEFAULT 1,
    salary_per_hour BIGINT      NOT NULL,
    openings        INT         NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    CONSTRAINT job_positions_status_chk CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT job_positions_title_chk CHECK (length(title) BETWEEN 3 AND 40),
    CONSTRAINT job_positions_level_chk CHECK (required_level BETWEEN 1 AND 10000),
    CONSTRAINT job_positions_salary_chk CHECK (salary_per_hour >= 0),
    CONSTRAINT job_positions_openings_chk CHECK (openings BETWEEN 1 AND 1000)
);

CREATE INDEX job_positions_open_idx ON job_positions (status, salary_per_hour DESC) WHERE status = 'OPEN';
CREATE INDEX job_positions_company_idx ON job_positions (company_id);

CREATE TABLE company_employees (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id      BIGINT      NOT NULL REFERENCES companies (id),
    player_uuid     UUID        NOT NULL REFERENCES players (uuid),
    role            TEXT        NOT NULL,
    position_id     BIGINT REFERENCES job_positions (id),
    salary_per_hour BIGINT      NOT NULL DEFAULT 0,
    hired_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    end_reason      TEXT,
    CONSTRAINT company_employees_role_chk CHECK (role IN ('OWNER', 'MANAGER', 'EMPLOYEE')),
    CONSTRAINT company_employees_salary_chk CHECK (salary_per_hour >= 0),
    CONSTRAINT company_employees_end_chk CHECK ((ended_at IS NULL) = (end_reason IS NULL)),
    CONSTRAINT company_employees_reason_chk CHECK (end_reason IS NULL OR end_reason IN ('LEFT', 'TERMINATED', 'DISSOLVED'))
);

-- A player can hold only one active membership per company.
CREATE UNIQUE INDEX company_employees_active_uq ON company_employees (company_id, player_uuid) WHERE ended_at IS NULL;
CREATE INDEX company_employees_player_idx ON company_employees (player_uuid) WHERE ended_at IS NULL;
CREATE INDEX company_employees_position_idx ON company_employees (position_id) WHERE ended_at IS NULL;

CREATE TABLE job_applications (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    position_id    BIGINT      NOT NULL REFERENCES job_positions (id),
    applicant_uuid UUID        NOT NULL REFERENCES players (uuid),
    message        TEXT,
    status         TEXT        NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at     TIMESTAMPTZ,
    decided_by     UUID,
    CONSTRAINT job_applications_status_chk CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'CLOSED')),
    CONSTRAINT job_applications_message_chk CHECK (message IS NULL OR length(message) <= 200)
);

CREATE UNIQUE INDEX job_applications_pending_uq ON job_applications (position_id, applicant_uuid) WHERE status = 'PENDING';
CREATE INDEX job_applications_applicant_idx ON job_applications (applicant_uuid, status);

-- ─── Payroll ───────────────────────────────────────────────────────────────────
-- One row per batch of verified work minutes. (employee_id, period_start) makes each batch idempotent.
CREATE TABLE payroll_entries (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id     BIGINT      NOT NULL REFERENCES company_employees (id),
    company_id      BIGINT      NOT NULL REFERENCES companies (id),
    player_uuid     UUID        NOT NULL REFERENCES players (uuid),
    period_start    TIMESTAMPTZ NOT NULL,
    work_minutes    INT         NOT NULL,
    salary_per_hour BIGINT      NOT NULL,
    amount          BIGINT      NOT NULL,
    status          TEXT        NOT NULL,
    transaction_id  BIGINT REFERENCES transactions (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at         TIMESTAMPTZ,
    CONSTRAINT payroll_entries_period_uq UNIQUE (employee_id, period_start),
    CONSTRAINT payroll_entries_minutes_chk CHECK (work_minutes BETWEEN 1 AND 1440),
    CONSTRAINT payroll_entries_amount_chk CHECK (amount >= 0),
    CONSTRAINT payroll_entries_status_chk CHECK (status IN ('PAID', 'UNPAID')),
    CONSTRAINT payroll_entries_paid_chk CHECK ((status = 'PAID') = (paid_at IS NOT NULL))
);

CREATE INDEX payroll_entries_unpaid_idx ON payroll_entries (company_id, id) WHERE status = 'UNPAID';
CREATE INDEX payroll_entries_player_idx ON payroll_entries (player_uuid, id DESC);
