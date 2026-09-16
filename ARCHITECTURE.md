# Architecture

## Overview

```
┌───────────────────────────── Paper 26.2 server ─────────────────────────────┐
│  SwedenCorePlugin (wiring)                                                  │
│  ┌──────────────── paper/ (adapter layer) ─────────────────┐                │
│  │ commands (Brigadier) · listeners · scheduler.Tasks      │  main thread   │
│  │ text.Messages (MiniMessage) · session.PlayerSessions    │  ⇅ hops        │
│  └───────────────────────────┬─────────────────────────────┘                │
│                              │ calls (on worker threads)                    │
│  ┌──────────────── domain (pure Java) ─────────────────────┐                │
│  │ core.NordiaCore → player · economy · skills · jobs ·    │                │
│  │ companies · contracts · reputation · properties ·       │                │
│  │ cities · settlements · localization                     │                │
│  └───────────────────────────┬─────────────────────────────┘                │
│                    database.Database (HikariCP, Tx, migrations)             │
└──────────────────────────────┼──────────────────────────────────────────────┘
                               ▼
                         PostgreSQL 18
```

## Modules

| Package | Responsibility |
|---|---|
| `core` | `NordiaCore` composition root, `CoreConfig`, `DomainException` |
| `database` | `Database` (pool, transactions, retry), `Tx` JDBC helper, `MigrationRunner` |
| `player` | Registration, identity, locale preference |
| `economy` | `Money`, accounts, ledger, transfers, mint/sink, audit |
| `localization` | Locale enum, message bundles with fallback |
| `skills` | Level curve, XP buffer, rate limiter, persistence |
| `reputation` | Clamped scores and idempotent event log |
| `companies` | Companies, roles, finances, staff, dissolution checks |
| `jobs` | Job board, applications, payroll (verified work minutes, arrears) |
| `contracts` | Escrowed contracts, deliveries, expiry |
| `inventory` | Item stash (player/company inventory held by the server) |
| `cities` / `properties` | Cities with treasuries; property regions, market, access lists, region index |
| `shops` | Physical chest shops, tokened purchases, sales history |
| `settlements` | Player-founded settlements and tier growth |
| `trade` | Trade state machine and atomic trade completion |
| `orders` | Buy orders |
| `production` / `market` | Recipes at facility properties; 7-day market statistics |
| `finance` | Loans, bankruptcy |
| `logistics` | Transport jobs with collateral |
| `shares` | Share registry, escrowed offers and bids, company holdings, dividends, valuation, closing equity |
| `player` | Registration and profiles |
| `events` | Domain events published after commit |
| `paper` | Everything that touches Bukkit/Paper/Adventure |

All modules follow the same shape: records for domain values, one service per module with SQL inside, extension
hooks (dissolution checks, ownership hooks, placement checks) instead of cyclic dependencies.

### Cross-module hooks
Later modules must be able to veto or react to earlier ones without the earlier module knowing about them:
`CompanyService.addDissolutionCheck` (contracts, properties, orders, leases, transports), `PropertyService.addOwnershipChangeHook`
(leases end on a new owner), `PropertyService.addOccupancyChangeHook` (shops close when the occupant changes — sale,
seizure, tenancy start/end), `CityService.addPlacementCheck` (settlements), `CompanyService.addFoundingHook` (initial
shares), `CompanyService.addWithdrawalCheck` (shareholders block owner withdrawals),
`CompanyService.addPreCloseHook` (share bids/offers unwind so escrow returns before residual cash is read) and
`CompanyService.setEquityCloser` (shares decide who receives a closing company's residual in dissolution and solvent
bankruptcy; the default pays the owner). Hooks run inside the caller's transaction.

## Key decisions and why

### One plugin, modular packages (not multiple plugins or services)
A single process with a single database transaction boundary makes cross-module atomicity trivial (e.g. contract state
change + escrow payout in one transaction). Splitting into plugins would require inter-plugin APIs and distributed
consistency for no benefit at our scale.

### Domain is pure Java; Paper is an adapter
Economic correctness is the heart of NORDIA and must be tested exhaustively. Keeping Bukkit out of domain packages means
tests run against real PostgreSQL in seconds without a server or mocking framework. `ArchitectureTest` enforces it.

### Plain JDBC instead of an ORM
Economic operations need precise control over locking (`FOR UPDATE`, lock ordering), `ON CONFLICT`, and transaction
boundaries. An ORM hides exactly the things we must see. `Tx` removes JDBC boilerplate without hiding SQL.

### Custom migration runner instead of Flyway
Flyway must be shaded and relocated in a Paper plugin (its ServiceLoader-based database plugins break under relocation)
and gates new PostgreSQL versions behind releases. We need ordered SQL files, one transaction each, checksums and an
advisory lock — about 200 lines. See `MigrationRunner`.

### Money as `long` öre with a double-entry-style ledger
Floating point cannot represent money. Every balance change is a `transactions` row with balances-after, so any balance
can be audited and history/valuation/news can be derived later. Money creation is explicit (`SYSTEM:MINT`), making the
money supply observable — a core design goal (no artificial money).

### Synchronous services + async adapter
Services are simple blocking code that is easy to reason about and test. The Paper layer decides threading:
`Tasks.run(sender, work, onSuccess)` executes `work` on a bounded worker pool and `onSuccess` on the main thread.
Per-player in-flight limits stop command spam from starving the connection pool.

### Registration in AsyncPlayerPreLoginEvent
The event runs off the main thread, so registration can block on the database. If it fails, login is refused:
a player in a world whose economy can't persist would create inconsistencies.

### Brigadier commands via Paper lifecycle API
Paper plugins (`paper-plugin.yml`) register commands through `LifecycleEvents.COMMANDS`, giving typed arguments and
client-side suggestions.

### Localization in the adapter, error codes in the domain
Domain throws `DomainException("economy.insufficient_funds", args)`; the adapter renders `error.economy.insufficient_funds`
in the viewer's language. Arguments are inserted unparsed, so player-controlled text can never inject MiniMessage tags.

## Threading rules

1. Never call a domain service on the main thread (exception: none).
2. Never touch Bukkit world/entity/inventory state off the main thread; use `Tasks.sync`.
3. `TxWork` may be retried — no side effects outside the database inside a transaction lambda.
4. Item flows: remove items on the main thread → commit async → on failure give items back on the main thread.

## Error handling

- `DomainException` → localized message to the player; no stack trace.
- Anything else → logged with stack trace, generic `error.internal` to the player.
- Database down at startup → server shutdown (configurable).
