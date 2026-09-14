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
| V2 | `skills` (reference), `skill_progress` |
| V3 | `reputation_events`, `companies`, `jobs` (reference), `job_positions`, `company_employees`, `job_applications`, `payroll_entries` |
| V4 | `contracts`, `contract_deliveries` (unique token), `item_stash` |
| V5 | `cities`, `properties`, `property_trusted`, `property_sales` |
| V6 | `shops`, `shop_listings`, `shop_sales` (unique token) |
| V7 | `settlements`, `settlement_members`, `settlement_invites`, `settlement_tier_history` |
| V8 | `trades` (unique token) |
| V9 | `buy_orders`, `buy_order_fills` (unique token); `ORDER` account owner type |
| V10 | `item_stash.pristine`, `production_runs` |
| V11 | `loans`, `loan_payments`, `bankruptcies`; `BANKRUPTCY` employee end reason |
| V12 | `property_leases` (one open lease per property), `lease_payments` |
| V13 | `transports` (pickup/delivery tokens); `TRANSPORT` account owner type (purposes `ESCROW`, `COLLATERAL`) |
| V14 | `production_runs.facility`, `production_runs.outputs` (snapshot of total outputs) |
| V15 | `share_holdings`, `share_offers`, `share_trades`, `dividends`; initial 1 000 shares for existing active companies |

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

### skills / skill_progress
`skills(id, sort_order)` is reference data (FK target). `skill_progress(player_uuid, skill_id, xp, level)` — `xp ≥ 0`,
capped by the configured curve; `level` is denormalised for leaderboards and always recomputed from `xp`.
XP is buffered in memory per online player and flushed in batches (`xp = xp + delta` under a row lock).

### reputation_events
Auditable log of reputation changes for `PLAYER`/`COMPANY` subjects with `score_after` and an optional unique
`idempotency_key` (e.g. `wage-default:<payroll id>`). Scores live in `players.reputation` / `companies.reputation`,
clamped to −100…100.

### companies
`id, name, owner_uuid, status (ACTIVE|DISSOLVED|BANKRUPT), reputation, founded_at, dissolved_at`.
Unique `lower(name)` **among active companies** (partial index). Each company has an account `COMPANY:<id>:MAIN`.

### jobs / job_positions
`jobs(id, skill_id NULL)` catalogue (MINER→MINING … SHOP_ASSISTANT→none). `job_positions(company_id, job_id, title,
required_level, salary_per_hour öre, openings, status OPEN|CLOSED)`.

### company_employees
Membership rows: `role OWNER|MANAGER|EMPLOYEE`, optional `position_id`, `salary_per_hour` (snapshot, editable),
`hired_at`, `ended_at`, `end_reason LEFT|TERMINATED|DISSOLVED`. Partial unique index: one active membership per
(company, player). History is kept (rows are ended, never deleted).

### job_applications
`status PENDING|ACCEPTED|REJECTED|WITHDRAWN|CLOSED`; partial unique index: one PENDING application per (position, applicant).

### payroll_entries
One row per batch of verified work minutes: `employee_id, company_id, player_uuid, period_start, work_minutes,
salary_per_hour, amount, status PAID|UNPAID, transaction_id`. `UNIQUE(employee_id, period_start)` makes batches
idempotent. UNPAID rows are the company's wage arrears, settled oldest-first (`payroll:<id>` transaction key).

### contracts / contract_deliveries
Issuer is a player or company (CHECK enforces exactly one). Reward escrowed in account `CONTRACT:<id>:ESCROW`;
`paid_out ≤ reward`. Status `OPEN|IN_PROGRESS|COMPLETED|CANCELLED|EXPIRED` with contractor consistency CHECK.
Deliveries carry a client-generated unique `token` so an ambiguous commit can be verified before returning items.

### item_stash
Opaque serialized item stacks (≤ 64 KiB, amount 1–99) held for a `PLAYER` or `COMPANY`. Claiming sets `claimed_at`
and `claimed_by` before items are handed out (crash ⇒ possible loss, never duplication).

### cities / properties
`cities(name unique ci, world, center, radius)` with account `CITY:<id>`. `properties` store an inclusive cuboid,
`type`, optional `city_id`, `owner_type/owner_id` (NULL iff `AVAILABLE`), `price`, `market_value`. Overlaps are
prevented in the service under `pg_advisory_xact_lock(hashtext('properties:<world>'))`. `property_sales` is the price
history; `property_trusted` extra builders.

### shops / shop_listings / shop_sales
One shop per SHOP property. A listing is bound to a container location (unique world+xyz), an item template, bundle
size and price. `shop_sales` records each purchase with a unique `token` and the ledger transaction.

### settlements
`settlements(name unique ci among ACTIVE, world, center, tier, leader_uuid, status, founded_at)` with account
`SETTLEMENT:<id>`. Radius is derived from the tier via configuration (not stored). `settlement_members` has a partial
unique index on `player_uuid WHERE left_at IS NULL` (one settlement per player). `settlement_tier_history` records
when each tier was reached. Placement is serialised with `pg_advisory_xact_lock(hashtext('settlements:<world>'))`.

### trades
One row per completed direct trade: both players, money each way, human-readable item summaries, unique `token`.
Money transfers reference the trade id and use keys `trade:<token>:ab|ba`.

### buy_orders / buy_order_fills
Issuer player or company; `quantity`, `filled ≤ quantity`, `unit_price`; escrow account `ORDER:<id>:ESCROW`.
Each fill has a unique `token`, the quantity and payout.

### production_runs
Company, recipe id, batches, operator, `RUNNING|COMPLETED`, `finishes_at` (from the service clock). Inputs are consumed
from pristine company stash rows at start (partial rows re-created with the item codec); outputs deposited on completion.
`facility` (FACTORY/INDUSTRIAL_LAND/FARM/MINE) scopes capacity; `outputs` (`MATERIAL:amount;…`) is written at start so
recipe edits never change a running job (NULL only for pre-V14 runs, which fall back to the current recipe).
Stash capacity is not a column: it is derived from occupied WAREHOUSE properties (see `ItemStashService.usage`).

### share_holdings / share_offers / share_trades / dividends
`share_holdings` (PK company, holder_type, holder_id; `quantity > 0`, rows deleted at zero). Holder types `PLAYER`
(uuid text) and `TREASURY` (the company's own id). A company's total shares = holdings + `remaining` of OPEN offers —
listed shares are moved out of the holding into the offer (escrow). `share_offers.status` OPEN → SOLD | CANCELLED |
EXPIRED, `closed_at` set iff not OPEN, SOLD requires `remaining = 0`; `buyer_uuid` marks a private offer.
`share_trades` is the price history (valuation); `dividends` enforces `total = per_share × shares`.
Lock order for share operations: company row → offer row → holding → accounts. Holdings of closed companies are kept
as history; queries filter on `companies.status = 'ACTIVE'`.

### loans / loan_payments / bankruptcies
`loans`: lender and borrower (`PLAYER|COMPANY` + id, never equal), principal, total repayment, repaid, schedule
(installments, interval hours), status `OFFERED|ACTIVE|REPAID|DEFAULTED|DECLINED|WITHDRAWN|SETTLED_IN_BANKRUPTCY`,
`accepted_at` (schedule anchor). `loan_payments` records every installment/early/bankruptcy payment with its ledger
transaction. `bankruptcies` (one per company) records assets, wages paid, creditors paid, unpaid debt, properties seized.

### property_leases / lease_payments
`LISTED` (rental offer) → `ACTIVE` (tenant set, `paid_until`) ↔ `OVERDUE` (`overdue_since`) → `ENDED` (`end_reason`
TENANT, OWNER, EVICTED, OWNERSHIP_CHANGE, TENANT_BANKRUPT) or `CANCELLED`. Partial unique index allows one open lease per
property. `ends_at` marks an owner-ended lease that stops at the paid period. The **occupant** of a property is the
tenant of its ACTIVE/OVERDUE lease, otherwise the owner (`PropertyService.SELECT` joins the lease).

## Invariants (verified by `/eco audit` and tests)

1. `SUM(accounts.balance) = 0` — money is conserved; MINT is negative by the amount ever created.
2. For every account: `balance = Σ incoming amount − Σ outgoing amount`.
3. No non-system account is ever negative.
4. Money in circulation = `SUM(balance) WHERE owner_type <> 'SYSTEM'`.

## Locking protocol

Transfers lock both account rows with `SELECT … FOR UPDATE ORDER BY id` before reading balances. All code that locks
more than one row of the same table must lock in ascending id order. When locking rows from different tables, lock
**domain rows first, then accounts**. Established order:

`players (founding serialisation) → companies → job_positions → job_applications → company_employees → payroll_entries
→ contracts / buy_orders / properties / shop_listings / settlements → reputation subject rows → skill_progress → accounts`
