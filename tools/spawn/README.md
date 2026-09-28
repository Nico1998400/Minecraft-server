# Nordhamn — spawn generator

Generates the NORDIA spawn as vanilla data packs from a voxel model: the Viking-age harbour town **Nordhamn** and the
mountain range **Nordfjället** south of it. World decoration only — no plugin code, economy or database.

## Regions

| Region | Box (relative to origin) | Data pack | Contents |
|---|---|---|---|
| `spawn` | u -128..127, v -128..127 | `nordia:spawn/build` | town, harbour, guardians, king's hall, world tree, river, wild surroundings |
| `massif` | u -192..191, v 128..383 | `nordia:massif/build` | mountain range, cirques, tarn, streams, valley lake, bridge, viewpoint |

Both share the origin **-572 64 378** (dev world). Region edges blend into the natural terrain read from the world.

## Generate

```bash
java -Xmx6g tools/spawn/Nordhamn.java --world <regionDir> --blocks blocks.txt --renders quick          # town
java -Xmx6g tools/spawn/Nordhamn.java --region massif --world <regionDir> --blocks blocks.txt          # mountains
java -Xmx8g tools/spawn/Nordhamn.java --region view --world <regionDir> --renders all                  # "wow test" from the built world
```

- `--world`: the world's `dimensions/minecraft/overworld/region` folder (natural terrain for blending, context for renders).
- `--blocks`: optional list of valid block ids; every block used is validated.
- Output: `tools/spawn/build/datapack/nordia_<region>` and previews in `tools/spawn/build/preview/` (a voxel ray tracer).

## Build in the world

1. Copy the data pack into `<world>/datapacks/`, `/reload`.
2. Force-load the origin chunk (`/forceload add -572 378`), then `/execute positioned -572 64 378 run function nordia:<region>/build`.
3. The build runs in steps of 2 000 commands and releases its force-loads when done; the town sets the world spawn on the
   ship's deck (`/setworldspawn -577 65 369 0 0` for the facing).

## Code map

`Canvas` voxel model + compiler, `B` block helpers and rotatable frames, `Terrain` town landscape, `Wild` terrain pass
around the town, `Geology` erosion/strata/materials, `River` rivers and ponds, `Massif` mountains, `Forest`/`Nature`
vegetation, `Fields` farmland, `Town` layout, `Build`/`Landmark`/`Harbour`/`Monument` structures, `Render` previews,
`WorldReader` Anvil reader.
