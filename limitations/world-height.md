# Limitations — World height & the static world

## One world geometry, baked at creation (v0.3.0 pivot)

Since v0.3.0 the tier system is gone. Archean Rise ships exactly **one** world preset, and its
geometry is **baked into the world when it is created**:

| Property | Value |
|---|---|
| World range | **Y −256 .. 768** (`min_y −256`, height 1024, logical_height 1024) |
| Mountain ceiling | **708** — the top slide ends there; terrain physically cannot exceed it (60 blocks of sky padding remain above; raised from 704 in the 2026-07-10 mountains rework) |
| Deepest nominal seafloor | **−128** — 128 blocks of solid rock remain between the deepest ocean and the world floor (local noise dithers the floor a few blocks either way; the dither is vanilla-sized by the band-thickness invariant) |
| Sea level | 63 (unchanged — structure/compat contract) |
| Relief Sv | 3.32× vanilla (the ceiling is a TC contract knob since the 2026-07-10 rework; Sv still scales the slide/depth envelopes) |
| Landform wavelength Sr | 6.64× — the slope-preserving cap Sr = 2·Sv (mean flank grades ≈ real mountains) |
| Biome size Sh | **12×** (the 18×-land coast-gated blend was removed 2026-07-10 — it produced temperature-band patchwork near coasts) — the design's UX ceiling |
| Structure spacing Ss | 3.0× (random-spread sets only; strongholds/mineshafts untouched) — UX ceiling, unchanged |
| Preset id | `archean_rise:archean_rise` (noise settings `archean_rise:rise`, dimension type `archean_rise:overworld_rise`) |

- **Singleplayer:** Create World → World tab → World Type → **Archean Rise**.
- **Dedicated server:** `level-type=archean_rise:archean_rise` in `server.properties` *before first boot*.

## Pre-0.3.0 worlds are NOT supported by 0.3.0+

Worlds created with the removed presets — `archean_rise:tier_1..tier_5` (any 0.2.x) or the legacy
v0.1 `archean_rise:archean_rise` (Y −128..512) — reference `dimension_type` / `noise_settings`
registry entries this version no longer ships. Loading such a world under 0.3.0+ **fails loudly**
at datapack resolution (by design: a silent re-resolve to the new geometry would corrupt the
world with seams and broken heightmaps). Keep those worlds on **mod version 0.2.26** (jars are
archived in `.build/`; the repo tag `checkpoint-v0.2.26` preserves the full source state).

If an *old Archean Rise datapack* is pinned ahead of the 0.3.0 jar, the old geometry can still
load; the mod detects the pre-0.3.0 (min_y, height) signatures at startup, logs a warning, and
shows a red in-game notice to ops. That configuration is unsupported.

## Why config can't change an existing world

Chunks are generated against the dimension's height range and saved that way. If the range of
an existing dimension changed, already-saved chunks would violate the new bounds — on 1.21.2+
this manifests as world corruption; on 1.21.1 as void bands, broken heightmaps, and lighting
errors. This is a Minecraft engine constraint, not an Archean Rise choice. The mod therefore
refuses the ambiguity entirely: the world keeps the exact registry entries it was created with,
and the startup log tells you what it found.

## What does NOT scale (deliberate, for realism and compatibility)

- **Sea level stays 63** (ocean-monument anchoring, structure compatibility contract).
- **Deepslate transition (Y 0..8)** stays at vanilla depth.
- **Caves** stay at vanilla absolute depths. Every cave/carver/aquifer gradient is byte-frozen at
  its vanilla `y_clamped_gradient` values — only the router's bottom slide is floor-anchored. The
  deep volume below roughly Y −48 is therefore essentially **cave-free rock** (~98% solid, ~2%
  lava, ~0% air — measured in `docs/research/02-subterrain-features.md`). Making that volume
  explorable is the scoped cave-generation work (Phase 4 of the pivot plan), not part of this
  change.
- **Bedrock** is the world floor and moves with it, by definition.
- **Overhang/3-D detail noise** is byte-frozen (anisotropy ban) — outcrops stay human-scale.
- Structures place via heightmaps and adapt automatically; fixed-altitude sky structures from
  other mods (y≈130–200 band) sit low against 708-ceiling mountains.

## Ore bands DO move with world depth (engine behaviour, not a mod choice)

Vanilla ore `placed_feature`s anchor their height ranges with `VerticalAnchor`: `absolute`
(fixed Y), `above_bottom` (**min_y + offset**), `below_top` (max_y − offset). Because a subset of
ores are anchored to the world *floor*, `min_y = −256` moves them.

**Floor-anchored, pure translation** — both endpoints `above_bottom`; the band keeps its width
and its peak sits exactly at `min_y`:

| Feature | Range | Peak |
|---|---|---|
| `ore_diamond`, `ore_diamond_buried`, `ore_diamond_large` | trapezoid `above_bottom(−80) .. above_bottom(+80)` | **Y −256** |
| `ore_redstone_lower` | trapezoid `above_bottom(−32) .. above_bottom(+32)` | **Y −256** |

"Diamonds peak at Y −59" was never a designed constant — it is *a few blocks above bedrock*.
In the static world **diamond peaks at Y −256**, and the peak band is proportionally richer per
Y-slice than vanilla's (the trapezoid's lower half clips into the floor).

**Floor-anchored bottom, absolute top** — the band stretches with depth while its vein count
stays fixed, thinning the ore *per Y-slice*. Density per slice vs vanilla (−64 floor), and vs
the last tiered release (v0.2.26 tier 3, −240 floor):

| Feature | Top | vs vanilla | vs v0.2.26 tier 3 |
|---|---|---|---|
| `ore_redstone` | 15 | 0.29× | 0.94× |
| `ore_iron_small` | 72 | 0.41× | 0.95× |
| `ore_lapis_buried` | 64 | 0.40× | 0.95× |
| `ore_infested` | 63 | 0.40× | 0.95× |
| `ore_clay` | 256 | 0.63× | 0.94× |
| `ore_tuff` | 0 | 0.25× | 0.94× |

**Absolute-anchored, unaffected:** `ore_copper`, `ore_gold`, `ore_gold_lower`, `ore_gold_extra`,
`ore_coal_upper/lower`, `ore_iron_middle`, `ore_iron_upper`, `ore_lapis`, `ore_emerald`,
`ore_diamond_medium`, granite/diorite/andesite, `ore_dirt`.

> **Accepted risk (re-review trigger).** The dilution above is a side effect of world depth, not
> a designed balance change. It is accepted because a deliberate ore/gem redistribution is
> already scoped (`docs/research/03` + `05`), and that work must re-derive these bands from
> scratch rather than inherit vanilla's floor-anchoring by accident. Until then, expect thinner
> upper-redstone / small-iron / buried-lapis per layer, and a long, currently cave-poor trip to
> the −256 diamond peak. Re-review when the cave/ore phases of the pivot plan land.

## Performance note

Chunk generation and lighting cost grow roughly linearly with world height: the static world's
1024-block column is **~2.67× vanilla's** noise volume (384) — about equal to the old tier 5
(1008). Every Archean Rise world now pays this cost; there is no "cheaper tier". This sits at
the design doc's practical height ceiling (~1024). Budget server hardware accordingly; the
built-in pregenerator and C2ME are the mitigations (C2ME + Lithium coexistence live-certified
2026-07-05 — C2ME roughly doubled pregen throughput; see docs/COMPATIBILITY.md).

## Ore bands — RESOLVED 2026-07-11 (Phase 0 executed)

The six `above_bottom(0)`-anchored ores that diluted on the 1024-block world (redstone, tuff,
small iron, buried lapis, infested, clay) are re-anchored to absolute −176 with per-slice
yield-neutral counts in Archean worlds (suppress-and-replace, generator-scoped — vanilla-preset
worlds are measurably untouched). Diamond and lower-redstone REMAIN deliberately floor-anchored:
the −256..−176 basement is the richest layer by design ("the deepest thing is the best thing").
Verified by per-slice ore census on 4 225-chunk worlds (tools/measure/ore-census.mjs). The
old dilution table above is retained as history. Route-filling (caves below −64) is the still
open Phase 1; ore GEOMETRY changes are Phase 2 — neither has begun.
