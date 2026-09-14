# CLAUDE.md — NORDIA / SwedenCore

Instructions for AI agents and humans working in this repository. Read this before changing code.

## 1. Vision

NORDIA is a persistent, player-driven Minecraft society MMO. The central fantasy: **"I am building a life in this world."**
Players work, specialise, freelance, found companies, hire each other, trade, own property, found settlements, and later
invest, govern, police and commit crime. Minecraft is the world; **our systems are the game**.

It is **not** Towny, EarthMC, McMMO, a Jobs plugin, or an auction-house economy. See [GAME_DESIGN.md](GAME_DESIGN.md).

## 2. Stack

| Concern      | Choice                                   | Notes                                                    |
|--------------|------------------------------------------|----------------------------------------------------------|
| Server       | Paper **26.2** (stable channel)          | Java 25 minimum                                          |
| Language     | Java **25** (Gradle toolchain)           | Auto-provisioned by foojay resolver                      |
| Build        | Gradle **9.7.1** wrapper, Kotlin DSL     | Versions in `gradle/libs.versions.toml`                  |
| Database     | PostgreSQL (dev: 18 via Docker)          | HikariCP + plain JDBC, custom SQL migrations             |
| Tests        | JUnit 6, AssertJ, embedded PostgreSQL    | Real PostgreSQL in tests — no H2, no mocks of the DB     |

## 3. Architecture (summary — details in [ARCHITECTURE.md](ARCHITECTURE.md))

One Paper plugin, **SwedenCore** (`:swedencore`), internally modular:

```
se.nordia.swedencore
├── SwedenCorePlugin        Paper entry point (wiring only)
├── core                    NordiaCore composition root, config records, clock
├── database                Database (Hikari), Tx, MigrationRunner
├── player  economy  skills  jobs  companies  contracts  reputation
├── properties  cities  settlements  localization  events  util
└── paper                   ALL Bukkit/Paper/Adventure code: commands, listeners, gui, scheduler, text
```

**Hard layering rule:** only `se.nordia.swedencore.paper..` and `SwedenCorePlugin` may import `org.bukkit`, `io.papermc`
or `net.kyori`. Domain modules are pure Java and fully testable against real PostgreSQL.
`ArchitectureTest` enforces this — do not weaken it.

Services are synchronous and thread-safe; the database is the source of truth. The Paper layer runs service calls
**off the main thread** (`paper.scheduler.Tasks`) and hops back to the main thread for any Bukkit interaction.

## 4. Gameplay philosophy (non-negotiable)

- There is no single correct path. Employee, freelancer, company owner, wilderness hermit, investor — all valid.
- The economy rewards **scale, specialisation and cooperation**. Freelancer = personal productivity;
  company = organisational productivity. Freelancers must remain viable.
- **Players need each other.** Prefer designs that create interdependence over self-sufficiency.
- Skill = capability. Reputation = trust. Wealth = economic success. **Keep them separate systems.**
- Progress comes from meaningful gameplay, never AFK or repetitive loops.

## 5. Economy rules

- **No global auction house. Ever.** No `/ah`. Trade happens through physical shops, contracts, direct trades,
  supplier agreements and advertisements.
- **No artificial money printing.** All server-created money flows from the `SYSTEM:MINT` account and is recorded in the
  ledger; money destroyed goes to `SYSTEM:SINK`. The sum of all account balances is always exactly 0.
  Adding a new mint source requires a documented design reason in GAME_DESIGN.md.
- Money is a `long` of **öre** (1 SEK = 100 öre) wrapped in `Money`. Never `double`/`float` for money.
  All arithmetic uses `Math.*Exact`.
- Company, city, settlement and escrow accounts are separate from personal accounts.
- Contract rewards are **escrowed** at creation; issuers can never "forget" to pay.

## 6. Database rules

- PostgreSQL only. Schema changes only via new files `swedencore/src/main/resources/db/migration/V<n>__<name>.sql`.
  **Never edit a migration that has been committed** — the runner verifies checksums and will refuse to start.
- Every economic mutation runs inside `Database.inTransaction`. Lock rows with `SELECT … FOR UPDATE` in a
  **deterministic order** (ascending id) to avoid deadlocks. Serialization failures/deadlocks are retried by `Tx`.
- Constraints are the last line of defence: `CHECK (balance >= 0 OR allow_negative)`, `CHECK (amount > 0)`,
  unique idempotency keys, partial unique indexes for "only one active X". Keep them.
- Anything that can be triggered twice (rewards, payroll, contract payouts, starter money) needs an idempotency key.
- Do not implement future entities (shares, loans, vehicles…) before their roadmap phase.
- Timestamps that drive game logic (ages, deadlines, statistics windows) must be written from the service's injected
  `Clock`, not the database `now()` — otherwise logic and data disagree (and tests with a controlled clock break).

## 7. Security rules — think like an attacker

- Never trust client-side values: re-validate ownership, amounts, item counts and permissions server-side at commit time.
- Amounts: reject zero, negative, > configured maximum, more than 2 decimals, exponents, NaN.
- Transfers are atomic; duplicate submissions are no-ops via idempotency keys.
- Item flows: remove items first (main thread), then commit; on failure return items. Never give items before commit.
  A crash may lose items, but must never duplicate them.
- Player-controlled text (company names, contract titles) is **always** inserted into MiniMessage as
  `Placeholder.unparsed` — never parsed — to block tag injection.
- Permission checks happen in the domain service (e.g. "is company owner"), not only in the command.

## 8. Localization

- Languages: `sv_SE` (default) and `en_US`. Files: `swedencore/src/main/resources/lang/<locale>.properties` (UTF-8, MiniMessage).
- **Never hardcode player-facing strings.** Use keys. Domain code returns result codes/enums; the Paper layer maps them to keys.
- Every key must exist in every locale with the same placeholders — `LocalizationBundleTest` enforces this.

## 9. Testing

- `./gradlew test` must pass before every commit. Tests use an embedded PostgreSQL (no Docker needed).
- Every economic feature needs exploit tests: negative/zero/overflow amounts, double spending, concurrent transfers,
  duplicate idempotency keys, permission bypass, state-machine bypass (e.g. completing a cancelled contract).
- Test domain services against the real database; do not mock repositories.

## 10. Git rules

- Branch: `main`. Small meaningful commits (e.g. "Economy: atomic transfers with idempotency").
- Never commit secrets, `.env`, server run directories, or database dumps. Dev credentials live in `.env` (gitignored)
  or default to local-only docker values.
- Update DEVELOPMENT_STATUS.md with each feature commit; update this file and ARCHITECTURE.md when architecture changes.

## 11. Prohibited shortcuts

- ❌ Global auction house / `/ah` / disconnected global listings.
- ❌ `double` money, non-atomic balance updates, check-then-act without row locks.
- ❌ Unbounded NPC/server money sinks or sources without ledger entries.
- ❌ Bukkit imports in domain packages; blocking DB calls on the main thread.
- ❌ Hardcoded player-facing strings; parsing player text as MiniMessage.
- ❌ Installing Towny, McMMO, Jobs, Vault-economy stand-ins, or other plugins to replace core systems.
- ❌ Editing committed migrations; disabling tests or constraints to make something pass.
- ❌ Microservices, Redis, message brokers, Kubernetes — not needed.
- ❌ Working on P4/P5 (stock market, crime, government) while P0/P1 is broken.

## 12. Multi-session workflow (mandatory)

This project is developed across many sessions. Never assume one session finishes the work.

**Starting a session:**
1. Read this file and [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md) (it holds the current TODO / next steps).
2. `git status` and `git log --oneline -15`.
3. Verify the build: `./gradlew test`.
4. Continue from "Next steps" in DEVELOPMENT_STATUS.md. Do not redo or discard completed work.

**During / ending a session (and before approaching context or usage limits):**
1. Commit each completed, tested feature immediately.
2. Update DEVELOPMENT_STATUS.md: done, in progress, known issues, exact next steps.
3. Leave the repository clean and buildable. Never leave important decisions only in conversation — write them into docs.
4. If close to limits, prioritise saving progress over starting a large new task.

## 13. Common commands

```bash
docker compose up -d            # dev PostgreSQL on localhost:5432
./gradlew test                  # all tests (embedded PostgreSQL)
./gradlew :swedencore:shadowJar # plugin jar → swedencore/build/libs/SwedenCore-<version>.jar
./gradlew :swedencore:runServer # local Paper 26.2 server with the plugin (run dir: swedencore/run)
```
