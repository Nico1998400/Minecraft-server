# Development

## Prerequisites

- **Git**
- **Docker** — only for the local development database (tests do not need it)
- **Java** — any JDK 17+ to launch Gradle. The Java 25 toolchain required by Paper 26.2 is auto-provisioned by the
  foojay toolchain resolver into `~/.gradle/jdks`.

## Database

```bash
docker compose up -d          # starts postgres:18 as nordia-postgres on 127.0.0.1:5432
docker compose logs -f postgres
docker exec -it nordia-postgres psql -U nordia nordia
```

Default credentials (`nordia` / `nordia_dev_only`) are for local development only. Override them in a gitignored
`.env` file (`NORDIA_DB_PASSWORD=...`) — docker compose and the plugin both read the `NORDIA_DB_*` variables.

Reset the dev database: `docker compose down -v`.

## Building and testing

```bash
./gradlew test                        # unit + database tests (embedded PostgreSQL 18)
./gradlew :swedencore:shadowJar       # plugin jar with relocated HikariCP + pgjdbc
```

Tests use `io.zonky.test:embedded-postgres`: one real PostgreSQL per test JVM, a migrated template database, and a fresh
`CREATE DATABASE … TEMPLATE` copy per test. No mocks of the database.

Test reports: `swedencore/build/reports/tests/test/index.html`.

## Running a server

```bash
./gradlew :swedencore:runServer
```

`run-paper` downloads Paper 26.2, builds the plugin and starts a server in `swedencore/run/` (gitignored) with the
EULA flag set. The plugin creates `plugins/SwedenCore/config.yml` whose defaults match docker compose.

Useful console/admin commands:

| Command | Purpose |
|---|---|
| `/nordia` | Plugin version |
| `/nordia reload` | Reload language files (`plugins/SwedenCore/lang/*.properties` override bundled keys) |
| `/eco give|take <player> <amount>` | Admin mint / burn (ledgered, audit-logged) |
| `/eco balance <player>` | Player balance |
| `/eco supply` | Money in circulation |
| `/eco audit` | Verify ledger consistency and conservation of money |

## Adding a feature (checklist)

1. Design fits GAME_DESIGN.md and CLAUDE.md rules (no AH, no unledgered money, no Bukkit in domain).
2. New tables → new migration `V<n>__<name>.sql` (never edit committed ones). Document in DATABASE.md.
3. Domain service in its module package; mutations inside `Database.inTransaction`; lock rows in id order.
4. Errors as `DomainException("module.code")` + `error.module.code` in **both** language files.
5. Paper adapter in `paper/…`: commands via `Tasks.run`, never DB on the main thread.
6. Tests against the real database, including exploit cases.
7. `./gradlew test`, update DEVELOPMENT_STATUS.md, commit.

## Troubleshooting

- **Server shuts down at startup** — the database is unreachable and `database.shutdown-server-on-failure` is true.
  Start docker compose or fix credentials.
- **"Checksum mismatch for applied migration"** — a committed migration file was edited. Revert it and add a new one.
- **Embedded PostgreSQL fails on Windows** — ensure the temp directory is writable and no antivirus blocks
  `postgres.exe` in `%TEMP%\embedded-pg`.
