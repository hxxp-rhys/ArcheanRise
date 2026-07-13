# Archean Rise — Installation & Deployment

Operator-facing instructions. **Documentation contract:** any change to config keys, the world
preset, deployment steps, supported versions, or limitations MUST update this file in
the same commit (enforced by convention; reviewers treat a mismatch as a bug).

Applies to: Archean Rise for Minecraft **1.21.1**. Every release ships **two jars** —
`archean-rise-fabric-<version>.jar` (Fabric Loader **0.19.3+**, Fabric API required) and
`archean-rise-neoforge-<version>.jar` (NeoForge for 1.21.1). Install the one matching your loader.

## 1. Install

- **Client / singleplayer:** drop the jar for your loader (plus Fabric API, on Fabric) into the
  instance's `mods/` folder (Prism/MultiMC/vanilla launcher).
- **Dedicated server:** drop the same jar (plus Fabric API, on Fabric) into the server's
  `mods/` folder. Clients do NOT need the mod installed to join (worldgen is fully server-side).

## 2. Select the Archean Rise world type — AT WORLD CREATION (the critical step)

World geometry is **baked into a world when it is created**. It can never be changed afterwards
(Minecraft engine constraint — see `limitations/world-height.md`).

**Since v0.3.0 there is exactly one Archean Rise world** (the tier system was removed):

| Property | Value |
|---|---|
| World range | Y **−256 .. 768** (height 1024) |
| Mountain ceiling | **708** — terrain physically cannot exceed it (raised from 704 in the 2026-07-10 mountains rework) |
| Deepest seafloor | **−128** nominal (191 blocks of water below sea level 63) |
| Relief | 3.32× vanilla |
| Landform wavelength | 6.64× vanilla |
| Biome size | 12× (Distant Horizons strongly advised) |
| Structure spacing | 3.0× (random-spread sets; strongholds/mineshafts untouched) |

Scaling model (real-life-referenced, `references/real-scale-design.md`): relief, landform
wavelength, and biome size scale on separate curves so terrain keeps normal local detail while
gaining large-scale structure. Local detail (caves, overhangs, sea level) stays vanilla-scale.

**Terrain system (v0.3.x, Phase 2):** geography is built from an iterated domain warp shared by
every terrain/climate field (coastlines and biome borders swirl together, never separately),
Voronoi tectonic plates feeding the continentalness channel (distinct highland/lowland regions;
mountain CHAINS follow plate borders), a true ridged multifractal for crest networks in peak
zones, and a re-authored land height distribution (flat lowlands, rare-but-tall highlands —
the tallest summits are designed to reach the 655..708 slide band with convex (non-plateau) tops since the 2026-07-10 mountains rework).
Biome placement inherits all of it automatically (same fields, same warp), and ocean depth /
shorelines are byte-frozen from the v0.3.0 derivation.

Two consequences worth knowing before you dig: caves stay at their vanilla absolute depths, so
most of the volume below about Y −48 is solid rock (the pivot plan's cave phase will address
this); and because vanilla anchors some ore bands to the *world floor*, **diamond peaks at
Y −256** (a few floor-anchored ores spread thinner per layer — full table in
`limitations/world-height.md`).

- **Singleplayer:** Create New World → **World** tab → **World Type** → cycle to
  *Archean Rise*.
- **Dedicated server:** in `server.properties`, **before the very first start**:
  `level-type=archean_rise:archean_rise`.
  If the server already generated a world, changing `level-type` does nothing — delete the
  world folder or set a new `level-name` to generate a fresh world.
- **Pre-0.3.0 worlds (tier_1..tier_5 and the legacy v0.1 preset) do NOT load under 0.3.0+** —
  their registry entries are no longer shipped, so they fail loudly at startup. Keep such
  worlds on mod version **0.2.26** (see `limitations/world-height.md`). Do not update a live
  pre-0.3.0 server to 0.3.0 unless you are resetting its world.

## 3. The config file — what it does and does NOT do

`config/archean_rise.json` (created on first launch, `//` comments allowed):

- **Self-upgrading:** the file is regenerated at every launch — your values are preserved,
  comments are refreshed, and keys added by mod updates appear automatically. Custom comments
  you add will not survive. If a key seems missing, launch once with the current jar.
- **Removed in 0.3.0: `terrainTier`.** With a single world geometry there is no tier to intend;
  a leftover `terrainTier` entry in an old config file is ignored and disappears on the next
  regeneration. World selection happens only via World Type / `level-type` at creation (§2).
- **SiteGrading** (v0.2.0+, on by default): villages and pillager outposts in Archean Rise
  worlds get terrain-aware placement — a fit check over the footprint, a deterministic search
  for a better spot within 32 blocks, and natural terrain re-grading (density-level pad +
  foundation fill) so pieces slot into the land instead of floating over dips. Keys:
  `siteGradingEnabled` (kill-switch), `siteGradingCandidateSearch`, `siteGradingGradePad`,
  `siteGradingFoundationFill`, and the placement **veto** (`siteGradingVeto`, **default ON**;
  `siteGradingVetoMaxRelief` 8–128, `siteGradingWaterVetoDepth` 0–64) which *rejects* village sites
  too steep, too locally-cliffed, or too far over water to make a walkable village — leaving them
  ABSENT rather than placing them on broken ground (villages spawn with the restored vanilla
  per-piece projection where the terrain allows it, and are skipped where even vanilla could not
  build a walkable village; SiteGrading v2's CUT+FILL earthworks are still in progress — see
  `references/sitegrading-v2-blueprint.md` and `limitations/structure-grading.md`).
  `siteGradingVetoMaxRelief` is the max vertical relief a village FOOTPRINT may span and still be
  walkable (Range 8–128; raise it to trade accessibility for more villages on steep terrain —
  the 3.32× relief amplification makes walkable sites rarer). `siteGradingWaterVetoDepth` (default
  32) is a support/floating-house guard over the footprint core — 32 keeps ordinary coastal
  villages, lower it for stricter over-water rejection. Also the experimental v2 CUT+FILL pass
  (`siteGradingCutFill`, **default off**; `siteGradingApronRampMax` 8–128; `siteGradingCut` — grade
  sub-switch, default on; `siteGradingForeignHaloExtra` 0–4 — extra off-limits margin, added to the
  12-block beard reach, around every OTHER structure so grading never disturbs a neighbour).
  **Flat-snap + full grade (built, 3b/3c/4):** when on, every house AND street projects to one flat
  plane (coplanar → no path-junction steps), and the terrain is graded TO that platform — the hill cut
  down, dips/water filled up, the apron resurfaced — so the village is flat and walkable. Yields
  entirely to any other structure (foreign-yield). Still **EXPERIMENTAL / default-off** — it only
  affects villages generated with it enabled (existing chunks are unchanged), uses aggressive earthwork
  (visible over-water fill, a possible apron-fringe seam at chunk edges), and only vanilla-type
  `WORLD_SURFACE_WG` jigsaw villages are graded (modded structures with other projections are yielded,
  not fixed — see `limitations/mod-compatibility.md`). Also `siteGradingExtraStructures` (opt-in more
  structures — each
  entry is one structure id like `"mymod:fort"` OR an entire mod as `"mymod"` / `"mymod:*"`;
  only eligible structures are affected: exact vanilla-type jigsaw + surface projection +
  terrain adaptation, so a whole-mod entry safely skips anything else that mod registers).
  At server start the log reports what each entry resolved to, warns about ids/namespaces
  that matched nothing (typo or mod not installed), and lists the resulting extra gradable
  structures. Other structure mods are deliberately untouched unless opted in here (they
  keep their own placement systems).
- **Structure spacing scaling** (v0.2.18+, on by default): `scaleStructureSpacing` multiplies every
  `random_spread` structure set's spacing/separation by **Ss = 3.0×** in the Archean Rise world, so
  ALL structures — vanilla AND add-on mods — spread apart to match the enlarged biomes.
  Per-generator, so the Nether/End keep vanilla spacing and `/locate` / explorer maps / villager
  trades stay consistent; strongholds (concentric rings) and frequency-driven sets (mineshafts,
  buried treasure) are never scaled. Set false for vanilla spacing. Applies at world/generator
  load; already-placed structures are not moved.
- **Add-on structure insetting** (v0.2.19+, on by default): `insetForeignStructures` cuts a pocket for
  add-on (non-village) surface structures that use thin terrain adaptation, so on steep terrain they
  inset into the hill/mountain with a clean interior (no terrain inside the buildings) and a ≥2-block
  skirt instead of hanging off the slope or poking through. Self-limiting — no effect where terrain
  doesn't intrude (flat ground); the vanilla beard still supplies support underneath. Structures that
  bury/encapsulate themselves, and villages, are left alone. Only jigsaw-based structures are affected
  (coded non-jigsaw types are unchanged). Set false to leave add-on structures fully vanilla.
- **Add-on structure exterior grade** (v0.2.24+, on by default): `insetForeignGrade` shapes the natural
  terrain AROUND an inset add-on structure so it does not read as a machined box — a sloped cut face
  (3/2..4/1) instead of a vertical wall, a steep declining fill under a genuine overhang, and a flared
  tunnel mouth where a piece bored through a hill. It only touches natural terrain, never the structure's
  own blocks. Tune with `insetForeignGradeReach` (4..48), `insetForeignOverhangMin` (1..32), and
  `insetForeignTunnelBase` (0..6). Set `insetForeignGrade` false to keep the plain inset.
- **Burial gate — keep the two inset passes off BURIED add-on structures** (v0.3.14+, on by default):
  `insetForeignBurialGate` stops the earthwork above from excavating structures their author deliberately
  sank (cave ruins, crypts, bunkers). Both inset passes exist to cut a hill off a *building*; pointed at a
  ruin that is meant to be hidden underground, they dig out the very rock it was buried in. Before 0.3.14
  the only guard was an ABSOLUTE test ("box top below sea level 63"), which is meaningless in a world whose
  land reaches y=768 — a cave ruin sunk 40 blocks under a y=231 mountain sailed straight through it, got the
  mountain carved off it, and left a **detached floating island** overhead (`create_ltab:cave_ruins`).
  The gate replaces that with a SURFACE-RELATIVE test: a structure counts as buried when its start piece's
  roof sits at least `insetForeignBurialMargin` blocks (default 8, clamp 1..64) below the surface **at the
  exact column vanilla projected it from**. A buried structure is handed back to vanilla's own
  `terrain_adaptation` untouched — it is never moved, never vetoed, and **its placement never changes**.
  Surface structures cannot trip this: their burial is a pure function of their declared `start_height`
  (0 for every ordinary surface structure), so no amount of terrain relief can push one across the
  threshold. Overrides, if a future mod ever needs one: `insetForeignForceSurfaceStructures` (inset it
  anyway) and `insetForeignForceBuriedStructures` (never inset it) — both take `"mod:id"`, `"mod"`, or
  `"mod:*"` entries and are reported in the log at server start. Set `insetForeignBurialGate` false to
  restore pre-0.3.14 behaviour exactly.
- **Snowy-biome gate for add-on structures** (v0.2.25+, on by default): `gateForeignInSnow` stops an
  eligible add-on SURFACE structure (the same set the inset earthwork treats) from spawning where the
  terrain is snow-covered — a generic add-on build placed on snowed ground reads wrong, and snow-covering
  its own blocks would write on another mod's structure. It is blunt: it removes ALL such structures where
  snow falls (it cannot tell a snow-themed build from a generic one), so set `gateForeignInSnow` false if
  an add-on ships snow-appropriate structures. Vanilla villages/outposts and non-surface (buried) add-on
  structures are never gated.
  **The gate is altitude-aware, not just biome-aware.** It asks Minecraft whether snow falls at the
  structure's position, and vanilla lowers biome temperature by 0.00125 per block above Y 80. So a peak can
  be "snow-covered" in an otherwise temperate biome. Snowline by biome: taiga Y 160, old-growth pine taiga
  Y 200, forest/dark forest Y 520, plains/swamp Y 600 (±8 blocks of noise). With the static world's
  **708 mountain ceiling, every one of those snowlines is reachable** — a high enough summit snows in ANY
  biome, and add-on structures up there are gated even where the biome is temperate. That is usually what
  you want — the summit really is snowy — but it surprises people. See
  `docs/research/07-temperature-and-seasons-compat.md` §4.3.
- **Water gate for add-on structures** (v0.3.13+, on by default): `gateForeignInWater` stops an add-on
  SURFACE structure from spawning floating on / sunk in open water, so add-on structures only spawn on
  land. Archean Rise's static terrain can push a land biome below sea level and carves rivers, so a
  structure placed by its own biome/heightmap rules can land on water; the built-in site veto only
  protects vanilla villages/outposts, never add-on structures. `gateForeignInWaterDepth` (0–64, default
  8) is how deep the solid ground must sit below sea level (Y 63) before a spot counts as water — 8 keeps
  beach/swamp/shallow-shore builds and removes only genuine over-water placements. **Intentionally-aquatic
  add-on structures are safe:** anything in an ocean, deep-ocean, or river biome (ocean villages, drifting
  ships, ocean monuments/ruins) is never gated, nor are underground/underwater structures or vanilla
  villages/outposts. (It also keeps vanilla NON-village surface structures — desert pyramids, jungle
  temples, mansions, land ruined portals — off deep water, for the same reason; only villages/outposts are
  exempt, since they get their own site check.) Like the snow gate it is blunt (it cannot tell a dock/stilt build from a generic one),
  so set `gateForeignInWater` false, or raise `gateForeignInWaterDepth`, if an add-on ships water-themed
  structures for a non-aquatic biome. Note: it does not catch a structure over an *elevated* river pool
  (perched above sea level), and it leaves a structure that its own mod places directly in a real ocean
  biome (an overly-broad biome tag) — it fixes the land-biome-pushed-underwater case AR itself creates.
- **Biome-border blend** (v0.2.21+, off by default): `biomeBorderBlend` (0–24) softens the abrupt cutoff
  at biome borders by domain-warping the temperature/humidity biome sampling, so borders finger/interlock
  instead of forming straight lines (0 = normal hard borders … 24 = max). Terrain is byte-identical and
  injected biome mods inherit it. It softens border SHAPE only — foliage/features/mobs and grass/water
  COLOUR still change sharply at the line (raise the client Options → Video → **Biome Blend** setting for
  colour). Applies to newly generated chunks; changing it on a live world cosmetically seams the biome
  layout at the generation frontier (no terrain change). Needs in-game tuning — leave 0 unless you want
  wavier borders. See `limitations/biome-borders.md`.
- **Float despeckle** (v0.2.23+, **off by default**): `floatDespeckleEnabled` removes small fully-detached
  terrain clumps (residual floating-block artifacts on amplified mountains/caves) after terrain generates,
  while KEEPING any large formation — so a future intentional floating sky island is never removed.
  `floatDespeckleMaxBlocks` (default 16) is the biggest clump treated as an artifact (larger is kept);
  `floatDespeckleMinY` (default 63) is the floor below which nothing is touched. Seed-deterministic; only
  affects newly generated chunks. Enable and check your world before relying on it.
- **River waterfalls & rapids** (v0.3.x R2, **on by default**): `riverFallsEnabled` turns the river
  graph's flagged lips (spec §7.2) into NATURAL drops over the carved step cliff as chunks generate —
  at each waterfall (drop ≥ 4) a retained lip source seated on the actual terrain crest (never floating),
  a non-spreading falling curtain down the cliff face, and a rim-contained plunge pool of still water at
  the landing (a filled reach basin is used as-is; a self-contained bowl is dug only on dry ground).
  Rapids (drop 2–3) get a light short falling section over a wetted step. Quiescent by construction
  (still source pools + falling water that does not spread horizontally, walled by the carve's side dams);
  the placer never overwrites the aquifer's river water, so pooled reaches are byte-unchanged. Seed-
  deterministic, Archean-generators-only, newly generated chunks only. Set `false` to fall back to the
  plain stepped-pool river descent (no built falls). Live in-game fluid-tick validation is the R2d gate —
  until then treat the falls as preview-quality.
- Runtime keys (safe to change anytime): pregeneration tuning (`pregenMaxInFlight` — the async
  ticket engine's window; `pregenPauseWhenPlayersOnline` — dedicated servers only, singleplayer
  never pauses; `pregenLogIntervalSeconds`, `pregenMaxRadiusChunks`).
  (The experimental `gpuBackend`/`gpuDevice` keys were removed in v0.3.x along with the OpenCL
  backend — generation is CPU-only, which was always the canonical path; stale keys in an
  existing config file are ignored harmlessly and disappear on the next rewrite.)
- Pregeneration: `/archeanrise pregen start <radiusChunks> [centerX centerZ]` — async ticket
  engine (measured ~54 chunks/s on a 16-core machine at 1008-block columns, essentially the
  static world's volume; ~2.5× the old engine), with an
  MSPT governor that automatically backs off while players are experiencing load. Generates
  **center-outward** — starting at spawn (or the given center) and expanding ring by ring to the
  radius. `pregenSaveIntervalChunks` (default 1024, 0 = off) drains generated chunks to disk
  every N chunks so the unsaved set never piles up into a long save on quit — see §6.
- **Auto-pregen dev tool:** `autoPregenEnabled` + `autoPregenRadiusBlocks` — generates that
  radius around spawn at startup, once per world (marker file; re-runs only if raised). An
  interrupted run **resumes** from a checkpoint on the next start instead of restarting (the
  marker records how far it got); it only runs to completion once. A hard crash may leave a
  thin band of the most-recently-generated chunks to generate on demand instead — never a
  corruption or data loss.
- **Player-ahead generation:** `playerAheadEnabled` + `playerAheadChunks` — keeps terrain
  generated view-distance+N chunks around every player (adaptive: falls back to +3 chunks /
  48 blocks under server load), so chunks always exist before they become visible.
- **Water depth:** oceans/large water bodies deepen beyond the relief curve (coast-anchored, so
  shorelines are unchanged): the deepest ocean floors sit at a **nominal Y −128** — 191 blocks
  below sea level (vanilla: ~28). The shallow shelf (the "ocean" biome band) runs ~50 blocks
  deep from the relief scaling alone. Bring boats; deep-ocean floor exploration is a serious
  expedition.

## 4. Verify the deployment (startup log)

After boot, the log tells you exactly what you got:

- `Archean Rise static world active (Y -256 to 768, mountain cap 708, seafloor -128…)` — the
  world type is active.
- `This overworld matches a pre-0.3.0 TIERED / the legacy v0.1 Archean Rise geometry…` — an
  old Archean Rise datapack is pinned ahead of the jar; unsupported — keep that world on 0.2.26.
- `Overworld is not an Archean Rise preset…` — the preset was NOT selected at creation;
  §2 was skipped.

Useful commands (permission level 2): `/archeanrise info` (world range + preset),
`/archeanrise pregen start <radius>` (pregeneration),
`/archeanrise-audit structures fast` (structure-mod compatibility audit),
`/archeanrise-audit spawnscan [radius mountainHeight]` (is THIS world's spawn near
mountains?), `/archeanrise-audit seedhunt <count> [startSeed [radius mountainHeight]]`
(scout many candidate seeds without generating chunks — finds seeds whose spawn is near,
but not on, a mountain range; one seed per tick, hits log live, ranked report in
`archean-rise-reports/`; `seedhunt cancel` stops early and keeps the partial report).

## 4b. Updating the mod on an EXISTING world — expect seams at the frontier

Terrain and biome layout are baked into chunks as they generate. A mod update that changes
worldgen (any release that says so in its notes — e.g. the 2026-07-10 terrain/biome fixes)
affects ONLY chunks generated after the update: already-explored areas keep their old terrain,
biomes, and structures, and the boundary between old and new chunks can be a hard terrain
CLIFF and/or an abrupt biome ring. This is inherent to Minecraft chunk baking, not a bug.
To fully receive worldgen fixes, start a NEW world (recommended) or accept the frontier seam.
(The biomeBorderBlend note in §3 about "no terrain cliff" applies to changing that one knob
only — NOT to mod updates that change the terrain pipeline.)

## 5. Mod compatibility quick facts

- 30 most-popular structure mods: live-tested compatible — details `docs/COMPATIBILITY.md`.
- Create-integrated mods: cannot work on Fabric 1.21.1 (Create itself is NeoForge-only there);
  **Create: Let The Adventure Begin crashes servers at boot — do not install.**
- Overworld-replacing terrain mods (Terralith/Tectonic/**Expanded Ecosphere**): pick them OR the
  Archean Rise world type for a given world, not both — EE in particular hijacks the overworld
  even when an Archean Rise world type is explicitly selected (live-proven; the mod warns in red
  chat when it happens). Regions Unexplored coexists fine. `limitations/mod-compatibility.md`.

## 6. Save performance (long "Saving world" stalls)

Tall Archean Rise chunks (1024-block columns) are ~2.67× the vanilla column volume, so on a fast pregen (or heavy
exploration) the game generates chunks faster than it writes them; the unsaved set piles up in
memory and the next full save — an autosave, or the save on quit — must serialize, compress and
write the whole backlog at once. That is the long "Saving world" screen. Note: with
Distant Horizons installed, its own LOD generation and per-dimension SQLite databases are a
*separate* write path that adds to this. Levers, integrity-first (region writes are header-last,
so none of these can corrupt a region file — the worst case of the durability ones is losing the
most-recent unsynced writes on a power loss):

- **`pregenSaveIntervalChunks`** (this mod, default on): drains during pregen so the quit save
  stays small. The built-in fix for the pregen case; no downside but a little extra overhead.
- **`sync-chunk-writes` / `syncChunkWrites`** — the biggest write-side dial. Dedicated server:
  `sync-chunk-writes=false` in `server.properties`. **Singleplayer** (the integrated server
  overrides this): set `syncChunkWrites:false` in `.minecraft/options.txt` — on Windows the
  default is `true`, so this is often the single most effective change. Removes per-write fsync;
  keep periodic backups.
- **C2ME** (certified companion): its `chunkio` modules parallelize compression + encoding.
  Recommended anyway; see §5 / `docs/COMPATIBILITY.md`.
- **LZ4 region compression** (vanilla 1.21.1, codec type 4 — lossless, still readable by vanilla
  and external tools): ~16× cheaper to compress than the default for ~1.7× larger files. Set
  **`regionFileCompression: "lz4"`** in the config — this works in **singleplayer** (applied at
  startup to all dimensions via `RegionFileVersion.configure`; new chunks write LZ4, old chunks
  still read). Reversible any time. On a dedicated server, `region-file-compression=lz4` in
  `server.properties` is the native equivalent and takes precedence.
- **Distant Horizons on its own disk/queue**: give DH's SQLite a separate volume so it doesn't
  contend with `.mca` writes.
- **Do NOT** add Moonrise alongside C2ME — they both rewrite the chunk system and are mutually
  exclusive (and Moonrise breaks Distant Horizons on Fabric).
