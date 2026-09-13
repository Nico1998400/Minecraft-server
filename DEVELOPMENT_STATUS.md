# Development Status

_Last updated: 2026-09-13_

## Current phase
**P0 — Foundation** (in progress)

## Environment verified
- Paper 26.2 build 123 (STABLE channel) — requires Java 25
- Java 25 (Temurin, auto-provisioned by Gradle toolchain); Gradle 9.7.1 wrapper
- PostgreSQL 18 via `docker compose` for dev; embedded PostgreSQL 18 for tests

## Done
- [x] Repository initialised, `.gitignore`, `.gitattributes`
- [x] Gradle build (`:swedencore`), version catalog, shadow + run-paper plugins
- [x] CLAUDE.md, GAME_DESIGN.md, ROADMAP.md, docker-compose.yml

## In progress / TODO (P0)
- [ ] Database: Hikari, `Tx` with retries, migration runner with checksums
- [ ] Player registration
- [ ] Economy: accounts, ledger, atomic transfers, idempotency, mint/sink
- [ ] Localization sv_SE / en_US
- [ ] Commands `/balance`, `/pay`, `/language`, `/eco`
- [ ] Tests incl. exploit tests; architecture test
- [ ] README, ARCHITECTURE, DATABASE, DEVELOPMENT docs
- [ ] Build jar, start Paper, verify plugin loads and migrates

## Next steps
Start with the database foundation.

## Known issues
- None yet.
