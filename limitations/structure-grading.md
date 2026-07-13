# Limitations — SiteGrading v2 (village terrain integration)

SiteGrading v2 makes villages/structures stop spawning on broken amplified terrain, and — behind a
default-off switch — grades the terrain under a village flat so its paths stay walkable. It is
**staged**:

- The **accessibility gate** — a **placement veto** (`siteGradingVeto`, **default ON**) that rejects
  un-buildable candidate sites — ships and is the shipped default behaviour.
- The authoritative **flat-snap + CUT/FILL/resurface earthwork** ships behind **default-off**
  `siteGradingCutFill` (with grade sub-switch `siteGradingCut`, default on). When ON it flat-snaps
  every village piece to one plane AND grades the terrain to that plane. It is **feature-complete**
  (flat-snap 3c + fill/resurface 3b/Phase 4 built) but stays **off by default** until the 10-seed ×
  10-village validate-loop (3d) certifies it at scale.

## Villages are rarer on rough terrain, and ABSENT on un-buildable terrain (by design)

With `siteGradingVeto` on (default), a candidate site is rejected when — measured over the village
**FOOTPRINT** (`anchor ± (maxDistanceFromCenter + PAD_RADIUS)`), on the water-clamped predictor
floor `max(floor, 63)` — any of:

- **relief** (max−min) exceeds `siteGradingVetoMaxRelief` (default 32, Range 8–128): beyond the
  ceiling the village sits across too much vertical relief for the jigsaw paths / villager routes to
  stay walkable.
- **local roughness** (max step between adjacent 16-block samples) exceeds `VETO_ROUGHNESS_MAX`
  (=PITCH=16): a genuine local CLIFF (node slope > 1) bisecting the footprint — the barrier a modest
  overall relief can hide. Rolling ground (node steps ≤ ~8) passes.
- **water depth** under the footprint core exceeds `siteGradingWaterVetoDepth` (default 32): a
  support / floating-house guard; 32 keeps ordinary coastal villages and rejects genuine over-water
  floaters.

Consequence: measured on the pre-pivot tiers, ~44–50% of candidate sites survived gentle
1.2×-relief terrain and ~20% survived 1.77×; the static world's **3.32× relief amplification sits
beyond both**, so expect villages to be noticeably rarer outside flat-erosion regions — relief is
an ABSOLUTE walkability ceiling and amplified relief pushes more footprints past it. On a
**highland-spawn seed the immediate spawn region can lose all villages** (correct — it is
uniformly mountainous).
This is intended: villages are accessible-or-absent, never broken. Tune with
`siteGradingVetoMaxRelief` or set `siteGradingVeto=false` for pure-vanilla placement. A rejected site
consumes no worldgen randomness, so it perturbs no neighbour (deterministic, seam-free).

## The flat-snap + grade earthwork (`siteGradingCutFill`, default OFF)

When `siteGradingCutFill` AND `siteGradingCut` are both on, a graded village is made walkable by
construction:

- **Flat-snap (3c interlock).** `DesignSurface.clampProjection` projects every house AND street piece
  to ONE plane (`anchor`), so the pieces are coplanar — the vanilla ±3–4 block per-piece template
  spread that breaks village paths on slopes is eliminated (path-junction step = 0). Flat-snap is
  gated on `cutFill && cut`: it fires ONLY when the grade will actually run, because flat-snapping
  WITHOUT grading would leave pieces inset/floating on the slope (the reverted-v0.2.13 failure). The
  two move together.
- **Grade (`SiteCut.grade`, at `StructureStart.placeInChunk` TAIL).** Grades the terrain to the flat
  platform: own-piece cells (house ANCHOR + street PATH) CUT everything above the piece box top (so
  doors/paths are un-buried) and FILL the natural gap strictly BELOW the piece box for support
  (displacing water for over-sea support, uncapped to the seabed); APRON collar cells CUT down to /
  FILL up to the target height `T` and RESURFACE the graded top. NATURAL and foreign cells are never
  touched. The grade is spill-decoupled (reads the frozen WG heightmaps, not the live being-built
  world) and depth-capped at `siteGradingVetoMaxRelief + siteGradingApronRampMax`.
- **Piece-declared open volumes are preserved (village wells).** The own-piece support fill stops
  strictly below the lowest covering piece box (`box.minY`), so a piece's DECLARED sub-plane volume —
  a well's water shaft, a piece-interior void — is never dirt-filled. The in-box foundation zone
  (`box.minY..T`) is closed instead by the noise-stage GradePad beard and the air-only M4 foundation
  fill (`siteGradingFoundationFill`, which breaks on liquid). Filling only below the box distinguishes
  natural sea/gap (solidify for support) from in-piece water (preserve) by geometry, deterministically.

### Resurface material is biome-derived (deterministic)

The resurface block is chosen from the **seed-frozen biome** at the column (via `level.getBiome`):
SAND on desert / beach / snowy-beach / badlands, GRASS_BLOCK otherwise. It is deliberately NOT a
live "what block is on the surface here" read — a live read would make the write depend on generation
order and break bit-level determinism under parallel pregen. Consequence: an exotic surface (e.g. a
mycelium or podzol patch a decorator would have placed) is resurfaced as grass/sand, not its rarer
material. Accepted: determinism outranks cosmetic surface fidelity on the graded apron.

## Accepted residuals & scope

- **A graded village regenerates with a DIFFERENT piece set.** Flat projection changes the heights
  the jigsaw BFS sees, so it attaches a different (still valid, still flat) set of pieces than the
  un-graded village would have. This is a new deterministic village, not the pre-fix layout mutated.
  Only NEWLY generated chunks are affected; existing villages are never rewritten.
- **Over-water fill is visible earthwork.** Filling a dip/pond up to the platform leaves a graded
  bank; on a coastal village the fill to the waterline is deliberate and visible. The FILL never
  writes below the frozen ocean floor and replaces only air/water (never existing solid).
- **Auditor flat% is a noisy secondary signal.** The auditor scans the ground DOWN past tree
  logs/foliage (canopy no longer counts), making flat% a grade-quality signal — but street decorations
  (lamp posts) and house/street piece-box overlap still read as bumps, so ~19% of street columns can
  deviate >±1 even on a correctly graded, walkable village. The RELIABLE walkability signal is **C2**
  (piece-plane path slope = 0 when flat-snapped); flat% only guards against a flat-snapped-but-ungraded
  village (real terrain dips, which it still catches). A directional (below-plane) / overlap-excluding
  flat% is a 3d-loop refinement.
- **Roughness slope-1 boundary.** The roughness gate rejects a node step **> 16** (local slope > 1),
  so an exactly-slope-1 (16-block) local face passes. Whether that counts as "broken" is a judgment
  call; the relief ceiling is the primary accessibility gate.
- **House-corner squeeze (R3).** Vanilla jigsaw gives village houses an irreducible ±3–4 block
  template floor-plane spread. With flat-snap ON the placed pieces are coplanar, so this is largely
  eliminated for graded villages; it remains a latent concern for the veto-only (cutFill off) path,
  where the field measures it itself (`slopeGuaranteed`).
- **Deep-water support.** `siteGradingWaterVetoDepth=32` admits villages whose footprint-core column
  overhangs water up to 32 deep; with the grade ON the FILL raises the seabed to the platform, but a
  below-sea shallow structure (e.g. a shipwreck) under a deep fill can be clipped at its top. Minor;
  lower `siteGradingWaterVetoDepth` for stricter over-water rejection.
- **Underground foreign structures are NOT protected by the grade skip — they are below it.** A
  foreign structure whose bounding box top is below sea level (mineshaft, stronghold, ancient city)
  is intentionally NOT treated as a surface obstacle by the grade (its footprint would otherwise
  yield the entire surface village and grade nothing). Surface foreign structures (box reaching ≥ sea
  level) are fully protected — the grade yields their footprint plus a halo over the 3×3 chunk
  neighbourhood (`siteGradingForeignHaloExtra`, clamp 0..4). See the mod-compatibility limitation.
  **Note (v0.3.14):** "underground" for the *village CUT+FILL* foreign-yield above still means the
  absolute sea-level test, and that is deliberate — changing it would alter terrain inside gradable
  village chunks. The *foreign-inset earthwork* (`ForeignInsetBeard`/`ForeignInsetGrade`) no longer uses
  the sea-level test at all; see the next bullet.
- **"Underground" is a SURFACE-RELATIVE property in this mod, not an absolute Y** (v0.3.14, the
  `create_ltab:cave_ruins` floating-island fix). Archean Rise's land reaches y=768, so the vanilla-era
  proxy "box top below sea level (63) ⇒ underground" is meaningless here: a cave ruin buried 40 blocks
  beneath a y=231 mountain has a box top of 199 and reads as a *surface building*. The foreign-inset
  earthwork therefore excavated it — hollowing y192..206 out from under the mountain and leaving a
  detached ~12×12×25 floating island whose horizontal cross-section was exactly the piece's bounding box.
  `BuriedStructures.isBuriedStart` now classifies a foreign start by measuring how deep its start piece
  sits **below the surface at the column vanilla projected it from** (`insetForeignBurialMargin`,
  default 8). Because vanilla's projection arithmetic makes burial a pure function of the structure's own
  declared `start_height` (the terrain term cancels), a surface structure is *mathematically incapable* of
  tripping the rule regardless of relief. Buried structures keep vanilla's own `terrain_adaptation`;
  their placement is unchanged. **Residual:** the snow and water veto gates
  (`StructureSnowGateMixin`/`StructureWaterGateMixin`) still use the old absolute sea-level test, so a
  buried foreign structure under a snowy or deep-water *surface* can still be falsely vetoed (deleted).
  That is a latent bug, deliberately not fixed in 0.3.14 because fixing it changes world content for
  structures that never had the floating-island defect (`create_ltab:grot`, `the_bunker`,
  `minecraft:trail_ruins`). **Re-review trigger:** revisit when a buried foreign structure is reported
  missing under snow/water, or when the next foreign-structure change lands.
- **In-box foundation depends on the beard / M4 (both default on).** The own-piece support fill stops
  below `box.minY`; the in-box zone (`box.minY..T`, ≤ `groundLevelDelta` blocks) is closed by the
  GradePad noise beard and the air-only M4 `foundationFill`. Either one alone suffices, and both
  default ON. Only if an admin disables BOTH (`siteGradingGradePad=false` AND
  `siteGradingFoundationFill=false`) with the grade on does a house over a dip get a bounded
  ≤`groundLevelDelta`-block air gap directly under its floor (a short stilt on the below-box dirt, not
  a float). This is the inherent cost of preserving wells (crit-5): the old unconditional fill that
  closed this zone is exactly what dirt-filled well shafts.
- **Thick modded terrain-matching path pieces.** M4/GradePad are RIGID-only, so a PATH (terrain-matching)
  cell's in-box zone is supported only by SiteCut's below-box fill. Vanilla village streets are thin
  (`box.minY ≈ T`) so this is full support; a MODDED `siteGradingExtraStructures` entry with a thick
  terrain-matching path piece could leave a sub-plane gap under it. Non-issue for the shipped allowlist
  (vanilla villages + outpost).
- **`getBoundingBox` inflation vs `/locate`/spacing** — a gradable-only, minimal box inflation for
  fringe-chunk coverage shifts `/locate` distances and structure spacing slightly; disable-config
  fallback exists (re-review trigger: users report drift).

## Why it stays default-off

`siteGradingCutFill` is experimental until the 3d validate-loop (10 seeds × 10 villages, block-level
`/archeanrise-audit placement` WALKABLE verdict on each) certifies it. Single-village live tests
(seed 555777999 tier-3) confirm a broken village (C2 path-step 5) regenerates flat-snapped to C2 0,
WALKABLE — but a village near a foreign structure, on extreme terrain, or in an unusual biome has not
been swept at scale. Enable it for testing; leave it off for stable play.
