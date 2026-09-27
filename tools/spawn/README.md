# Nordhamn — spawn town generator

`Nordhamn.java` generates the NORDIA spawn town as a vanilla data pack: a Nordic harbour town on an island (~128 × 128).
It is world decoration only — no plugin code, economy or database.

Contents: arrival ship (players spawn on its deck), harbour with lighthouse, breakwaters, piers, crane and boat houses,
welcome gate and flags, customs house, market square with fountain and 16 stalls (future rentable shop plots), town hall
with clock tower, church, bank, job centre, warehouse, windmill, 15 houses, trees, lamps, name labels
(`text_display`), decorative villagers, boats and cats. Invisible `light` blocks prevent monster spawns.

## Generate

```bash
java tools/spawn/Nordhamn.java tools/spawn/build [blocks.txt]
```

- Output: `tools/spawn/build/datapack/nordia_spawn` and review images in `tools/spawn/build/preview/` (top-down and
  isometric views — review these before building in a world).
- `blocks.txt` (optional): one valid block id per line, e.g. extracted from the server jar's
  `data/minecraft/loot_table/blocks/*.json` names. Every block used is validated against it.
- Output is deterministic (fixed seed). Any Java 25 works; the Gradle toolchain JDK is fine.

## Build in a world

1. Copy `build/datapack/nordia_spawn` into `<world>/datapacks/` (a new world picks it up automatically; otherwise `/reload`).
2. Pick open water at sea level (y 62) that covers 128 × 128 blocks — e.g. `/locate biome minecraft:ocean` and check
   the surroundings.
3. `/execute positioned <x> 64 <z> run function nordia:spawn/build` — `<x> <z>` becomes the centre of the market square.
   The build runs in 14 steps over a few seconds and sets the world spawn on the ship's deck.
4. `/gamerule respawn_radius 0` and `/setworldspawn <x-8> 65 <z+36> 180 0` so players arrive facing the town.

The build replaces everything in the 128 × 128 box from y 40 to y 124. Rebuilding in the same place is safe (entities
tagged `nordia_spawn` are replaced).

Dev world (2026-09-27): centre `-584 64 300`, spawn `-592 65 336`.
