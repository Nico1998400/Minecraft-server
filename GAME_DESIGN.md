# NORDIA — Game Design

> "I am building a life in this world."

This document captures the design of NORDIA and, importantly, **the concrete mechanics chosen for implementation and why**.
Numbers marked *(config)* are defaults in `config.yml` and expected to be tuned by playtesting.

---

## 1. Pillars

1. **A life, not a grind.** Players build careers, companies, homes and reputations that persist.
2. **Player-driven economy.** Money circulates between players. The server does not buy everything for unlimited money.
3. **Scale through cooperation.** A freelancer is limited by their own hours; a company multiplies output through employees.
4. **Players need each other.** Specialisation and supply chains create interdependence.
5. **Physical world matters.** Shops, properties and settlements exist at real locations. **No global auction house.**
6. **Stories emerge.** Success, competition, bankruptcy and growth arise from systems, not scripts.

## 2. The economic ladder

| Stage | Role                     | Risk        | Income            | Limiter                    |
|-------|--------------------------|-------------|-------------------|----------------------------|
| 1     | New player               | none        | starter money     | skills, capital            |
| 2     | Employee                 | low         | low/medium, stable| employer, salary           |
| 3     | Skilled professional     | low         | medium            | job availability           |
| 4     | Freelancer               | medium      | medium/high       | **personal time**          |
| 5     | Small business owner     | medium      | higher potential  | capital, staff             |
| 6–7   | Established/large company| high resp.  | high              | management, market         |
| 8–9   | Industrialist / investor | financial   | passive potential | market risk (P4)           |
| 10    | Major influence          | —           | —                 | politics & society (P5)    |

Not every player should reach the top; the journey is the game.

## 3. Money

- Currency: **SEK**, stored as integer **öre** (1 SEK = 100 öre). Displayed as `1 234,50 SEK` (sv) / `1,234.50 SEK` (en).
- **Money supply is explicit.** Every account balance sums to zero with the `SYSTEM:MINT` account, whose negative balance
  equals the total money ever created. Admins and analytics can always answer "how much money exists?".
- **Controlled money sources (mints):**
  - Starter grant for new players *(config: 1 000 SEK)* — once per player, idempotent.
  - Admin grants (logged with actor).
  - *(Future)* limited NPC demand, carefully balanced — see §11.
- **Money sinks:** company registration fee *(config: 5 000 SEK)*, later property taxes and fees → `SYSTEM:SINK` or the
  city treasury. When a city exists, fees go to the city rather than being destroyed.
- Why no "sell to server" in the MVP: it makes the server, not players, the economy's main customer.

## 4. Skills

Own skill system (not McMMO). Initial skills: **Mining, Farming, Herbalism, Building, Forestry, Fishing, Engineering, Logistics**.
Max level *(config: 100)*.

### Level curve
XP to go from level `L` to `L+1` = `round(base × L^exponent)` *(config: base 50, exponent 1.5)*.
Level 10 ≈ 5.6k total XP (~2 hours of mining), level 50 ≈ 340k, level 100 ≈ 2M. Early levels come quickly; mastery
takes months. Diminishing returns: XP above an hourly soft cap per skill *(config: 6 000; herbalism/building 3 000)*
is granted at 25%.

### XP sources (MVP)
| Skill       | Natural gameplay source                                                                 | Anti-exploit                                            |
|-------------|------------------------------------------------------------------------------------------|---------------------------------------------------------|
| Mining      | Breaking stone/ores with the correct tool; value-weighted (diamond ≫ stone)              | Player-placed blocks give 0 XP; creative ignored        |
| Farming     | Harvesting **fully grown** crops; melons/pumpkins grown from stems                        | Placed blocks 0 XP; immature crops 0 XP                 |
| Herbalism   | Wild flowers, mushrooms, berries (harvest), nether wart (mature)                          | Placed blocks 0 XP                                      |
| Forestry    | Chopping natural logs                                                                    | Placed logs 0 XP                                        |
| Fishing     | Catching fish/treasure                                                                   | AFK detection: no XP without recent real activity       |
| Building    | Placing building blocks that **remain for 5 minutes** *(config)*; value-weighted          | Break before maturity cancels; hourly cap; dirt etc. 0  |
| Engineering | Contracts & employment (MVP). Future: redstone/machinery                                  | —                                                       |
| Logistics   | Contracts & employment (MVP). Future: transport distance with cargo                       | —                                                       |

Global rules: no XP while AFK (no real movement/rotation for *(config: 3 min)*), no XP in creative/spectator,
events that were cancelled by protection plugins/systems give nothing.
**Employment bonus:** +10% XP *(config)* for work in the skill of the player's on-duty job — companies are where you learn fastest.

### Skills gate opportunities
Job positions and contracts may require a minimum level. Level 1 → basic jobs; 20 → better jobs; 40 → advanced contracts;
60 → specialised; 80 → high-value professional; 100 → master opportunities.

## 5. Reputation

Separate from skill and wealth. Integer score clamped to **[-100, 100]**, starting at 0, for players **and** companies.
Changes are recorded as events (auditable, idempotent).

| Event                                     | Default delta |
|-------------------------------------------|---------------|
| Contract completed (contractor)           | +2            |
| Contract completed (issuer)               | +1            |
| Contract abandoned (contractor)           | -3            |
| Contract expired while in progress        | -2            |
| Company fails to pay wages (per period)   | -1 company    |

## 6. Employment

- Companies create **positions** (job type, title, required level, hourly salary, number of openings).
- Players **browse → apply → owner/manager accepts or rejects**. Players can **leave**; owners can **terminate**.
- Employment persists across sessions; a player may hold several employments but can be **on duty for only one** at a time.
- **Salary is paid for active work minutes**, not wall-clock time: a minute counts when the on-duty employee performed
  real work in the job's skill (gained XP in it) and was not AFK. Jobs without a skill (e.g. shop assistant) count
  non-AFK minutes. This removes AFK-salary exploits and connects employment to actual output.
- **Payroll** runs every *(config: 5)* minutes: `salary/hour × active minutes / 60`, paid from the **company account**.
  Each payroll period is idempotent. If the company cannot pay, the wage is recorded as **arrears** and retried
  automatically when funds exist; the company loses reputation. This creates real company risk (§8).
- **Work sites:** blocks broken by an on-duty employee inside a MINE, FARM, FACTORY or INDUSTRIAL_LAND property owned
  by their employer drop straight into the **company inventory** (company stash). The company pays wages and owns the
  output. Outside work sites, members can hand items over with `/stash give company`.
- If employment ended before buffered output is stored, the output goes to the worker instead.

## 7. Companies

- Founded by a player: name (3–32 chars, letters incl. åäöÅÄÖ, digits, space, `&-.`), registration fee.
- Own **company account**, separate from the owner's. Owner can deposit/withdraw.
- Roles (MVP): `OWNER`, `MANAGER` (can manage positions and applications), `EMPLOYEE`.
- Max companies owned per player *(config: 3)*.
- Growth is emergent (employees, money, contracts, reputation, assets) — **no arbitrary company levels**.
- Architecture supports future bankruptcy (status `BANKRUPT`), shares and valuation — not implemented yet.

## 8. Company risk

Wages owed are real obligations. A company with high salaries and poor sales runs out of money, accumulates arrears
and loses reputation (fewer applicants). Bankruptcy procedure is P3; the `status` field and arrears ledger exist now.

## 9. Contracts

Contracts connect players and companies. Issuer: player or company.

- **Escrow:** the full reward is moved to a dedicated escrow account at creation. Issuers cannot fail to pay;
  contractors can trust contracts. Cancelling an OPEN contract refunds the escrow.
- **Types (MVP):**
  - `ITEM_DELIVERY` — deliver N of a material. Partial deliveries allowed; each delivery pays proportionally from escrow.
    Delivered items go to the issuer's **stash** (`/stash`), claimable later. Completion when fully delivered.
  - `SERVICE` — construction, transport etc. The **issuer** confirms completion; payout from escrow.
- **Lifecycle:** `OPEN → IN_PROGRESS → COMPLETED`, or `OPEN → CANCELLED`, `IN_PROGRESS → OPEN` (contractor abandons, rep
  penalty), `IN_PROGRESS → EXPIRED` (deadline passed; remaining escrow refunded).
- Optional skill requirement; rewards: money, skill XP *(for the contract skill)*, reputation.
- Contractors cannot accept their own contracts or contracts of companies they own.

## 10. Trade without an auction house

Implemented replacements for a global AH:

| System | How it works | Why it is not an AH |
|---|---|---|
| **Shops** | A SHOP property holds chests; each chest is a listing (item, bundle, price). Customers right-click the chest and buy with a click; revenue goes to the owner or company. Floating labels show the offer. | You must travel to the chest. Location, stock and opening status matter. |
| **`/shops find <item>`** | Lists shops selling an item, cheapest per item first, with coordinates. | Information only — an advertisement. Competition is visible, buying still requires the trip. |
| **Buy orders** | "Buying 10 000 iron ore at 18 SEK": escrowed budget (+1% fee), many sellers fill it, goods go to the buyer's stash. | Demand-side contracts between named parties; no anonymous sell listings. |
| **Contracts** | Exclusive, escrowed jobs (deliveries, services) with reputation and skill XP. | Relationship-based work. |
| **Direct trade** | Two nearby players, server-controlled trade window, both confirm after a cooldown, money and record commit atomically. | Face to face. |

Trade security rules: purchases/fills/deliveries carry a unique token (ambiguous commits are verified before items
are returned), prices shown to the buyer must equal the price at commit (no bait-and-switch), and items are always
removed before money moves and only returned on definite failure — a crash can lose items but never duplicate them.

Future: supplier agreements (recurring contracts), physical marketplaces (city-owned market stalls), price statistics.

## 11. NPC money (future)

If NPC vendors are added they must be: price-capped, volume-limited per period, funded from a city/system budget recorded
in the ledger, and never the best deal for goods players can supply.

## 12. Property, cities, settlements (P2 foundation)

- Properties: typed (apartment, house, shop, office, factory, warehouse, industrial land, farm, mine), bounded region,
  owner (player/company), city/settlement, price, status (available/owned/rented), market value.
- Cities: 1–3 predefined (Stockholm, Göteborg, Helsingborg) with treasury accounts; property sales pay the city.
- Settlements (implemented): founded in the wilderness for a cost (sink). Placement keeps room for the largest tier
  (plus a buffer) from cities and other settlements. Residents join by invite (one settlement per player); land within
  the tier radius is protected for residents. The leader upgrades when all requirements are met; the cost is spent
  from the treasury as public works:

  | Tier | Radius | Residents | Treasury | Age | Leader rep. | Cost |
  |---|---|---|---|---|---|---|
  | Outpost | 32 | 1 | – | – | 0 | 10 000 (founder) |
  | Settlement | 64 | 3 | 25 000 | 3 d | 0 | 10 000 |
  | Village | 128 | 8 | 100 000 | 14 d | 10 | 50 000 |
  | Town | 192 | 15 | 500 000 | 30 d | 25 | 200 000 |
  | City | 256 | 30 | 2 000 000 | 60 d | 50 | 1 000 000 |

  "We built this", never `/town create`. *Future:* building/infrastructure requirements, settlement plots, taxes.
- Wilderness stays valuable: space, resources, freedom.

## 13. Future phases (architecture-aware, not implemented)

- **P3:** dynamic market prices, banking & loans, logistics & vehicles, warehouses, industry, company finance, bankruptcy.
- **P4:** shares, shareholders, valuation from revenue/profit/assets/debt/reputation (never random), dividends.
- **P5:** crime (smuggling, theft, black markets), police, courts, government, elections, taxes, dynamic news from real
  events, player history.

The ledger (every transfer typed and referenced), reputation events and domain events are the data foundation for
valuation, news and player history later.
