# Development Status

_Last updated: 2026-09-27_

## Current phase
**P0, P1, P2 complete (MVP). P3 feature-complete:** market statistics, loans, bankruptcy, company finance, renting,
logistics, warehouses and industry done. **P4 in progress:** shares, dividends, valuation, bids and company investments
done. Remaining: stock market / exchange (#43).

## Environment verified
- Paper 26.2 build 123 (STABLE) — requires Java 25
- Java 25 (Temurin, auto-provisioned by Gradle toolchain); Gradle 9.7.1 wrapper
- PostgreSQL 18 via `docker compose` for dev; embedded PostgreSQL 18 for tests (works on Windows, no Docker)
- Plugin verified on a real Paper 26.2 server after every feature: loads, migrates (V1–V15; V16–V17 tested only in the
  embedded test database so far), commands respond, clean shutdown
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
- [x] Industry (V14): recipes run at FACTORY / INDUSTRIAL_LAND / MINE / FARM facilities with per-type capacity; 16
      recipes forming cross-facility chains; runs snapshot their outputs (recipe edits cannot destroy goods)
- [x] Vehicles (P3 #40): **decided not to build a custom system** — transport cargo is real items, so vanilla chest
      boats, minecart chests and pack animals already are the vehicles. Revisit only if playtests show a need.

### P4 — Financial world
- [x] Shares (V15): 1 000 founder shares, treasury issuance, escrowed peer-to-peer share offers (public/private,
      partial fills, 1 % burned fee), dividends per share, valuation (book value, last/average price, market cap),
      shareholders block owner withdrawals and receive closing equity pro rata; `/shares` (`/aktier`)
- [x] Share market overview `/shares market` (best public ask, last price, 30-day volume/turnover per company — an
      aggregation of player offers, no central order book or market maker); shareholdings in `/profile`
- [x] Share bids (V16): escrowed buy-side bids (`SHARE_BID` escrow accounts), sellers fill with free or treasury shares,
      refunds on cancel/expiry/company close; best bid in `/shares market`; `/shares bid|bids|sell-to|sell-treasury-to|cancel-bid`.
      Asks + bids = peer-to-peer order book (no matching engine, no market maker)
- [x] Company investments (V17, P4 #45): companies hold shares of other companies; dividends and closing equity go to
      the holding company; holdings count as outside shareholders of the target. `/shares buy-company|bid-company|
      sell-company|sell-company-to|holdings`. Extraction guard: if the acting company has outside shareholders, buys
      cannot exceed `max-investment-book-multiple` × book value per share and sales cannot go below book / multiple
      (default 3). Wholly-owned companies are unrestricted. Wage arrears block purchases. On close, listings/bids unwind
      before residual cash is read and remaining holdings are split pro rata. Balance sheet `investments` line uses the
      target's operating book (no nested mark-to-market).
- [x] 203 tests (domain, concurrency, exploits, localization completeness, architecture rules)

### Player experience — spawn and onboarding (in progress, started 2026-09-27)
- [x] Spawn town **Nordhamn** (`tools/spawn/`, see its README): generated vanilla data pack, Nordic harbour town on an
      island; players arrive on a ship's deck. Built and verified in the dev world (centre `-584 64 300`; 3 508 sampled
      blocks matched, 36 entities). Old dev world kept as `swedencore/run/world_backup_2026-09-27`.
- Decisions (agreed with the owner):
  - Both GUI and commands: a main menu (`/meny` + a "NORDIA pass" item in the last hotbar slot) calls the same services
    as the commands; commands stay for experienced players.
  - First join: arrival on the ship → welcome title → customs officer NPC (language choice, hands out the pass) →
    "First steps" checklist (find a job → take a contract → sell something → leave town), each step pointing at a
    spawn building. **Rewards are reputation or items, never money** (no new mint source).
  - Spawn buildings map to systems: town hall = properties, job centre = jobs/contracts, bank = loans/shares,
    warehouse = logistics, market stalls = rentable player shops (not an auction house).

## Next steps (exact)
1. **Main menu GUI** (`/meny`, NORDIA pass item) → then first-join onboarding (see decisions above) → spawn protection
   for the Nordhamn area in the plugin (currently only vanilla `spawn-protection=16`) and making the market stalls
   rentable server-owned properties.
2. **Playtest pass** with real clients on the dev server; fix GUI/listener issues found (see above).
3. Smoke-test V16 and V17 on the dev Paper server (share bids and company investments: domain tests pass, not yet loaded
   on the real server).
4. GUI menus for common flows (job board, company management) — optional polish.
5. P4 #43 stock market / exchange — only if playtests show peer-to-peer offers and bids are not enough.
6. Then P5 planning (society: crime, police, government) — only after a playtest confirms P0–P2 work in-game.

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
- Shares: an owner can still move value to an accomplice via inflated contracts, purchases, loans or salaries; only
  direct withdrawals are blocked. Share purchases have no idempotency token (a repeated command buys again, as asked).
  The investment price guard only covers company-to-share trades against book value, not those other channels.
- Shares: closing-equity payouts use one transfer per holder; a payout above `economy.max-transfer` would make the
  dissolution fail (same limit as before shares).
- Dev harness (not committed) drives the server console via redirected stdin; the first command is eaten by a BOM.
- Windows PowerShell 5.1: commit messages containing double quotes break `git commit -m`; use `git commit -F file`.
