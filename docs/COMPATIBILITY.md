# Archean Rise — Structure-Mod Compatibility Verdicts

**Method (2026-07-04):** all 30 top non-Create structure mods (plus required libraries, 42 jars
total, resolved from `references/data/*.json`) were installed together with Archean Rise
0.1.0 on a **production Fabric 1.21.1 dedicated server** running the `archean_rise:archean_rise`
world preset (Y −128..+512). The in-mod audit (`/archeanrise-audit structures`) then verified
every one of the **1,073 registered structures** for biome eligibility against our generator,
scanned absolute-Y anchors, and classified dimensions. Full table:
[reports/fleet-audit-2026-07-04.md](reports/fleet-audit-2026-07-04.md). Result: 854 overworld-
compatible, 200 other-dimension (Nether/End — our preset keeps those vanilla, so they generate
normally), 19 ineligible — **every ineligible structure is ineligible by its own mod's design**
(biome-mod-exclusive variants or shipped-disabled), none because of Archean Rise. Placement
mechanics (locate probes) were separately validated on the vanilla baseline; per-mod placement
facts are jar-verified in `references/structure-mods-compatibility.md`.

Create-mod context: **Create itself does not run on Fabric MC 1.21.1** (Create Fabric tops out
at MC 1.20.1; Create on 1.21.1 is NeoForge-only — volatile, verified 2026-07-04, watchlist in
`references/create-structure-mods-compatibility.md`).

## ✅ Compatible — structure mods WITHOUT Create integration (30/30 tested live)

- YUNG's Better Nether Fortresses · YUNG's Better Ocean Monuments · YUNG's Better Dungeons ·
  YUNG's Better Jungle Temples · YUNG's Better Mineshafts · YUNG's Better End Island ·
  YUNG's Better Strongholds · YUNG's Better Witch Huts · YUNG's Better Desert Temples ·
  YUNG's Bridges · YUNG's Extras (+ YUNG's API)
- Dungeons and Taverns — *note: `mining_system` is shipped disabled by the mod itself*
- Towns and Towers — *its standard villages/outposts all eligible; the 17 "exclusives" variants
  activate only when partner biome mods are present (by design). Its `replace:true` village-tag
  override is the known hazard for our FUTURE custom biomes (compat tag pack planned, §1b of
  docs/EXPANSION-DESIGN.md)*
- Repurposed Structures · When Dungeons Arise (*sky structures occupy y≈130–200 — the documented
  contested band; also ships broken advancement JSONs on 1.21.1, mod-side, cosmetic*) ·
  ChoiceTheorem's Overhauled Village · MES — Moog's End Structures · Explorify ·
  Villages & Pillages (*one structure pinned at absolute y=68, fine at sea level 63*) ·
  Structory · Structory: Towers (*wizard_tower needs magical biomes — inactive by design without
  biome mods*) · Philip's Ruins · AdoraBuild: Structures · MVS — Moog's Voyager Structures ·
  Better Archeology (*harmless datafixer warning at boot, mod-side*) · Formations Nether ·
  Formations Overworld (+ Formations lib) · The Lost Castle · Explorations ·
  Additional Structures · Luki's Grand Capitals

## ✅ Compatible — structure mods WITH Create integration

- *(none — no Create-integrated structure mod can currently run on Fabric MC 1.21.1 at all,
  because Create itself cannot; see below. This list is empty by ecosystem reality, not by an
  Archean Rise limitation.)*

## ❌ Incompatible — structure mods WITHOUT Create integration

- *(none of the top 30 — all pass live testing)*
- Environment-incompatible honorable mentions (no Fabric 1.21.1 build exists, could not be
  tested): The Lost Cities · Bygone Nether · The Graveyard

## ❌ Incompatible — structure mods WITH Create integration (15/15, all environment-level)

All incompatible because **Create has no Fabric 1.21.1 build** — nothing Archean Rise can fix;
re-evaluate if the `create-fabric` watchlist trigger fires:

- Create: Let The Adventure Begin — *the one "Fabric 1.21.1" release; **live-tested: causes a
  fatal registry crash at server boot** (ships a `minecraft:create` flat-preset referencing
  Create blocks with no Create dependency). Do not install.*
- Create: Structures · Create: Structures Arise · IDAS (Integrated Dungeons and Structures) ·
  CTOV – Create Structures addon · Create: Molten Vents · Create: Easy Structures ·
  Integrated Stronghold · Create: Dynamic Village · Create: Rustic Structures ·
  Create: New Beginnings · Create: Sky Village · Create: Hangars · Create: Pillagers Arise ·
  Create: Structures Overhaul — *all NeoForge-1.21.1 and/or Fabric-≤1.20.1 only*

## Client modpack coexistence — MandR instance (49 mods, live evidence 2026-07-05)

Archean Rise ran inside a Fabulously-Optimized-style client instance with 49 mods (Sodium,
Iris, Lithium, ModernFix, ImmediatelyFast, EntityCulling, Controlify, Zoomify, ModMenu,
Cloth/YACL, Continuity, ETF/EMF, Dynamic FPS, e4mc, NoChatReports, Polytone, etc. — full list
in the instance). Verdict: **compatible with all 49** —
- two worlds created and played (tier 3 + tier 5); zero Archean Rise errors or warnings in the
  client log; the only log errors belong to other mods (Controlify mixin remap notice,
  SteamDeckUtil probe, a skin-layers class check) and predate/ignore us;
- structural reasons: these are client-side rendering/QoL/performance mods; Archean Rise was a
  data-driven worldgen mod with zero mixins at the time of this evidence (v0.1.x — v0.2.0 added
  the six SiteGrading mixins, whose vanilla-world leak test is clean and whose targets are
  structure placement, not anything these client mods touch). Lithium is the only
  worldgen-adjacent mod and generated both worlds without incident;
- OpenCL GPU backend passed its bit-parity self-test on the client alongside Sodium/Iris
  (separate compute context; no GL interop). (Historical — the backend was removed in the
  v0.3.x cleanup; generation is CPU-only.)

## ✅ Server performance mods — C2ME + Lithium (live evidence 2026-07-05, AR 0.2.3)

**Verdict: TRULY COMPATIBLE (functional certification).** Dedicated server booted with
`c2me-fabric 0.4.0-alpha.0.18+1.21.1` (all modules active, including `rewrites-chunk-system`
and `threading-lighting`) + `lithium 0.15.4+mc1.21.1` + Fabric API, tier_5 preset, seed 424242:

- clean boot in 3.2s; zero error-level lines; the only warnings are C2ME's own optional
  Starlight-integration probes (ClassNotFoundException — expected without Starlight);
- `World terrain tier: 5 (relief 2.59x, landforms 5.18x, biomes 12.0x, Y -272 to 640)` — the
  preset survives C2ME's chunk-system rewrite intact;
- **SiteGrading fired on `c2me-worker-*` threads** — the @WrapOperation + GenerationStub
  consumer decoration survives C2ME rescheduling structure placement onto its own pool (the
  decoration travels with the stub, so thread identity is irrelevant by construction);
- pregen ticket engine completed error-free at **96.3 chunks/s** (289 chunks) — C2ME's
  parallel worldgen COMPOSES with our engine (~2× our CPU-only baseline);
- structure audit: 34 structures — 29 compatible, 5 no-eligible-biome, 0 regressions (matches
  the vanilla-only baseline pattern).

**Methodology caveat:** certification is functional, not bit-compare. The 2026-07-05
order-sensitivity finding (docs/DECISIONS.md — cross-chunk feature spill is order- AND
state-dependent; even two mod-free runs diverge beyond the demand-generation frontier)
invalidates whole-world hash comparison as a coexistence gate for any mod that reorders chunk
scheduling, which is C2ME's entire purpose. Terrain-shape (pre-feature) parity is EXPECTED
(C2ME schedules the same density functions) but NOT bit-verified here: `c2me-opts-math` /
`c2me-opts-natives-math` replace some math implementations and could in principle alter
floating-point results [WEAK EVIDENCE — unmeasured]. Re-review trigger: player reports of
chunk borders/seams on C2ME servers → re-test with those two modules disabled via C2ME config.

## Re-running this verdict

Boot the audit server with the mod set, run `/archeanrise-audit structures fast` (whole-registry
eligibility, seconds) or `... deep <radius>` (adds locate probes; set `max-tick-time=-1`).
Compare against the archived report. Any regression in a previously-compatible structure is an
Archean Rise bug until proven otherwise.
