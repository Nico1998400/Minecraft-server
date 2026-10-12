# NORDIA resource pack

Custom art that is **optional** for players (never forced). It holds the two quest emblems that float above Brage's head:
`quest_available` (gold "!", a quest to take) and `quest_ready` (green check, a quest is done). Players without the pack see a
plain nether star (quest to take) or emerald (quest done) instead.

- The original art is in `source/`. `java swedencore/resourcepack/tools/MakeEmblems.java` (run from the repository root) turns
  it into the 256x256 textures: it removes the baked-in checkerboard from the gold "!" and crops/centers both.
  To change an emblem, replace the file in `source/` and run it again.
- Build the zip: `./gradlew :swedencore:buildResourcePack` -> `swedencore/build/NORDIA-pack.zip`; the task prints its SHA-1.
- Turn it on: see "Asking players to install it" below.
  SHA-1 under `texture-pack:` in `core/config.yml` (live copy: `swedencore/run/plugins/SwedenCore/config.yml`) and restart.
  Players switch it on and off themselves with `/texturer` or the menu. Change the SHA-1 every time the zip changes.

How it works: the emblem is a floating item carrying a custom model value (`nordia:quest_available` on a nether star,
`nordia:quest_ready` on an emerald). The pack only overrides those two cases, so every other nether star and emerald stays vanilla.

## Asking players to install it

When a player joins they get the normal Minecraft question ("This server recommends a custom resource pack. Download and
install it?"). Yes downloads it automatically. A No is remembered so they are not asked again; they can still use `/texturer`.
It is not forced unless `texture-pack.required: true`.

The pack is **built into the plugin jar** and the plugin hands it out itself (a tiny web server on `texture-pack.host.port`,
default 8765, with its SHA-1 computed automatically). You do not upload anything:

1. Set `texture-pack.host.public-address` in `core/config.yml` (live copy: `swedencore/run/plugins/SwedenCore/config.yml`) to
   the address players use to reach the server: a domain or your public IP, without `http://` or a port.
2. Open **TCP port 8765** in your router/firewall and forward it to this PC. (Only that one file is served.)
3. Restart. For testing on your own PC use `public-address: localhost`.

If you would rather host the zip elsewhere, set `texture-pack.url` and `texture-pack.sha1` instead.
