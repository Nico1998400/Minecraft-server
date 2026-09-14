# Development Status

_Last updated: 2026-09-14_

## Current phase
**P0, P1, P2 complete (MVP). P3 in progress:** market statistics, loans, bankruptcy, company finance, renting and
logistics and warehouses done.

## Environment verified
- Paper 26.2 build 123 (STABLE) — requires Java 25
- Java 25 (Temurin, auto-provisioned by Gradle toolchain); Gradle 9.7.1 wrapper
- PostgreSQL 18 via `docker compose` for dev; embedded PostgreSQL 18 for tests (works on Windows, no Docker)
- Plugin verified on a real Paper 26.2 server after every feature: loads, migrates (V1–V9), commands respond, clean shutdown
- **Not verified with a real game client** (none available in the dev environment). Gameplay listeners and GUIs
  (XP from blocks, duty payroll, deliveries, shop chests, trade window, work sites, protection) are covered by domain
  tests only. First playtest should focus on: trade window clicks, shop purchase flow, work-site drops, protection.

## Done
### P0 — Foundation
- [x] Git, Gradle (`:swedencore`), version catalog, shadow (relocated Hikari/pgjdbc), run-paper
- [x] Database: Hikari, `Tx`, retry on serialization failure/deadlock, checksum migrations + advisory lock
- [x] Economy: `Money` (öre), atomic lock-ordered transfers, idempotency keys, mint/sink, history, supply, audit
- [x] Player registration (AsyncPlayerPreLogin) with idempotent starter grant
- [x] Localization sv_SE/en_US, MiniMessage with unparsed placeholders, `/language`
- [x] `/balance`, `/pay`, `/transactions`, `/eco give|take|balance|supply|audit`, `/nordia [reload]`

### P1 — Core gameplay
- [x] Skills (V2): level curve, buffered XP, anti-exploit XP sources, `/skills`
- [x] Reputation, companies, jobs, employment, activity-based payroll with arrears (V3)
- [x] Contracts with escrow, item stash (V4)

### P2 — Player economy
- [x] Cities & properties (V5), protection index + listener, `/property`, `/city`
- [x] Physical shops (V6), `/shop`, `/shops find`
- [x] Settlements (V7): outpost → city tiers, treasury, members, land protection, `/settlement`
- [x] Direct trading (V8): server-controlled trade window, atomic money swap, `/trade`
- [x] Buy orders (V9): escrowed multi-seller demand, `/orders`, `/order`
- [x] Company inventory (`/stash give company`) and work sites (employee drops → company stash)
- [x] `/profile [player]` — identity and story
- [x] Basic production (V10): recipes in `production.yml`, FACTORY capacity, engineer operators, Engineering XP,
      pristine stash consumption via `ItemCodec`, `/production`
- [x] Domain event bus; ledger shows player and company counterparties

### P3 — Advanced economy
- [x] Market statistics: `/market <item>`, `/market top` (7-day volume-weighted prices, trend, best offers)
- [x] Loans (V11): player/company lending, automatic collection, late penalties, default; `/loan`
- [x] Company bankruptcy: orderly wind-down (escrows, property seizure, wages first, pro-rata creditors); `/company bankrupt`
- [x] Company finance report: `/company finance` (income statement, balance sheet, equity)
- [x] Property renting (V12): listings, tenancy with exclusive use, rent collection, eviction; occupant concept used by
      protection, shops, production and work sites; `/property rentals|rent-out|rent|move-out|end-lease`
- [x] Logistics (V13): transport jobs with physical pickup/delivery, collateral, Logistics XP; `/transports`, `/transport`
- [x] Warehouses: stash capacity in stacks (config `storage`), +1 080 per occupied WAREHOUSE; enforced on voluntary
      inflows under a per-stash advisory lock; `/stash` shows used/capacity
- [x] 179 tests (domain, concurrency, exploits, localization completeness, architecture rules)

## Next steps (exact)
1. **Playtest pass** with real clients on the dev server; fix GUI/listener issues found (see above).
2. **Vehicles (P3 #40)** — optional; could reuse minecart/boat entities with cargo capacity for transports.
3. **P4 — company valuation & shares** (only once P3 is stable): valuation from `CompanyFinanceService` (equity,
   30-day operating result, reputation), share registry, dividends.
4. GUI menus for common flows (job board, company management) — optional polish.

## Known issues / notes
- Player names must match `[A-Za-z0-9_]{1,16}` (Java Edition). Bedrock/Floodgate prefixes are not supported yet.
- Jobs without a skill (shop assistant, manager, general worker) are paid for non-AFK minutes on duty.
- Piston direction handling marks both axis neighbours as placed (can deny XP for an adjacent natural block).
- Trade window money buttons adjust in 100 / 1 000 / 10 000 SEK steps (chat cannot be opened in a container GUI).
- Buy orders can be filled from anywhere (goods delivered to the issuer's stash). Logistics (P3) may later require
  physical transport.
- Bankruptcy cancels OPEN transport jobs (reward escrow goes to creditors; reserved cargo is forfeited with the company
  stash, which nobody can claim once all memberships end). IN_TRANSIT jobs stay: carriers can still deliver and be
  paid; if they fail, reward and collateral refund into the closed company account (effectively a sink).
- Stash capacity is not enforced on transport delivery, shop/trade fallbacks or refunds (by design: never lose items).
  A stash can therefore exceed capacity; it then only blocks new voluntary inflows until items are claimed.
- Work-site output that overflows to the worker is not announced in chat yet.
- Dev harness (not committed) drives the server console via redirected stdin; the first command is eaten by a BOM.
- Windows PowerShell 5.1: commit messages containing double quotes break `git commit -m`; use `git commit -F file`.
