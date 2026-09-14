# NORDIA

A persistent, player-driven Minecraft society MMO. Players work, specialise, freelance, found companies, hire each
other, trade through physical shops and contracts, own property and build settlements.

> Minecraft is the world. Our systems are the game. The economy is created by the players.

This repository contains **SwedenCore**, the single Paper plugin that implements NORDIA's systems.

| Document | Purpose |
|---|---|
| [CLAUDE.md](CLAUDE.md) | Rules for contributors and AI agents (read first) |
| [GAME_DESIGN.md](GAME_DESIGN.md) | Design pillars and the concrete mechanics we implement |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Code structure, threading model, key decisions |
| [DATABASE.md](DATABASE.md) | Schema, invariants, migration rules |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Local setup, running, testing |
| [ROADMAP.md](ROADMAP.md) | Phases P0–P5 |
| [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md) | What is done, in progress, and next |

## Quick start

Requirements: Git, Docker (for the dev database). Java 25 is downloaded automatically by Gradle.

```bash
docker compose up -d                 # PostgreSQL on localhost:5432
./gradlew test                       # all tests (embedded PostgreSQL, no Docker needed)
./gradlew :swedencore:runServer      # Paper 26.2 test server with the plugin
```

The plugin jar is built to `swedencore/build/libs/SwedenCore-<version>.jar`.

## Player commands (Swedish aliases in parentheses)

| Area | Commands |
|---|---|
| Money | `/balance` (`/saldo`), `/pay` (`/betala`), `/transactions` (`/transaktioner`) |
| Identity | `/profile` (`/profil`), `/language sv\|en` (`/sprak`), `/skills` (`/fardigheter`) |
| Work | `/jobs` (`/jobb`) — board, apply, duty, payslips · `/company` (`/foretag`) — found, staff, positions, finances |
| Deals | `/contracts`, `/contract` (`/kontrakt`) · `/orders`, `/order` (`/bestall`) · `/trade` (`/byt`) |
| Places | `/property` (`/fastighet`), `/city` (`/stad`), `/settlement` (`/by`) |
| Goods | `/shop` (`/butik`), `/shops find` (`/butiker`), `/stash` (`/forrad`) |
| Admin | `/eco`, `/nordia reload`, `/property admin`, `/city admin` |

## Tech

Paper 26.2 · Java 25 · Gradle 9.7.1 · PostgreSQL 18 · HikariCP · JUnit 6
