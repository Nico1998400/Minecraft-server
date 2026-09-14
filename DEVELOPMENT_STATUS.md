# Development Status

_Last updated: 2026-09-14_

## Current phase
**P0 complete. P1 complete. P2 in progress**: cities, properties and shops done; settlements next.

## Environment verified
- Paper 26.2 build 123 (STABLE) — requires Java 25
- Java 25 (Temurin, auto-provisioned by Gradle toolchain); Gradle 9.7.1 wrapper
- PostgreSQL 18 via `docker compose` for dev; embedded PostgreSQL 18 for tests (works on Windows, no Docker)
- Plugin verified on a real Paper 26.2 server after every phase: loads, migrates (V1–V4), commands respond, clean shutdown
- Not verified with a real game client (no client available in the dev environment) — gameplay listeners
  (XP from blocks/fishing, duty payroll, deliveries) are covered by domain tests only.

## Done
### P0 — Foundation
- [x] Git, Gradle (`:swedencore`), version catalog, shadow (relocated Hikari/pgjdbc), run-paper
- [x] Database: Hikari, `Tx`, retry on serialization failure/deadlock, checksum migrations + advisory lock
- [x] Economy: `Money` (öre), atomic lock-ordered transfers, idempotency keys, mint/sink, history, supply, audit
- [x] Player registration (AsyncPlayerPreLogin) with idempotent starter grant
- [x] Localization sv_SE/en_US, MiniMessage with unparsed placeholders, `/language`
- [x] `/balance`, `/pay`, `/transactions`, `/eco give|take|balance|supply|audit`, `/nordia [reload]`

### P1 — Core gameplay
- [x] Skills (V2): level curve, buffered XP + periodic flush, `/skills [player]`, `/skills top <skill>`
- [x] XP sources: Mining, Forestry, Farming (mature crops), Herbalism, Fishing (AFK-checked), Building (5-min maturity)
- [x] Anti-exploit: placed-block tracker (chunk PDC), generator/piston blocks, AFK via head rotation, hourly soft cap
- [x] Reputation (V3): event log, clamped scores, idempotent adjustments
- [x] Companies (V3): founding fee, company account, roles, deposit/withdraw, staff management, dissolution
- [x] Jobs & employment (V3): catalogue, positions, applications, accept/reject, `/jobs duty`
- [x] Payroll: verified work minutes → idempotent batches, arrears settled oldest-first, withdrawal block, rep penalty
- [x] Contracts (V4): escrow, item delivery → stash, service contracts, cancel/abandon/expiry, XP & reputation rules
- [x] Item stash: `/stash`, `/stash company`, claim limited to free slots; delivery token prevents duplication

### P2 — Player economy
- [x] Cities (V5): admin-created, square area, treasury account, stats (residents, businesses)
- [x] Properties (V5): cuboid regions, 9 types, player/company ownership, market (city treasury / resale),
      expected-price check, trusted players, company staff access, dissolution blocked while owning property
- [x] Protection: in-memory chunk index refreshed by domain events; blocks, interactions, explosions, fire, liquids,
      pistons, hoppers; public city land
- [x] Shops (V6): chest-bound listings in SHOP properties, buy at the chest only, tokened purchases, labels,
      `/shops find`, revenue to owner/company, shop closed on property sale
- [x] Domain event bus (`events.DomainEvents`), published after commit
- [x] Ledger history shows company names as counterparties
- [x] 137 tests

## Next steps (exact)
1. **Settlements (V7)**: player-founded in wilderness; tiers OUTPOST→SETTLEMENT→VILLAGE→TOWN→CITY with configurable
   requirements (members, treasury, age, founder reputation); settlement treasury; member-only building inside radius.
2. **Direct trade**: two-player trade GUI, both confirm, atomic item+money swap, disconnect/close safe.
3. **Buy orders / advertisements** ("Buying 10 000 iron at 18 SEK"): escrowed, fulfilled at a physical location or via stash.
4. **Company inventory / work sites**: employees deposit output to company stash; company property chests.
5. `/profile [player]` — identity: reputation, companies, properties, skills summary.

## Known issues / notes
- Player names must match `[A-Za-z0-9_]{1,16}` (Java Edition). Bedrock/Floodgate prefixes are not supported yet.
- Employee output (mined ore) stays in the employee inventory; company inventory/work sites are P2.
- Jobs without a skill (shop assistant, manager, general worker) are paid for non-AFK minutes on duty.
- Piston direction handling marks both axis neighbours as placed (can deny XP for an adjacent natural block).
- Local test harness (not committed) drives the server console through redirected stdin; the first command sent
  is eaten by a BOM from Windows PowerShell 5.1.
