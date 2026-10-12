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
EULA flag set, 8 GB heap and IPv4 so port-forwarded clients can connect. FastAsyncWorldEdit and FastAsyncVoxelSniper
(VoxelSniper for 26.2) are downloaded into `plugins/`, and `branding/server-icon.png` is copied as the server-list icon.
and `branding/server-icon.png` is copied as the server-list icon. The plugin creates
`plugins/SwedenCore/config.yml` whose defaults match docker compose. The game port is **25565** (TCP); do not forward
RCON (25575). Friends need Java Edition and a Mojang/Microsoft account (`online-mode=true`).

Useful console/admin commands:

| Command | Purpose |
|---|---|
| `/spawn` | Teleport to the overworld spawn |
| `/setspawn` | Set that spawn to your current position (op) |
| `//wand` | WorldEdit/FAWE selection wand (op; wooden axe) |
| Arrow + `/b ball` `/v grass_block` | VoxelSniper brush (op; `/vs` for help, `/u` undo) |
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

## Changing code without restarting the server (hot reload)

1. Start the server in debug mode: `./gradlew :swedencore:runServer -Pdebug` (opens port 5005; normal startup otherwise).
2. In VS Code run the launch configuration **"Anslut till NORDIA-servern (hot reload)"** (Run and Debug, F5). It attaches to the server.
3. Edit a Java file and save. VS Code recompiles and swaps the changed code into the running server within a second.

Limits (a JVM rule, not ours): you can change what existing methods *do*, but not add or remove methods, fields or classes, and
not change a method signature. Those changes, and new language keys or `paper-plugin.yml`, still need a restart. Edits to
`essentials.yml` need no rebuild at all: change the live copy in `swedencore/run/plugins/SwedenCore/` and run `/nordia reload`.

## Running a command on the running server (and stopping it cleanly)

`powershell -File tools/servercmd.ps1 "<command>"` runs any console command on the dev server that is already running, for
example `save-all flush`, `stop` or `brage rutt info`. It is the clean way to stop a server that was started in the background
(do not kill the process, and do not rebuild the plugin jar while the server runs: the running server then loses classes).

## Which way to apply a change (fastest first)

| What you changed | How to apply it |
|---|---|
| Brage settings (`guide.yml`: walking, emblem, sound), `essentials.yml`, language texts | `/nordia reload` in game, no restart |
| What an existing method does (logic, numbers, messages built in code) | Save the file while attached with VS Code F5 (hot reload, see above) |
| New methods, fields, classes, commands, language keys, `paper-plugin.yml` | `powershell -File tools/restart-server.ps1` (saves, stops cleanly, builds, starts; about 30-40 s, players reconnect) |

A full plugin reload inside the running server is deliberately not offered: Paper cannot unload a plugin cleanly, so it would
leave the old classes, a second Brage and open ports (database, web server, Discord bot) behind.
Run the restart script from a terminal (or redirect its output to a file when a tool waits for it), because the server it
starts keeps the output pipe open.
