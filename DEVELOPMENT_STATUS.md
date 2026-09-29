# Development Status

_Last updated: 2026-09-29_

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
- [x] Spawn town **Nordhamn 2.0** (`tools/spawn/`): Viking-age harbour town generated from a voxel model into a data
      pack. Organic slope with contour streets and a grand stair, round harbour basin, two 31-block stone guardians at
      the mouth, king's hall + world tree on the plateau, stave church, stilt quarter, longships, boathouses, beacon,
      palisade and watchtowers. Built in the dev world 2026-09-27 (centre `-572 64 378`, spawn on the ship deck
      `-577 65 369` facing the town); 99.2 % of 9 832 sampled blocks matched. Previews: `--renders all` (ray tracer).
      Old dev world kept as `swedencore/run/world_backup_2026-09-27`.
- Decisions (agreed with the owner):
  - Both GUI and commands: a main menu (`/meny` + a "NORDIA pass" item in the last hotbar slot) calls the same services
    as the commands; commands stay for experienced players.
  - First join: arrival on the ship → welcome title → customs officer NPC (language choice, hands out the pass) →
    "First steps" checklist (find a job → take a contract → sell something → leave town), each step pointing at a
    spawn building. **Rewards are reputation or items, never money** (no new mint source).
  - Spawn buildings map to systems: town hall = properties, job centre = jobs/contracts, bank = loans/shares,
    warehouse = logistics, market stalls = rentable player shops (not an auction house).

## Next steps (exact)
1. **Nordhamn polish**: terrain pass done (2026-09-28): geology toolkit, river rebuilt (no spills by design: falls
   of two or more blocks, raised banks), fields, and the **Nordfjället** massif south of the town (separate region
   `--region massif`, data pack `nordia:massif/build`, same origin `-572 64 378`). Check the built result with
   `--region view` (renders from the world files). Open: denser houses on the upper slopes, labels for key buildings,
   guardian faces, more farmland in the valley. Dev server view-distance raised to 16 so the mountains show.
1b. **Villa district SydHamn** (2026-09-28, experimental second map): `--region villas --origin X,Z` builds a lake-shore
   villa neighbourhood with named streets, a park and ~20 individually designed villas in ten families (torp,
   sekelskiftesvilla, funkis, mansard, suterräng, tvåplanshus, modern, lyx, hörnvilla, 60-tal) on private lots with
   gardens of four characters. Lots are valued by location and written to `build/villas_lots.json` (address, tier,
   area, bounds, waterfront/view/corner/park, suggested price) for the property system. Built in a fresh dev world at
   origin `0 64 0` (spawn `104 66 6`); the Nordhamn world is kept as `swedencore/run/world_nordhamn_2026-09-28`
   (rename it back to `world` to switch).
1c. **Old spawn in a fresh 26.2 world (BUILT 2026-09-29)**: the owner wants the builds of their old 1.18.1 server
   (original at `Desktop/1.18.1 Paper/world_test`, 33 GB; spawn hub around x 1380-1830, z -130..310) in a brand-new
   26.2 world with mountains, plus more buildings in the same style so it feels like one town. Existing builds must stay
   exactly as they are; take the castle walls and the custom trees (spruce-wood trunks, oak crowns) as inspiration.
   - Done: `world_oldspawn_2026-09-28` = old world upgraded by Paper (hub chunks force-loaded, so in 26.2 format).
     `--region transplant --origin -1150,-1050 --src-center 1595,72 --source <oldspawn region dir>` copies the hub
     block for block (99.84 % exact state match verified) into the new world (`world`, fresh seed, mountains west
     of the hub) with a ragged blended edge. World spawn `-1052 126 -1078` (the old Essentials spawn in the castle).
   - Done 2026-09-29: cellars and underground builds copied too (copy starts below the deepest man-made block);
     `--region districts --origin -1150,-1050` built 65 medieval houses on 11 streets around the hub (registry
     `build/districts_lots.json`). **Removed 2026-09-29** at the owner's request: the 65 cottages, lot walls/hedges and
     address labels (`--region districts-clear`). Hub shop/stalls outside the church restored from oldspawn
     (`--region hub-restore`) after the second clear over-dug them. Leftover generated stone walls / house shells at
     the old lots are stripped with `--region leftover-walls` (skips original hub columns). Generated district
     cobblestone/stone roads and retaining walls (the cliff-like murar) are stripped with `--region leftover-streets`.
     Kept the transplanted hub
     (church, trees, bridge, towers, shops, town hall) plus custom spruce-wood/oak trees. District generate no longer
     places those houses or labels. Dev server heap
     raised to 8 GB (2 GB ran out of memory on the big build; 6 GB was tight with view-distance 24). Server list MOTD is
     "NORDIA — Bygg ett liv / Städer • Företag • Jobb • Handel" with the NORDIA logo as `server-icon.png`. `/spawn` and
     `/setspawn` are in SwedenCore; FastAsyncWorldEdit + FastAsyncVoxelSniper 3.2.5 are in `plugins/` (VoxelSniper
     for terraforming; FAWE replaces EngineHub WorldEdit).
     Next ideas:
     Then maybe a town wall with round towers joining the districts. Not copied: block-entity contents (chest items,
     sign text), entities (item frames, armor stands, NPCs).
   - **2026-09-29 evening:** hill-world is `world` again (overworld restored from `world_kullar_2026-09-29` after a
     coastal-blend experiment was reverted). Extra copy still at `world_kullar_2026-09-29`. Spawn `-1052 126 -1078`.
     Terraforming: FastAsyncVoxelSniper 3.2.5 + FastAsyncWorldEdit (replaces EngineHub WorldEdit).
   - **2026-09-29 late:** the owner started a fresh 26.2 `world` (previous ones kept as `world_old_kaos`, `world_new`).
     The oldspawn hub was transplanted to origin `1567,930` in 9 tiles (`nordia_nh0`–`nh8`, `--band 90 --hub
     -300,299,-292,292`, wider blend so the hill slopes into the lowland) and the church copied to `1866,1181`
     (`nordia_kyrka`). Spawn is the castle at `1665 126 902`. Backup: `world_nyspawn_2026-09-29`.
     Hollow hills filled with `--region hill-blend --fill-only` (tiles `hf*`/`hg*`, same box). The first version
     started at the roof / tree-crown height and filled building and tree interiors; fixed by re-running the `nh*`
     tiles and `kyrka` (pocket fill below the copy stays). Fill-only now starts from `Transplant.terrain` (soil
     under roofs and crowns).
2. **Main menu GUI** (`/meny`, NORDIA pass item) → then first-join onboarding (see decisions above) → spawn protection
   for the Nordhamn area in the plugin (currently only vanilla `spawn-protection=16`) and making the market stalls
   rentable server-owned properties.
3. **Playtest pass** with real clients on the dev server; fix GUI/listener issues found (see above).
4. Smoke-test V16 and V17 on the dev Paper server (share bids and company investments: domain tests pass, not yet loaded
   on the real server).
5. GUI menus for common flows (job board, company management) — optional polish.
6. P4 #43 stock market / exchange — only if playtests show peer-to-peer offers and bids are not enough.
7. Then P5 planning (society: crime, police, government) — only after a playtest confirms P0–P2 work in-game.

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
