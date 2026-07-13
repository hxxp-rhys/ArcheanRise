# Limitations — biome borders ("abrupt cutoff" / blending)

**Short version:** true blending of biome *identity* along borders is **not possible** while Archean
Rise keeps the vanilla `minecraft:multi_noise` biome source (a hard compatibility contract — it is what
lets TerraBlender / Biolith / Lithostitched biome mods inject). The abruptness is mostly a vanilla trait,
not an Archean Rise bug. What *can* be softened is partial and appearance-only; the effective levers and
their costs are below.

## Why borders are abrupt (the mechanism)

`MultiNoiseBiomeSource` assigns exactly **one** biome per 4×4×4 "quart" cell, by nearest-neighbour in
6-axis climate space (temperature, humidity/vegetation, continentalness, erosion, weirdness/PV, depth).
There is **no interpolation of biome identity anywhere** — the lowest-fitness climate box simply owns the
cell. Consequences:

- **Terrain height is continuous** across a border (all biomes share the same density functions), so
  there is no terrain cliff at a border.
- **Everything keyed to biome identity SNAPS** at the quart line: surface blocks (grass↔sand), placed
  features/foliage, mob spawn lists, and the grass/foliage/water **colour**. Crossing the line, all of
  these change in a single step. That step *is* the "abrupt cutoff."

The vanilla client **"Biome Blend"** video setting blends only the grass/water/foliage **colour** over a
3–15 block radius; it never blends blocks, features, or identity. It is the single biggest,
zero-risk reduction of *perceived* abruptness — **recommend players raise it** (Options → Video →
Biome Blend). Archean Rise does not (and cannot) change this from the mod side.

## Interaction with the biome scale

Archean Rise biomes are large (climate wavelength ÷Sh = 12 everywhere — the extra ×1.5 land
blend was removed 2026-07-10; it teleported temperature bands near coasts), so
borders are **fewer but longer** — you travel alongside one border for minutes and cross it as a rarer,
more "significant" event. So the abruptness is *more* prominent than in vanilla even though it is the
same mechanism. This is the argument for softening it, and the reason the lever below scales with Sh.

## Levers (all partial; none blends identity)

| Lever | What it softens | Safe to ship blind? | Cost / risk |
|---|---|---|---|
| **Client Biome Blend** (recommend to users) | grass/water/foliage **colour** | ✅ (not a mod change) | none — a client video setting |
| **Domain-warp enrichment** (`shift_x`/`shift_z`) | border **shape** — makes the line wavy/interdigitated instead of straight | ❌ **no** | the shift also drives continents/erosion/ridges, so it re-shapes **all terrain** (coastlines, rivers), a golden-seed rebaseline; pushed too far it **folds** climate into speckled single-quart biomes. Needs in-game visual tuning. Does **not** soften the perpendicular snap — only the line's shape. |
| **Surface-rule transition dithering** | surface **blocks** near a border (grass↔sand feathered with a shared neutral block) | ⚠️ mostly | pure data, no terrain/biome change, cannot fold — but surface rules cannot read the neighbour biome, so it can only dither each biome's *own* surface (per-biome-pair authoring), does nothing for foliage/features/mobs/colour, and a heavy dither muddies intended surfaces. Needs visual tuning. |
| **Transition biomes** (climate-intermediate) | per-border **contrast** (the A→B jump passes through a milder C) | ❌ | adds MORE borders (each still hard); needs new/re-parameterised biomes; edits the parameter list (collides with list-replacers like Terralith). Highest design cost. |
| **Custom blending biome source** | biome identity (true blend) | ❌ **rejected** | the ONLY path to real blending, but it abandons `MultiNoiseBiomeSource` → TerraBlender/Biolith injection goes invisible and it needs per-loader code. Violates the hard contract. |

## Archean Rise's position (v0.2.21)

1. **Client Biome Blend** — raise it; the biggest, free, zero-risk win for the most visible facet (colour).
2. **Domain-warp — SHIPPED as `biomeBorderBlend` (0..24, default 0) from v0.2.21.** The earlier concern
   (editing `shift_x`/`shift_z` re-shapes *all* terrain) is avoided: the warp is applied at the CLIMATE
   SAMPLER seam and warps **only temperature+humidity**, so terrain is **byte-identical** (continents/
   erosion/depth/weirdness unwarped — no terrain blast radius, no golden-seed *terrain* rebaseline).
   Borders finger/interlock instead of forming straight lines, and injected biome mods inherit it. It
   stays **OFF by default** — the magnitude constants (`AMP_MAX`, `PERIOD`, warp firstOctave) are
   `[JUDGMENT]` and want in-game tuning; raise the knob to taste. Caveat: changing it on
   an existing world cosmetically seams the biome layout at the generation frontier (no terrain
   cliff). Softens border SHAPE only — see the ceiling below.
3. **Surface-rule dithering** — the second half of the `biomeBorderBlend` knob (buildSurface mixin,
   feathers the surface BLOCK across a border). Shipping as a follow-up increment.

**Bottom line:** there is no data-only technique that softens the perpendicular identity snap for
features/foliage/mob-spawns under `MultiNoiseBiomeSource`; borders can be made *wavier* (warp), their
*surface blocks* feathered (surface dithering), and their *colour* blended (client setting) — each a
partial, appearance-only improvement. True identity blending is out of scope for the contract.
