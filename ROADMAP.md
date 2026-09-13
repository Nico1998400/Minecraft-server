# NORDIA Roadmap

Rule: **do not start P4/P5 while P0/P1 is broken.** Status is tracked in [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md).

## P0 — Foundation
1. Project setup (Git, Gradle 9.7.1 wrapper, Java 25 toolchain, Paper 26.2)
2. PostgreSQL (Docker for dev, embedded for tests), HikariCP
3. SQL migrations with checksum verification
4. Player registration & profile
5. Economy: accounts, money value type, atomic transfers, ledger, idempotency, money supply invariant
6. Localization (sv_SE default, en_US), MiniMessage, injection-safe placeholders
7. Commands: `/balance`, `/pay`, `/language`, admin `/eco`
8. Tests, CLAUDE.md, documentation

## P1 — Core gameplay
15. Skill system (XP curve, persistence, buffered writes, anti-AFK)
16–21. Mining, Farming, Herbalism, Building, Forestry, Fishing XP sources (placed-block tracking)
22. Jobs catalogue
23. Employment: positions, applications, hire/leave/terminate, duty, activity-based payroll, arrears
24. Companies: founding, accounts, roles, management
25. Contracts: escrow, item delivery with stash, service contracts, lifecycle, expiry
26. Reputation (players and companies, event log)

## P2 — Player economy
27. Properties (regions, ownership, purchase, protection)
28. Player/company shops (physical, stock, prices) — **no auction house**
29. Secure direct trading (GUI, atomic, disconnect-safe)
30. Buy orders / advertisements & supplier agreements
31. Company inventory & work sites (route employee output to company)
32. Basic production
33. Cities (treasury, districts, property market)
34. Wilderness support
35. Settlements (founding and progression)

## P3 — Advanced economy
36. Dynamic market statistics  37. Banking & loans  38. Logistics  39. Warehouses  40. Vehicles  41. Industry
42. Company finance & bankruptcy

## P4 — Financial world
43. Stock market  44. Shares  45. Investments  46. Dividends  47. Valuation

## P5 — Society
48. Crime  49. Police  50. Government  51. Elections  52. Courts  53. Dynamic news  54. World events
