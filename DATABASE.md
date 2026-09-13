# Database

PostgreSQL 18 (dev via docker compose, tests via embedded PostgreSQL). Access through HikariCP + JDBC.

## Migrations

- Location: `swedencore/src/main/resources/db/migration/V<n>__<description>.sql`, contiguous from 1.
- Applied at plugin start by `MigrationRunner`, each in its own transaction, under a PostgreSQL advisory lock.
- History table: `schema_migrations(version, description, checksum, applied_at, execution_ms)`.
- **Committed migrations are immutable.** Startup fails on checksum mismatch, or if the database has migrations this
  plugin build does not know (prevents running an older plugin against a newer schema).

| Version | Contents |
|---|---|
| V1 | `players`, `accounts`, `transactions`, system accounts MINT and SINK |

## Global conventions

- Money: `BIGINT` öre. Never `NUMERIC`/`REAL` in schema for balances.
- Surrogate ids: `BIGINT GENERATED ALWAYS AS IDENTITY`; players keyed by Minecraft `UUID`.
- Timestamps: `TIMESTAMPTZ`, default `now()`.
- Enumerations stored as `TEXT` with `CHECK` constraints mirroring Java enums.
- "Only one active X" rules use partial unique indexes.
- Anything that can happen twice by retry/race has a unique idempotency key.

## Tables

### players
| Column | Type | Notes |
|---|---|---|
| uuid | UUID PK | Minecraft UUID |
| name | TEXT | last known name (1–16 chars); names can move between players, lookups pick most recent `last_seen` |
| locale | TEXT NULL | `sv_SE`/`en_US`, NULL = server default |
| reputation | INT | −100…100 |
| first_seen, last_seen | TIMESTAMPTZ | |

### accounts
| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | |
| owner_type | TEXT | SYSTEM, PLAYER, COMPANY, CITY, SETTLEMENT, CONTRACT |
| owner_id | TEXT | UUID or numeric id as text |
| purpose | TEXT | `MAIN`, `ESCROW` … |
| balance | BIGINT | öre |
| allow_negative | BOOLEAN | only SYSTEM accounts (enforced by CHECK) |
| frozen | BOOLEAN | frozen accounts cannot send or receive |

Constraints: `UNIQUE(owner_type, owner_id, purpose)`, `CHECK(allow_negative OR balance >= 0)`,
`CHECK(NOT allow_negative OR owner_type = 'SYSTEM')`.

### transactions (the ledger)
| Column | Type | Notes |
|---|---|---|
| id | BIGINT PK | |
| idempotency_key | TEXT UNIQUE NULL | repeated key → no second application |
| type | TEXT | `TransactionType` |
| from_account_id, to_account_id | BIGINT FK | distinct (CHECK) |
| amount | BIGINT | > 0 (CHECK) |
| from_balance_after, to_balance_after | BIGINT | snapshot for audit/history |
| actor_uuid | UUID NULL | who caused it |
| reference_type, reference_id | TEXT NULL | e.g. `CONTRACT`, `42` |
| memo | TEXT NULL | ≤ 200 chars |

## Invariants (verified by `/eco audit` and tests)

1. `SUM(accounts.balance) = 0` — money is conserved; MINT is negative by the amount ever created.
2. For every account: `balance = Σ incoming amount − Σ outgoing amount`.
3. No non-system account is ever negative.
4. Money in circulation = `SUM(balance) WHERE owner_type <> 'SYSTEM'`.

## Locking protocol

Transfers lock both account rows with `SELECT … FOR UPDATE ORDER BY id` before reading balances. All code that locks
more than one row of the same table must lock in ascending id order. When locking rows from different tables, lock
**domain rows first (e.g. contract, company), then accounts**.
