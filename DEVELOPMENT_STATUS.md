# Development Status

_Last updated: 2026-09-13_

## Current phase
**P0 — Foundation: complete.** Moving into **P1 — Core gameplay**.

## Environment verified
- Paper 26.2 build 123 (STABLE) — requires Java 25
- Java 25 (Temurin, auto-provisioned by Gradle toolchain); Gradle 9.7.1 wrapper
- PostgreSQL 18 via `docker compose` for dev; embedded PostgreSQL 18 for tests (works on Windows, no Docker)
- Plugin verified on a real Paper 26.2 server: loads, migrates, commands respond (console), clean shutdown

## Done
### P0
- [x] Git, Gradle (`:swedencore`), version catalog, shadow (relocated Hikari/pgjdbc), run-paper
- [x] Database: Hikari pool, `Tx` helper, retry on serialization failure/deadlock, checksum migration runner + advisory lock
- [x] V1 schema: players, accounts, transactions (ledger), SYSTEM MINT/SINK
- [x] Economy: `Money` (öre, exact arithmetic, strict parsing), atomic lock-ordered transfers, idempotency keys,
      mint/burn, history, money supply, ledger audit
- [x] Player registration in AsyncPlayerPreLoginEvent with idempotent starter grant; login refused if DB down
- [x] Localization sv_SE (default) + en_US, MiniMessage with unparsed placeholders, per-player `/language`
- [x] Commands: `/balance [player]`, `/pay`, `/transactions`, `/language`, `/eco give|take|balance|supply|audit`, `/nordia [reload]`
- [x] Tests (70+): money parsing/overflow, concurrent double-spend, deadlock, duplicate keys, key reuse, constraints,
      frozen accounts, registration races, migrations, localization completeness, MiniMessage injection, architecture rules
- [x] Docs: CLAUDE.md, README, ARCHITECTURE, DATABASE, DEVELOPMENT, ROADMAP, GAME_DESIGN

## In progress / TODO (P1)
- [ ] Skills: V2 schema, level curve, SkillService, in-memory XP buffer with periodic flush, `/skills`
- [ ] XP sources: Mining, Farming, Herbalism, Forestry, Fishing, Building (placed-block tracker, AFK detection)
- [ ] Companies: V3 schema, founding fee, company account, deposit/withdraw, roles
- [ ] Jobs & employment: job catalogue, positions, applications, hire/leave/terminate, duty, activity-based payroll, arrears
- [ ] Contracts: escrow, item delivery with stash, service contracts, lifecycle, expiry
- [ ] Reputation: event log, player/company scores

## Next steps (exact)
1. Write `V2__skills.sql` (skills reference table + skill_progress).
2. Implement `skills` domain (Skill enum, LevelCurve, SkillService) + tests.
3. Paper: `SkillTracker` session cache + flush task, block/fish listeners, `PlacedBlockTracker` (chunk PDC), `ActivityTracker`.

## Known issues / notes
- Player names must match `[A-Za-z0-9_]{1,16}` (Java Edition). Bedrock/Floodgate prefixes are not supported yet.
- Dev test harness (not committed): a PowerShell script drives the server console via redirected stdin; `run-paper`'s
  `runServer` is the normal interactive way.
