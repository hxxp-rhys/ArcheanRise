// Archean Rise spec-pipeline CORE — the single implementation of the terrain math, shared by:
//   - tools/generate-worldgen.mjs  (Node: emits the datapack the mod ships)
//   - tools/preview/               (browser: live knob-adjusted preview via deepslate)
// Pure data code: NO node/browser APIs. Everything here must stay byte-compatible with the
// emitted datapack — the generator's regen-byte-identity gate guards this file.
//
// Parameterization: the factories take a TC (TerrainConfig) + WORLD + CONTINENT_ROWS so the
// preview can override knobs; DEFAULT_* are the shipped values. The bound convenience exports
// (surfaceY, KNEE_SAMPLES, ...) are the factories applied to the defaults — what the
// generator uses.

// ---- world box (Phase-1 contract; spec §0 assumptions verified 2026-07-10) ----
export const DEFAULT_WORLD = {
  minY: -256,
  top: 768,       // WORLD_TOP — verified: min_y + height = 768
  s: 3.32,        // Sv: vertical envelope for slides/depth/factor (relief itself now comes
                  // from the spec pipeline; Sv keeps the band-thickness invariant; the
                  // 655..708 top-slide backstop is TC-derived since M1)
  sr: 6.64,       // PV/ridges wavelength — RETAINED for the biome-variant/river-band channel
                  // only (terrain relief no longer reads PV); spec rivers get their own graph
  sh: 12.0,       // climate wavelength (biome UX ceiling; spec's "slower still" satisfied)
};

// ---- TerrainConfig (spec §4 — single source of truth; sea-63 recompute per DECISIONS) ----
export const DEFAULT_TC = {
  WORLD_TOP: 768,
  PEAK_BUFFER: 60,          // = the spec minimum (user M1 retune 2026-07-10: cap raised to 708)
  PEAK_CAP: 708,            // user M1: summits to 708 (= top-slide end, TC-derived in the generator)
  SOFT_KNEE_Y: 640,         // M1: knee starts higher — tanh shapes the last ~68 blocks instead of
                            // clipping everything into a plateau (v0.3.9 ran 12.6x overdrive)
  SEA_LEVEL: 63,
  SEA_FLOOR: -128,
  OCEAN_DEPTH: 191,         // SEA_LEVEL - SEA_FLOOR
  LAND_RELIEF: 645,         // PEAK_CAP - SEA_LEVEL
  HILL_RELIEF: 120,         // blocks — user retune 2026-07-10 (preview report): strongly rolling lowlands
  // M2 TWO-SCALE MASSIFS (doc 13, 2026-07-10): a single-wavelength carrier cannot deliver big
  // relief AND realistic slopes (sustained slope ≈ atan(2·relief/λ)); real ranges carry MASS at
  // long wavelength and CRAGGINESS at short wavelength. MACRO = the mountain mass (12–24°
  // structural flanks); MESO = crags/spurs on those flanks, amplitude-bounded so it can no
  // longer build 240-block walls. Supersedes RIDGE_RELIEF/RIDGE_FREQ (v0.3.9 900@1/850 maps
  // to MACRO 560@1/4800 + MESO 90@1/850 — the preview import translates old saves).
  MACRO_RELIEF: 560,        // blocks — the massif mass carrier
  MACRO_FREQ: 1 / 4800,     // macro crests ≈ 4.8 km apart
  MESO_RELIEF: 90,          // blocks — crag/spur texture on the macro flanks
  MESO_FREQ: 0.001176470588235294, // ≈ 1/850 (the user's v0.3.9 crest-texture wavelength)
  // M3 FLANK DISSECTION: dendritic gully incision, strongest MID-flank (parabolic weight
  // m(1−m) keeps ridgelines and footslopes intact), scaled by mountainAmp.
  RAVINE_DEPTH: 45,         // blocks — max mid-flank gully incision
  RAVINE_FREQ: 1 / 600,     // gully network wavelength
  APRON_EDGE_HI: 0.7,       // voronoi-edge value where the macro FOOTHILL APRON reaches full
                            // strength: the macro mass ramps border→core across most of each
                            // province (≈1–1.5 km at cell 2400) instead of completing inside
                            // the personality fade band (validator finding: the amp-shaping
                            // alone changed onset CURVATURE, not its spatial reach)
  DETAIL_RELIEF: 72,        // user retune
  SEABED_DETAIL: 6,
  PROVINCE_M_MAX: 1.45,     // user retune
  // COASTAL SUPPRESSION RETUNE (2026-07-10 field report "no mountains seen"): at the Sh-scaled
  // continentalness wavelength (~24.6 km), the spec's abstract inland fractions put full relief
  // (INLAND 0.30) and full mountainAmp (Lrel 0.40) many kilometres inland — and spawn_target
  // always drops players in the coastal band, so the entire explorable start area was
  // relief-suppressed. Full relief now arrives ~half as far inland; coastal ranges are
  // realistic and the spec's real constraint ("no sea peaks") still holds via the Lrel clamp.
  SHORE_HI: 0.035,          // user retune 2026-07-10
  INLAND: 0.1,              // user retune: mountains reach even closer to coasts
  CONTINENT_WARP_FREQ: 1 / 1500, // coastline crenellation wavelength
  BASE_FREQ: 1 / 2400,      // n_hill rolling swells
  DETAIL_FREQ: 1 / 500,
  WARP_FREQ: 1 / 1500,      // user retune: swirlier, half-wavelength warp
  WARP_AMP: 525,            // blocks — user asked 740, CLAMPED to the folding limit at λ1500
                            // (740/1500 = 0.49 > 0.35 hard-fails validateTc; 525/1500 = 0.35 is
                            // the max artifact-free amplitude at this wavelength; see DECISIONS)
  COAST_WARP_AMP: 0.25,     // user retune; added to L (in [-1,1] space) inside the shore band
  RIDGE_OCT: 4,             // user retune: octaves of the MESO carrier (macro is fixed at 5)
  MOUNTAIN_COMMONNESS: 2.5, // user retune 2026-07-10 (was 1.35): most provinces qualify as mountainous
  PROVINCE_CELL: 2400,      // user retune: tighter range↔plain alternation (was 4100)
  PROVINCE_SALT: 500,
  PROVINCE_FADE_HI: 0.35,   // user retune: smaller full-expression cores, wider blends (was 0.25
                            // = the measured edge p50; see tools/measure/voronoi-tune.mjs)
  // deviations (documented, DECISIONS 2026-07-10): CONTINENT_FREQ/EROSION_FREQ stay the
  // Sh-scaled climate wavelengths (1/24576) — the spec's ordering intent (slowest) holds.
};

// ---- spec §2d continent spline (sea-63 adjusted; L → world Y) ----
// REALIGNED 2026-07-10 (user report: spawned on the ocean floor, no land in sight): the
// spec's abstract table put the waterline at L=0, but L here is VANILLA continentalness,
// whose semantics are baked into spawn_target (cont ≥ −0.11 = spawnable) and the biome
// thresholds (deep_ocean < −0.455 < ocean < −0.19 < coast < −0.11 ≤ land). The waterline
// must therefore sit at LAND0 ≈ −0.14 (mid-coast band) — beach biomes land AT the beach,
// ocean biomes over water, and spawn candidates are dry again.
export const LAND0 = -0.14; // the waterline in vanilla-continentalness space
export const DEFAULT_CONTINENT_ROWS = [
  [-1.05, -128], // abyssal floor (SEA_FLOOR row; mushroom fence sits below this)
  [-0.60, -85],  // deep ocean (matches the deep_ocean biome band)
  [-0.35, -25],  // continental slope (ocean band)
  [-0.19, 40],   // shelf top (ocean→coast biome boundary)
  [-0.16, 56],   // shallow near-shore
  [LAND0, 63],   // WATERLINE — mid coast-biome band, flat beach approach
  [-0.10, 66],   // beach / berm (top of the coast band)
  [0.10, 100],   // coastal lowland (user retune: was 95)
  [0.45, 170],   // inland base (user retune: was 150)
  [1.00, 220],   // high interior baseline
];

// ---- offset-unit algebra (unchanged Phase-1 anchors) ----
export const G_SEA = 0.5078125;
export const OFFSET_BASE = -0.50375;
export function makeAlgebra(world, tc) {
  const SEA = tc.SEA_LEVEL;
  const surfaceY = (v) => SEA + (OFFSET_BASE + G_SEA + v) * 128 * world.s;
  const vOf = (y) => (y - SEA) / (128 * world.s) - (OFFSET_BASE + G_SEA);
  const blocksToV = (b) => b / (128 * world.s);
  return { SEA, surfaceY, vOf, blocksToV };
}

// ---- soft knee sampled into a monotone value-domain spline (no tanh node exists) ----
export function makeKnee(world, tc) {
  const { vOf } = makeAlgebra(world, tc);
  const V_KNEE = vOf(tc.SOFT_KNEE_Y);
  const V_CAP = vOf(tc.PEAK_CAP);
  const V_FLOOR = vOf(tc.SEA_FLOOR);
  const kneeF = (v) => v <= V_KNEE ? v
      : V_KNEE + (V_CAP - V_KNEE) * Math.tanh((v - V_KNEE) / (V_CAP - V_KNEE));
  const KNEE_SAMPLES = (() => {
    const span = V_CAP - V_KNEE;
    const pts = [[V_FLOOR - 0.05, V_FLOOR - 0.05, 1.0], [V_KNEE, V_KNEE, 1.0]];
    for (const m of [0.5, 1.0, 1.6, 2.4, 3.4, 4.6]) {
      const v = V_KNEE + m * span;
      const t = Math.tanh(m);
      pts.push([v, V_KNEE + span * t, 1 - t * t]); // derivative = tanh'
    }
    return pts;
  })();
  return { V_KNEE, V_CAP, V_FLOOR, kneeF, KNEE_SAMPLES };
}

// ---- tree builders ----
export const mul = (a, b) => ({ type: 'minecraft:mul', argument1: a, argument2: b });
export const addN = (a, b) => ({ type: 'minecraft:add', argument1: a, argument2: b });
export const addAll = (...t) => t.reduce((a, b) => addN(a, b));
export const clampN = (input, min, max) => {
  if (typeof input === 'string') {
    // vanilla codec quirk (found the hard way, 2026-07-10): minecraft:clamp parses its input
    // with the DIRECT codec — a registry REFERENCE string fails at world load ("Not a
    // number ... Not a JSON object"). Use minN/maxN (Ap2 nodes take references) or inline.
    throw new Error('clampN: minecraft:clamp cannot take a reference input (' + input + ') — use minN/maxN or inline the tree');
  }
  return { type: 'minecraft:clamp', input, min, max };
};
export const minN = (a, b) => ({ type: 'minecraft:min', argument1: a, argument2: b });
export const fc2d = (a) => ({ type: 'minecraft:flat_cache', argument: { type: 'minecraft:cache_2d', argument: a } });
// R2b channel carve (doc 14 §2 R2b): archean_rise:river_carve wraps a DF and applies the river
// carve per column IN-GAME (RiverCarveDensityFunction). It is IDENTITY everywhere the NATURAL
// surface is evaluated — the preview engine (engine.mjs) and the graph's PreviewParityEvaluator
// both pass through to `argument` — so it changes nothing in the previews/parity references;
// only the game's vanilla-DF terrain carves. mode:'carve' (default) → carved offset from the
// natural-offset argument; mode:'valley' → argument·valleyFactor (mountainAmp suppression).
export const riverCarve = (argument) => ({ type: 'archean_rise:river_carve', argument });
export const riverCarveValley = (argument) => ({ type: 'archean_rise:river_carve', mode: 'valley', argument });
// R2b-4e full-relief corridor suppression (spec §7.3): mode:'sink' grades the natural surface DOWN
// toward the reach water level inside the river corridor+halo (mask 1 in the bed, smoothstep→0 over
// the halo, EXACTLY 0 outside → byte-identical non-river terrain). mountain_amp_valley suppresses only
// the mountain MASS; HILL_RELIEF + y_base still perch the mountainside 45–58 blocks above the water,
// which the carve then cuts as a sub-grid vertical slot the 4×8 noise cell bridges back to solid.
// SINK pulls that perching relief to the water so the carve is a WIDE graded valley. IDENTITY in every
// preview/parity path (engine + PreviewParityEvaluator treat river_carve as pass-through), so the
// designed surface, the graph/routing and the D1 offset-freshness reference all see the natural offset.
export const riverCarveSink = (argument) => ({ type: 'archean_rise:river_carve', mode: 'sink', argument });
export const splineOn = (coordinate, pts) => {
  for (const p of pts) {
    if (!Number.isFinite(p[0]) || !Number.isFinite(p[1])) throw new Error('splineOn: non-finite point ' + JSON.stringify(p));
    if (p.length > 2 && !Number.isFinite(p[2])) throw new Error('splineOn: non-finite derivative ' + JSON.stringify(p));
  }
  return {
    type: 'minecraft:spline',
    spline: { coordinate, points: pts.map(([location, value, derivative = 0.0]) => ({ location, value, derivative })) },
  };
};
export const WARP_NOISE_LAMBDA = 8;      // archean_rise:warp firstOctave -3
export const RIDGED_NOISE_LAMBDA = 128;  // archean_rise:ridged_base firstOctave -7
export const HILL_NOISE_LAMBDA = 8;      // archean_rise:hill firstOctave -3 (4 amplitudes = 4 octaves)
export const warpSample = (lambda, cx, cz) => ({
  type: 'minecraft:shifted_noise', noise: 'archean_rise:warp',
  shift_x: cx, shift_y: 0.0, shift_z: cz,
  xz_scale: WARP_NOISE_LAMBDA / lambda, y_scale: 0.0,
});
// Single-stage warp per spec §4 (WARP_AMP @ 1/WARP_FREQ); the coast crenellation (§2d-iii)
// is a separate L-space term. The old 2-stage warp is retired with the spec adoption.
export function buildWarpTrees(tc) {
  const l = 1 / tc.WARP_FREQ;
  return {
    dx: fc2d(mul(tc.WARP_AMP, warpSample(l, 0.0, 0.0))),
    dz: fc2d(mul(tc.WARP_AMP, warpSample(l, 3781.0, -3781.0))),
  };
}
export const refDx = (ns) => `archean_rise:${ns}/warp/dx`;
export const refDz = (ns) => `archean_rise:${ns}/warp/dz`;
export const voronoiNode = (tc, output) => ({
  // NO terrain warp on the cellular field (measured 2026-07-10: the 900@λ3000 warp has
  // gradient ~1.9 — far past the ~0.12 folding limit — which folds the sampling map and
  // collapses F2-F1 to p50 0.09 and scrambles cell ids; provinces alternate correctly only
  // unwarped. A dedicated mild warp (<=0.12*cell) may be added later for border wiggle.)
  type: 'archean_rise:voronoi', cell_size: tc.PROVINCE_CELL, salt: tc.PROVINCE_SALT, output,
});
// parameterized ridged carrier — the macro massif, the meso crag layer, and the ravine field
// all share this construction with independent noise channels (independent fromHashOf seeds)
export const ridgedNodeFor = (ns, noise, octaves, freq) => ({
  type: 'archean_rise:ridged_multifractal', noise,
  octaves, lacunarity: 2.0, gain: 0.5, weight_gain: 2.8, offset: 1.0,
  xz_scale: RIDGED_NOISE_LAMBDA * freq / 2, // nominal λ = 2/freq → crests ≈ 1/freq
  shift_x: refDx(ns), shift_z: refDz(ns),
});
// the MESO carrier — also used by the generator's --debug-field=ridged mode
export const ridgedNode = (ns, tc) => ridgedNodeFor(ns, 'archean_rise:ridged_base', tc.RIDGE_OCT, tc.MESO_FREQ);

/**
 * The spec §2 pipeline. Emits every named tunable DF file via emit(relPath, json) and returns
 * the final offset tree (the caller writes it as overworld/offset). emit is the seam: the
 * generator passes its fail-loudly write(); the preview passes an in-memory collector.
 */
export function buildSpecPipeline({ ns, tc, world, rows, emit }) {
  const { vOf, blocksToV } = makeAlgebra(world, tc);
  const { V_CAP, V_FLOOR, KNEE_SAMPLES } = makeKnee(world, tc);
  const R = (p) => `archean_rise:${ns}/${p}`;
  const L = R('overworld/continents');
  const E = R('overworld/erosion');

  // province PERSONALITY (spec §2b, redesigned 2026-07-10 from debug-field measurement):
  // the old clamp01(L*(1-E))*province gate measured max 0.26 / p50 0.16 WORLD-WIDE — the
  // Sh-scale erosion deviation makes (1-E) near-constant over 25-km megaregions, so the
  // spec's few-km mountain/plains alternation never happened (the true root cause of the
  // mountain-less 0.3.5/0.3.6 worlds). Fix: the 4.1-km Voronoi provinces — terrain-scale by
  // construction and the spec's own "subdued plains vs aggressive ranges" mechanism — become
  // the PRIMARY alternation driver; erosion demotes to a soft modulator. MOUNTAIN_COMMONNESS
  // widens the qualifying band (the user's +35% knob acts here).
  // fade [0 -> PROVINCE_FADE_HI=0.25]: measured OFFLINE over 16x16 cells (voronoi-tune, 6.5M
  // samples — the exact splitmix64 field): edge p50 = 0.25, so full personality expression
  // covers HALF of each cell's area (borders still blend over ~500-800 m). Pointwise province
  // mix becomes subdued 30% / foothill 29% / mountain 22% / aggressive 19% (was 25/41/23/11
  // with the old 0.5 endpoint — assumed from the [0,4.5] codomain, never measured).
  const edgeInterior = splineOn(R('spec/province_edge'), [[0.0, 0.0], [tc.PROVINCE_FADE_HI, 1.0]]);
  emit(`data/archean_rise/worldgen/density_function/${ns}/spec/province_edge.json`, fc2d(voronoiNode(tc, 'edge')));
  const cm = tc.MOUNTAIN_COMMONNESS;
  emit(`data/archean_rise/worldgen/density_function/${ns}/spec/province.json`,
      fc2d(splineOn(mul(voronoiNode(tc, 'cell_id'), edgeInterior), [
        [-1.0, 0.05],            // deeply subdued plains province
        [-0.20 / cm, 0.15],      // subdued
        [0.20 / cm, 0.55],       // foothill province
        [0.70 / cm, 1.0],        // mountain province
        [1.0, tc.PROVINCE_M_MAX], // aggressive range (knee absorbs overdrive)
      ])));

  // mountainAmp = clamp01(Lrel / INLAND) * personality * softErosion — full strength once past
  // the (retuned) INLAND line, aligned with landFactor; erosion modulates +-, never
  // annihilates; no sea peaks (Lrel <= 0 clamps to 0 offshore).
  const softE = clampN(addN(1.0, mul(-0.25, E)), 0.7, 1.15); // TRUE modulator: measured E~+1.3 megaregions must not strangle provinces (six-patch probe: all amp <=0.18 at the 0.4 floor)
  emit(`data/archean_rise/worldgen/density_function/${ns}/spec/mountain_amp.json`,
      fc2d(mul(clampN(mul(1 / tc.INLAND, addN(L, -LAND0)), 0.0, 1.0), mul(R('spec/province'), softE))));
  // R2b valley suppression (spec §7.3): the RELIEF chain reads a river_carve(valley)-wrapped copy
  // of mountainAmp so ridge relief does not overhang the channel. NATURAL everywhere except the
  // corridor+halo (the DF returns factor 1.0 outside → byte-identical terrain away from rivers),
  // and IDENTITY in every preview/parity reference (engine treats river_carve as pass-through), so
  // the natural surface, the routing surface (which reads the UN-suppressed spec/mountain_amp) and
  // the graph are all unchanged. Emitted separate from spec/mountain_amp so routing keeps the raw amp.
  emit(`data/archean_rise/worldgen/density_function/${ns}/spec/mountain_amp_valley.json`,
      fc2d(riverCarveValley(R('spec/mountain_amp'))));

  // y_base: the §2d continent spline in offset units
  emit(`data/archean_rise/worldgen/density_function/${ns}/spec/y_base.json`,
      fc2d(splineOn(L, rows.map(([l, y]) => [l, vOf(y)]))));

  // landFactor: smoothstep(SHORE_HI, INLAND, L)
  emit(`data/archean_rise/worldgen/density_function/${ns}/spec/land_factor.json`,
      fc2d(splineOn(L, [[LAND0 + tc.SHORE_HI, 0.0], [LAND0 + tc.INLAND, 1.0]]))); // spec band, waterline-relative

  // noise stacks (2-D): hill (rolling swells), detail (roughness), ridged (massif carrier)
  const hill = {
    type: 'minecraft:shifted_noise', noise: 'archean_rise:hill',
    shift_x: R('warp/dx_scaled'), shift_y: 0.0, shift_z: R('warp/dz_scaled'),
    xz_scale: HILL_NOISE_LAMBDA * tc.BASE_FREQ, y_scale: 0.0,
  };
  // detail is used TWICE (relief roughness + ungated seabed detail). Inline copies are
  // distinct DF nodes — vanilla dedupes only file-referenced nodes per NoiseChunk — so it
  // is emitted once as a cached file and referenced from both sites: one 4-octave noise
  // eval per column instead of two. Values are byte-identical (same node, same math).
  emit(`data/archean_rise/worldgen/density_function/${ns}/spec/detail.json`, fc2d({
    type: 'minecraft:shifted_noise', noise: 'archean_rise:detail',
    shift_x: 511.0, shift_y: 0.0, shift_z: -511.0,
    xz_scale: HILL_NOISE_LAMBDA * tc.DETAIL_FREQ, y_scale: 0.0,
  }));
  const detail = R('spec/detail');
  const ridged = ridgedNode(ns, tc);
  // the shared warp displacement rescaled into the shifted_noise coordinate space of hill
  const { dx, dz } = buildWarpTrees(tc);
  emit(`data/archean_rise/worldgen/density_function/${ns}/warp/dx.json`, dx);
  emit(`data/archean_rise/worldgen/density_function/${ns}/warp/dz.json`, dz);
  emit(`data/archean_rise/worldgen/density_function/${ns}/warp/dx_scaled.json`,
      mul(HILL_NOISE_LAMBDA * tc.BASE_FREQ, refDx(ns)));
  emit(`data/archean_rise/worldgen/density_function/${ns}/warp/dz_scaled.json`,
      mul(HILL_NOISE_LAMBDA * tc.BASE_FREQ, refDz(ns)));

  // RELIEF reads the valley-suppressed amp (river_carve identity in every preview/parity path, so
  // this is the NATURAL amp there; only the game's terrain suppresses it inside corridors).
  const mAmp = R('spec/mountain_amp_valley');
  // M2 (doc 13): TWO-SCALE massifs with the geomorphic hillslope profile. The MACRO carrier
  // holds the mountain MASS at λ≈1/MACRO_FREQ; its shaping spline is the three-segment
  // real-hillslope profile, designed against the MEASURED ridged distribution (debug-field
  // 2026-07-10, n=877k: p10 0.73, p50 0.80, p90 0.86, p95 0.88, max 0.95 — scale-invariant,
  // so it holds for the macro node too):
  //   CONCAVE FOOTSLOPE  ≤p50  → 0..0.06   (long, gentle apron tails)
  //   STRAIGHT MIDSLOPE  p50–p90 → 0.06..0.55 (the ~threshold-angle flank band)
  //   CONVEX SUMMIT      ≥p90  → 0.55..1   (derivative decays to 0 — rounded peaks;
  //                                          saddles/passes survive un-crushed)
  // anchors from the MEASURED oct-5 macro distribution (debug-field=macro, 2026-07-10,
  // n=1.08M: p25 0.68, p50 0.75, p90 0.87, p95 0.89, p99.9 0.91, max 0.93) — octave count
  // changes the distribution materially (oct-6 measured p50 0.80), so each carrier is
  // anchored against ITS OWN measurement, not an assumed-invariant curve.
  const macro = ridgedNodeFor(ns, 'archean_rise:ridged_macro', 5, tc.MACRO_FREQ);
  const macroShaped = splineOn(macro, [
    [0.68, 0.0], [0.75, 0.06, 1.2], [0.82, 0.30, 5.0], [0.87, 0.55, 4.5], [0.90, 0.85, 2.0], [0.925, 1.0, 0.0],
  ]);
  emit(`data/archean_rise/worldgen/density_function/${ns}/spec/macro_shaped.json`, fc2d(macroShaped));
  const M = R('spec/macro_shaped');
  // MESO: crags/spurs riding the macro flanks — the pre-M2 carrier, now amplitude-bounded
  // (it can no longer manufacture walls) and biased toward the upper flanks (0.25+0.75·M).
  // Anchors from the MEASURED oct-4 distribution (debug-field=ravraw, same construction:
  // p25 0.35, p50 0.49, p75 0.60, p90 0.69, p95 0.76, p99 0.87, max 0.98) — the v0.3.9
  // spikiness came from the old oct-6 anchors admitting only the top ~9% of this field.
  const mesoShaped = splineOn(ridged, [
    [0.49, 0.0], [0.60, 0.10], [0.69, 0.35], [0.80, 0.70, 3.0], [0.90, 1.0, 0.0],
  ]);
  // FOOTHILL APRON, two mechanisms (doc 13 M2, completed 2026-07-10 after validator review):
  // (1) CURVATURE: mAmp·min(mAmp, 1) — concave-up (quadratic) onset while amp < 1; linear
  //     above 1 (a plain square would explode the aggressive provinces, amp ≤ ~1.67).
  //     min() not clamp(): amp ≥ 0 by construction and minecraft:clamp cannot take a
  //     reference input (see clampN).
  // (2) SPATIAL REACH: apronWide ramps the macro mass border→core over the province-edge
  //     field up to APRON_EDGE_HI — a km-scale foothill belt at every range edge, decoupled
  //     from the (much narrower) personality fade. Floor 0.15 keeps border massifs present
  //     but subdued. The meso/ravine layers stay on the narrow fade — crags may start before
  //     the mass does, which reads as foothill outcrops.
  const apronWide = splineOn(R('spec/province_edge'), [[0.0, 0.15], [tc.APRON_EDGE_HI, 1.0]]);
  const mAmpSq = mul(mul(mAmp, minN(mAmp, 1.0)), apronWide);
  // M3 RAVINES: dendritic gully incision with parabolic mid-flank weight 4·M·(1−M) —
  // ridgelines and footslopes stay intact; gullies are deepest on the steep flank band.
  const ravine = ridgedNodeFor(ns, 'archean_rise:ravine', 4, tc.RAVINE_FREQ);
  // gully NETWORK over ~the upper half of the measured oct-4 field: ~45% of flank area gets
  // some incision, full depth only along the p95+ dendritic spines
  const ravShaped = splineOn(ravine, [[0.35, 0.0], [0.55, 0.35], [0.75, 1.0, 0.0]]);
  const flankWeight = mul(4.0, mul(M, addN(1.0, mul(-1.0, M))));
  const relief = addAll(
      mul(blocksToV(tc.HILL_RELIEF), hill),
      mul(blocksToV(tc.MACRO_RELIEF), mul(mAmpSq, M)),
      mul(blocksToV(tc.MESO_RELIEF), mul(mAmp, mul(mesoShaped, addN(0.25, mul(0.75, M))))),
      mul(-1.0, mul(blocksToV(tc.RAVINE_DEPTH), mul(mAmp, mul(flankWeight, ravShaped)))),
      mul(blocksToV(tc.DETAIL_RELIEF), mul(detail, addN(0.3, mul(0.7, mAmp)))));
  emit(`data/archean_rise/worldgen/density_function/${ns}/spec/relief.json`, relief);

  // pre-knee offset: y_base + landFactor*relief + ungated seabed detail
  emit(`data/archean_rise/worldgen/density_function/${ns}/spec/offset_preknee.json`,
      fc2d(addAll(R('spec/y_base'),
          mul(R('spec/land_factor'), R('spec/relief')),
          mul(blocksToV(tc.SEABED_DETAIL), detail))));

  // final offset: soft knee (value-domain spline), hard clamp backstop, then the vanilla
  // outer base constant. CRITICAL: vanilla offset.json = add(-0.50375, spline) and the
  // whole v-unit algebra (vOf/surfaceY) assumes offset_total = OFFSET_BASE + v — omitting
  // this add shipped every surface +214 blocks high (measured: spawn p50 Y 321 vs designed
  // ~110; three ridged-shape experiments moved nothing because the bug was additive).
  // R2b: the NATURAL offset is wrapped in river_carve so IN-GAME terrain (depth → sloped_cheese
  // → final_density) reads the CARVED surface. The wrapper is identity in every preview/parity
  // path (engine + PreviewParityEvaluator pass through), so the designed surface, the D1 offset
  // freshness check, and the R2a graph all see the natural offset exactly as before.
  // R2b-4e: the natural offset is first graded by river_carve(sink) — the full-relief corridor
  // suppression that pulls the perching mountainside down to the reach water level over the halo, so
  // the carve incises a WIDE valley, not a sub-grid slot. sink is likewise identity in every
  // preview/parity path, so terrain reads carve(sink(natural)) while the graph reads natural.
  const naturalOffset = addN(OFFSET_BASE, clampN(splineOn(R('spec/offset_preknee'), KNEE_SAMPLES), V_FLOOR, V_CAP));
  return fc2d(riverCarve(riverCarveSink(naturalOffset)));
}

// Coast crenellation (§2d-iii): L' = L + COAST_WARP_AMP * cren(1/1500) * shoreBand(L_base)
export function crenellatedContinents(tc, baseRef) {
  const cren = {
    type: 'minecraft:shifted_noise', noise: 'archean_rise:cren',
    shift_x: 137.0, shift_y: 0.0, shift_z: -137.0,
    xz_scale: WARP_NOISE_LAMBDA * tc.CONTINENT_WARP_FREQ, y_scale: 0.0,
  };
  const shoreBand = splineOn(baseRef, [[LAND0 - 0.30, 0.0], [LAND0 - 0.10, 1.0], [LAND0 + 0.10, 1.0], [LAND0 + 0.30, 0.0]]); // crenellation centred on the waterline
  return fc2d(addN(baseRef, mul(tc.COAST_WARP_AMP, mul(cren, shoreBand))));
}

// the spec noises the pipeline references (1b block) — emitted by the generator, and needed
// by the preview to instantiate deepslate NormalNoise instances with identical parameters
export const SPEC_NOISES = {
  'archean_rise:warp': { firstOctave: -3, amplitudes: [1.0] },
  'archean_rise:ridged_base': { firstOctave: -7, amplitudes: [1.0] },
  'archean_rise:ridged_macro': { firstOctave: -7, amplitudes: [1.0] },
  'archean_rise:ravine': { firstOctave: -7, amplitudes: [1.0] },
  'archean_rise:hill': { firstOctave: -3, amplitudes: [1.0, 1.0, 1.0, 1.0] },
  'archean_rise:detail': { firstOctave: -3, amplitudes: [1.0, 1.0, 1.0, 1.0] },
  'archean_rise:cren': { firstOctave: -3, amplitudes: [1.0] },
};

/**
 * Knob/contract validation shared by the generator (hard assertions) and the preview (live
 * violation display). Returns an array of human-readable violations; empty = valid.
 */
export function validateTc(world, tc, rows) {
  const errs = [];
  const warns = []; // advisories: legal but likely-unintended configurations (preview UI shows amber)
  const { surfaceY, vOf } = makeAlgebra(world, tc);
  const { V_KNEE, V_CAP, KNEE_SAMPLES, kneeF } = makeKnee(world, tc);
  if (world.top !== tc.WORLD_TOP) errs.push(`WORLD.top ${world.top} != TC.WORLD_TOP ${tc.WORLD_TOP}`);
  if (world.top - tc.PEAK_CAP !== tc.PEAK_BUFFER) errs.push('PEAK_BUFFER inconsistent with WORLD_TOP - PEAK_CAP');
  if (tc.SEA_LEVEL - tc.SEA_FLOOR !== tc.OCEAN_DEPTH) errs.push('OCEAN_DEPTH inconsistent with SEA_LEVEL - SEA_FLOOR');
  for (const [L, y] of rows) {
    const back = surfaceY(vOf(y));
    if (Math.abs(back - y) > 0.01) errs.push(`v/y roundtrip broke at L=${L}`);
  }
  for (let i = 1; i < rows.length; i++) {
    if (rows[i][0] <= rows[i - 1][0]) errs.push(`CONTINENT_ROWS locations not increasing at row ${i}`);
  }
  for (let i = 1; i < KNEE_SAMPLES.length; i++) {
    if (KNEE_SAMPLES[i][1] <= KNEE_SAMPLES[i - 1][1]) errs.push('knee spline not monotone');
    if (KNEE_SAMPLES[i][1] >= V_CAP) errs.push('knee sample reached the cap');
  }
  const baseTop = rows.length ? Math.max(...rows.map(([, y]) => y)) : 220;
  // HARD cap check on the all-maxed corner (max base row × amp M_MAX·softE 1.15 with the
  // amp·clamp01(amp) macro gate, every channel crested). NaN-safe by construction.
  const ampMax = tc.PROVINCE_M_MAX * 1.15;
  const cornerRelief = tc.HILL_RELIEF
      + tc.MACRO_RELIEF * ampMax * Math.min(ampMax, 1)
      + tc.MESO_RELIEF * ampMax
      + tc.DETAIL_RELIEF;
  const preKneeV = vOf(baseTop + cornerRelief);
  const worst = surfaceY(kneeF(preKneeV));
  // Saturation-safe (validator F1): tanh returns exactly 1.0 in float64 at corner overdrive
  // ≳19×, making a strict worst < cap compare FALSELY err (it hit every SOFT_KNEE_Y ≥ 660).
  // kneeF < V_CAP is mathematically guaranteed whenever the knee sits below the cap, so
  // validate the INPUTS: knee position and finiteness (NaN-safe via Number.isFinite).
  if (!(tc.SOFT_KNEE_Y < tc.PEAK_CAP)) errs.push(`SOFT_KNEE_Y ${tc.SOFT_KNEE_Y} must be below PEAK_CAP ${tc.PEAK_CAP}`);
  if (!Number.isFinite(preKneeV)) errs.push('worst-case pre-knee relief is not finite — check the relief knobs');
  const kneeOverdrive = Math.max(0, (preKneeV - V_KNEE) / (V_CAP - V_KNEE));
  // PLATEAU advisory on the TYPICAL aggressive summit, not the measure-zero corner (the corner
  // metric cried wolf at shipped defaults — validator finding, 2026-07-10). Typical: inland-base
  // row (or median row), half the hill/detail texture, full-amp (1.0) province, macro at 0.9
  // shaped (≈ field p99+; field-p95 shapes to ~0.77 — deliberately conservative), meso at half. If THIS plateaus (>3.5× past the knee), most big
  // summits flatten and the terrain reads capped.
  const inlandBase = rows.length ? rows[Math.max(0, rows.length - 2)][1] : 170;
  const typicalPreKneeV = vOf(inlandBase + tc.HILL_RELIEF / 2
      + tc.MACRO_RELIEF * 0.9 + tc.MESO_RELIEF * 0.5 + tc.DETAIL_RELIEF / 2);
  const typicalOverdrive = Math.max(0, (typicalPreKneeV - V_KNEE) / (V_CAP - V_KNEE));
  if (typicalOverdrive > 3.5) {
    warns.push(`typical big summits overdrive the knee ${typicalOverdrive.toFixed(1)}× (>3.5): most peaks flatten into a ~${Math.round(surfaceY(V_CAP) - 8)}–${tc.PEAK_CAP} plateau — lower MACRO_RELIEF/MESO_RELIEF or raise SOFT_KNEE_Y`);
  }
  // y_base VALUE monotonicity: legal terrain design (coastal depressions), but nothing else
  // distinguishes intent from a slider accident — advise, never block.
  for (let i = 1; i < rows.length; i++) {
    if (rows[i][1] < rows[i - 1][1]) {
      warns.push(`y_base decreases with continentalness between rows ${i - 1}→${i} (${rows[i - 1][1]}→${rows[i][1]}) — intended depression?`);
    }
  }
  if (tc.SHORE_HI >= tc.INLAND) errs.push('SHORE_HI must be < INLAND');
  if (!(tc.PROVINCE_FADE_HI > 0)) errs.push('PROVINCE_FADE_HI must be > 0');
  if (!(tc.APRON_EDGE_HI > 0)) errs.push('APRON_EDGE_HI must be > 0 (the apronWide spline needs ascending knots)');
  if (!(tc.MOUNTAIN_COMMONNESS > 0)) errs.push('MOUNTAIN_COMMONNESS must be > 0');
  if (0.70 / tc.MOUNTAIN_COMMONNESS >= 1.0) errs.push('MOUNTAIN_COMMONNESS too low: province spline locations collapse');
  if (tc.WARP_AMP * tc.WARP_FREQ > 0.35) errs.push('warp folding risk: WARP_AMP*WARP_FREQ > 0.35 (gradient ~2.2x limit)');
  return { errs, warns, worst, kneeOverdrive, typicalOverdrive };
}
