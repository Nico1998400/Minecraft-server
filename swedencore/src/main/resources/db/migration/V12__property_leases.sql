-- V12: renting properties. A landlord earns rent; the tenant gets exclusive use of the premises.

CREATE TABLE property_leases (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    property_id   BIGINT      NOT NULL REFERENCES properties (id),
    rent          BIGINT      NOT NULL,
    period_hours  INT         NOT NULL,
    tenant_type   TEXT,
    tenant_id     TEXT,
    status        TEXT        NOT NULL DEFAULT 'LISTED',
    listed_by     UUID        NOT NULL REFERENCES players (uuid),
    listed_at     TIMESTAMPTZ NOT NULL,
    started_at    TIMESTAMPTZ,
    paid_until    TIMESTAMPTZ,
    overdue_since TIMESTAMPTZ,
    ends_at       TIMESTAMPTZ,
    ended_at      TIMESTAMPTZ,
    end_reason    TEXT,
    CONSTRAINT property_leases_rent_chk CHECK (rent > 0 AND period_hours BETWEEN 1 AND 8760),
    CONSTRAINT property_leases_status_chk CHECK (status IN ('LISTED', 'ACTIVE', 'OVERDUE', 'ENDED', 'CANCELLED')),
    CONSTRAINT property_leases_tenant_chk CHECK ((status IN ('ACTIVE', 'OVERDUE', 'ENDED')) = (tenant_type IS NOT NULL AND tenant_id IS NOT NULL)),
    CONSTRAINT property_leases_tenant_type_chk CHECK (tenant_type IS NULL OR tenant_type IN ('PLAYER', 'COMPANY')),
    CONSTRAINT property_leases_reason_chk CHECK (end_reason IS NULL OR end_reason IN ('TENANT', 'OWNER', 'EVICTED', 'OWNERSHIP_CHANGE', 'TENANT_BANKRUPT'))
);

-- At most one open lease (listing or tenancy) per property.
CREATE UNIQUE INDEX property_leases_open_uq ON property_leases (property_id) WHERE status IN ('LISTED', 'ACTIVE', 'OVERDUE');
CREATE INDEX property_leases_due_idx ON property_leases (paid_until) WHERE status IN ('ACTIVE', 'OVERDUE');
CREATE INDEX property_leases_tenant_idx ON property_leases (tenant_type, tenant_id) WHERE status IN ('ACTIVE', 'OVERDUE');

CREATE TABLE lease_payments (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lease_id       BIGINT      NOT NULL REFERENCES property_leases (id),
    amount         BIGINT      NOT NULL,
    period_start   TIMESTAMPTZ NOT NULL,
    period_end     TIMESTAMPTZ NOT NULL,
    transaction_id BIGINT REFERENCES transactions (id),
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT lease_payments_chk CHECK (amount > 0 AND period_end > period_start)
);

CREATE INDEX lease_payments_lease_idx ON lease_payments (lease_id, id);
