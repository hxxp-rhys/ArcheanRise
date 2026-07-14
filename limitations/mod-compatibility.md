# Limitations — Mod compatibility

Verified live 2026-07-04 (see `docs/COMPATIBILITY.md` for the full verdicts and method).

## ⚠ IF YOU RAN 0.3.13–0.3.15 WITH Terralith, Tectonic, Regions Unexplored or CTOV — UPDATE

Those mods all require a library called **Lithostitched**, and on 0.3.13–0.3.15 Lithostitched made Archean
Rise **stop recognising its own world**. Nothing crashed, and the terrain still looked completely correct —
but underneath, **rivers, structure spacing, the ore rebalance, site grading and every structure gate were
switched off**. Measured: roughly 65% of the deep ore simply never generated.

The cause: Archean Rise identified its own generator by looking up its noise-settings registry key. That key
lives in a mutable field, and Lithostitched replaces it with a keyless one whenever any installed mod adds a
surface rule to the overworld. The lookup then failed, and Archean Rise concluded — silently — that the world
was not its own.

**Fixed in 0.3.16.** The identity is now captured when the generator is built, before any mod can touch it,
and Archean Rise now shouts in the log (and warns ops in-game) if it is ever handed a world it does not
recognise, instead of quietly generating a hollow one. Worlds generated on 0.3.13–0.3.15 alongside those mods
keep whatever they already generated; only new chunks are correct.

## Biomes O' Plenty and friends now work (0.3.16)

Biomes O' Plenty, Oh The Biomes We've Gone, Nature's Spirit, YUNG's Cave Biomes and Underground Worlds all add
their biomes through a library called **TerraBlender**, which keeps a list of the worlds it is allowed to add
biomes to. Archean Rise was never on that list, so their biomes had nowhere to go — no crash, they just never
generated. **From 0.3.16 Archean Rise adds itself to that list**, and they generate normally on Archean Rise's
terrain. Only Biomes O' Plenty has actually been tested so far; the others should follow, but are not yet
confirmed. Archean Rise's own terrain is unchanged — the biomes are simply painted onto the land it makes.

- **The Create ecosystem is unavailable, mod-wide, on our platform.** Create has no Fabric
  MC 1.21.1 build (Fabric ends at 1.20.1; 1.21.1 Create is NeoForge-only). Every
  Create-integrated structure mod is therefore unusable alongside Archean Rise regardless of
  anything we do. Watchlist: if `create-fabric` ships a 1.21.1 release, re-run the compat suite.
- **Create: Let The Adventure Begin crashes the server at boot** on Fabric 1.21.1 (its data
  references Create blocks without depending on Create). Not installable — an upstream defect.
- **Overworld-replacer terrain mods (Terralith, Tectonic class) — their TERRAIN never applies; their
  BIOMES may.** These mods rewrite the same vanilla data surface our preset derives from, so their
  *terrain shape* is a competing generator and is never merged: Archean Rise's preset wins, and their
  noise settings are simply not used. Policy: detect + defer, never merge. **This is a statement about
  TERRAIN only** — it does not mean the mod is unusable. Terralith's *biomes* DO appear in Archean Rise
  terrain (live-verified, see the biome-mod verdicts below and docs/COMPATIBILITY.md); Tectonic's do
  not contribute anything and it is simply inert. Do not read this bullet as "Terralith is
  incompatible" — it previously read that way and contradicted the verdict two bullets down.
- **Expanded Ecosphere (`expanded_ecosphere`) — HARD CONFLICT, now enforced:** it replaces the
  overworld of ANY selected world preset at world creation, including the Archean Rise world
  type explicitly set via `level-type` (live-proven 2026-07-05). No combination in which both
  function exists, and compatibility cannot be implemented from our side (its takeover is
  unconditional code; counter-mixins would be an arms race). As of v0.1.5 Archean Rise declares
  `breaks` on it — Fabric Loader refuses to launch with both installed and explains why. Worlds
  already created while EE was installed remain EE worlds.
- **Biome-mod live verdicts (2026-07-05, two-tier validated)** — full table in
  `references/biome-mods-compatibility.md`: ✅ Terralith, Regions Unexplored, Geophilic (biomes
  appear in AR terrain); ✅-by-scope BetterEnd/BetterNether/Incendium/Nullscape; ⚪ Tectonic
  (inert); ❌ the TerraBlender overworld family — Biomes O' Plenty, Oh The Biomes We've Gone,
  Nature's Spirit, YUNG's Cave Biomes (+ Underground Worlds by family rule): no crash, but
  their biomes never appear in AR worlds. Fix path: a TerraBlender bridge (designed follow-up).
- **Verdict carry-over:** the live verdicts above were proven on the pre-0.3.0 tiers under the
  cross-tier policy (proven on one tier, confirmed on another → mod-wide; see docs/TESTING.md).
  The 0.3.0 static world uses the identical generation mechanism (same transform pipeline, one
  parameter set), so the verdicts carry; spot re-checks happen as mods are re-installed.
- **terrain-diffusion-mc** alongside Archean Rise: world-safe, but its unconditional spawn-setup
  mixin misbehaves on non-TD presets and it downloads ~2.5 GB of models at first launch. Not
  recommended together until tested further.
- **Towns & Towers with future Archean Rise custom biomes** will suppress vanilla villages in
  those biomes (it hard-replaces village tags). A conditional compat tag pack ships with the
  custom-biome milestone; until then only vanilla biomes exist and nothing is lost.
- Structures gated on partner-mod biomes (T&T "exclusives", Structory Towers wizard_tower) are
  dormant unless those biome mods are present — by their design, not an Archean Rise fault.
- **Create structure mods (Create: Pillagers Arise, LTAB, …) with SiteGrading — NeoForge only:**
  many of their jigsaw structures project `WORLD_SURFACE` / `MOTION_BLOCKING` rather than vanilla
  villages' `WORLD_SURFACE_WG`, so SiteGrading's `isGradable` gate **excludes them** — AR
  foreign-yields them (prime directive) and they generate with the mod's OWN placement. On rough
  amplified terrain that shows the broken village paths vanilla villages had before the v2 flat-snap
  fix, and that fix (vanilla jigsaw only) does **not** reach them. Only their `WORLD_SURFACE_WG`
  structures are SiteGrading-opt-in-eligible; extending SiteGrading to `WORLD_SURFACE`-projected
  structures is a possible future opt-in (undeliberated). LIVE-verified 2026-07-07 on a user
  NeoForge world — see `references/create-structure-mods-compatibility.md` §B.13.
- **Add-on structures on water — gated by default (`gateForeignInWater`, v0.3.13+).** AR's static terrain
  can push a land biome below sea level and carves rivers, so a foreign (add-on) surface structure placed
  by its own biome/heightmap rules can land floating on / sunk in water; the built-in site veto is
  vanilla-only (`isGradable`), so foreign structures get no water check. The water gate removes a foreign
  surface structure whose footprint sits mostly over water (solid floor > `gateForeignInWaterDepth`, default
  8, below sea level 63), returning the same "no structure here" result vanilla uses on a failed placement —
  determinism-neutral, never modifies the mod's structure. **Accepted residuals:** (1) intentionally-aquatic
  structures (ocean/deep-ocean/river biome) are protected and NOT gated — so a foreign LAND structure that
  its own mod places into a REAL ocean biome via an overly-broad tag (`#minecraft:is_overworld`,
  `#c:is_overworld`) is left in place (indistinguishable from an intended ocean structure; a vanilla-wide
  mod behaviour, not AR's land-below-sea contribution, which IS fixed); (2) an *elevated* river pool (perched
  above sea level, placed features-stage) is not seen by the noise-stage height probe, so a structure over
  one is not gated (the sea case and sub-sea carved river valleys ARE caught); (3) blunt like the snow gate —
  a water-themed add-on set for a non-aquatic biome would be gated; kill-switch `gateForeignInWater=false`
  or raise `gateForeignInWaterDepth`. Re-review trigger: a water-themed add-on set → add a per-namespace
  exempt allowlist (mirror `siteGradingExtraStructures`). See DECISIONS.md 2026-07-12 (water gate).

## Buried add-on structures are left to vanilla — the foreign inset earthwork only touches SURFACE builds (v0.3.14)

**What it is.** `ForeignInsetBeard` / `ForeignInsetGrade` exist to cut a hill off an add-on *building* so it
insets cleanly instead of poking through a slope. Pointed at a structure its author deliberately BURIED (a
cave ruin, a crypt, a bunker), that same earthwork excavates the very rock the structure was meant to be
hidden in. Until v0.3.14 the only guard was an ABSOLUTE test — "box top below sea level (63) ⇒ underground" —
which is meaningless in a world whose land reaches y=768.

**The failure it caused.** `create_ltab:cave_ruins` (*Create: Let The Adventure Begin*) declares
`start_height {absolute: -40}` + `project_start_to_heightmap: WORLD_SURFACE_WG` — "40 blocks below the local
surface". Under a y=231 Archean Rise mountain that places it, correctly and as intended, at y 191..199,
buried in solid rock. But its box top (199) is far above y=63, so AR classified it as a surface building,
carved the mountain out from under y192..206, and left a detached ~12x12x25 **floating island** overhead
whose horizontal cross-section was exactly the structure's bounding box. Reproduced and fixed: with the gate
off, 7 of 16 `cave_ruins` sites grow a floating island (17,167 blocks); with it on, zero.

**How it is decided now.** `BuriedStructures.isBuriedStart` measures how deep a foreign start's *start piece*
sits below the surface **at the exact column vanilla projected it from**, using the structure's own heightmap
type. Buried (>= `insetForeignBurialMargin`, default 8) ⇒ AR withdraws its earthwork and the structure keeps
vanilla's own `terrain_adaptation`. **It never moves or vetoes a structure — placement is byte-identical.**
A surface structure cannot trip it: vanilla's projection arithmetic makes burial a pure function of the
structure's declared `start_height` (0 for every ordinary surface structure), so terrain relief is irrelevant.

**If a mod ever needs an override:** `insetForeignForceSurfaceStructures` (inset it anyway) and
`insetForeignForceBuriedStructures` (never inset it) — `"mod:id"`, `"mod"`, or `"mod:*"`; both resolved and
typo-reported at server start. Kill-switch: `insetForeignBurialGate=false`.

**Accepted residuals (re-review triggers).** (1) The **snow and water veto gates still use the old absolute
sea-level test**, so a buried add-on structure under a snowy or deep-water *surface* can still be falsely
vetoed (deleted) — a latent bug, deliberately not fixed in 0.3.14 because fixing it changes world content for
structures (`create_ltab:grot`, `the_bunker`, `minecraft:trail_ruins`) that never had the floating-island
defect. Trigger: a buried add-on structure reported missing under snow/water. (2) `create_ltab:grot`
(`bury`, -45) cannot sever terrain (`bury` only ADDS density) but can materialise a free-floating stone blob
if it lands in open air — different mechanism, not fixed here. (3) Already-generated chunks are not repaired;
placement is unchanged, so deleting the affected region chunks and regenerating under 0.3.14 relocates
nothing. See DECISIONS.md 2026-07-13 (burial gate).
