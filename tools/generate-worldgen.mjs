// Archean Rise v0.3.5 static-world generator — SPEC-CONFORMANT to docs/terrain-scaling-spec.md
// (adopted 2026-07-10; conflicts resolved in docs/DECISIONS.md: SEA_LEVEL 63, PEAK_CAP 708
// with buffer 60 = the spec minimum, data-side pipeline inside NoiseBasedChunkGenerator,
// climate-shared wavelengths stay Sh-scaled).
//
// Emits the single preset:
//   noise_settings  archean_rise:rise            (min_y -256, height 1024)
//   dimension_type  archean_rise:overworld_rise  (Y -256..768)
//   world_preset    archean_rise:archean_rise
//
// TERRAIN PIPELINE (spec §2, expressed in offset units v where surfaceY = 63 + (v + 0.0040625)·425):
//   L            = warped + coast-crenellated continentalness (shared with the biome router)
//   y_base       = continentSpline(L)                     (§2d table, sea-63 adjusted)
//   landFactor   = smoothstep(SHORE_HI, INLAND, L)
//   mountainAmp  = clamp01((L − LAND0)/INLAND) · province · softE   (province = UNWARPED Voronoi
//                  personality 0.05..1.2 — the primary mountain/plains driver; softE = erosion
//                  demoted to a 0.7..1.15 modulator. Redesigned 2026-07-10 from measurement.)
//   relief       = hill·HILL + amp·clamp01(amp)·macroProfile·MACRO + amp·meso·MESO
//                  − amp·4M(1−M)·ravines·RAVINE + detail·DETAIL·(0.3 + 0.7·amp)   (doc 13 M2/M3)
//   v            = y_base + landFactor·relief + detail·SEABED
//   v            = softKnee(v)  → clamp                    (knee = value-domain spline; the 655..708
//                                                          slide becomes a never-binding backstop)
// The old S-curve/jag-channel/plate-uplift/profile-var machinery is RETIRED (superseded).
// Vanilla graph parts that remain byte-frozen: caves, aquifers, veins, base_3d_noise, factor
// (×Sv band-thickness invariant), slides, depth gradient — the floating-block invariants hold.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
// The terrain MATH lives in pipeline-core.mjs, shared verbatim with the live preview tool
// (tools/preview/) so the preview can never drift from what this generator emits. This file
// keeps the Node-side concerns: vanilla fetching/transform, router surgery, debug-field
// mode, fail-loudly write(), and the world-box assertions.
import {
  DEFAULT_WORLD as WORLD, DEFAULT_TC as TC, LAND0,
  DEFAULT_CONTINENT_ROWS as CONTINENT_ROWS,
  G_SEA, OFFSET_BASE, makeAlgebra, makeKnee,
  mul, addN, addAll, clampN, fc2d, splineOn,
  WARP_NOISE_LAMBDA, RIDGED_NOISE_LAMBDA, HILL_NOISE_LAMBDA,
  refDx, refDz, voronoiNode as coreVoronoiNode, ridgedNode, ridgedNodeFor,
  buildSpecPipeline, crenellatedContinents as coreCrenellated,
  SPEC_NOISES, validateTc,
} from './pipeline-core.mjs';
// Rivers R2a (doc 14 §2): the ROUTING-SURFACE tree is built by the SAME function the R1
// reference tracer evaluates (tools/preview/rivers-r1.mjs), so the emitted registry DF and the
// JS parity reference can never drift. rivers-r1.mjs is environment-agnostic (pure data code).
import { routingSurfaceTree, DEFAULT_RC as RC } from './preview/rivers-r1.mjs';

const MCMETA = 'https://raw.githubusercontent.com/misode/mcmeta/1.21.1-data/data/minecraft/';
if (!process.argv[3]) {
  console.error('usage: node tools/generate-worldgen.mjs <cacheDir> <resourcesRoot>');
  process.exit(2);
}
const CACHE = join(process.argv[2], 'df-cache');
const RES = process.argv[3];
const NS = 'rise';

// WORLD, TC, LAND0, CONTINENT_ROWS come from pipeline-core.mjs (the single source of truth).

const { SEA, surfaceY, vOf, blocksToV } = makeAlgebra(WORLD, TC);

// ---- hard assertions (generator-specific world-box checks + the shared contract set) ----
{
  const height = WORLD.top - WORLD.minY;
  if (WORLD.minY % 16 !== 0 || WORLD.top % 16 !== 0 || height % 16 !== 0) throw new Error('bounds not /16');
  if (height % 8 !== 0) throw new Error('height not divisible by noise cell height 8');
  if (WORLD.minY < -2032 || WORLD.top > 2032 || height > 4064) throw new Error('outside 1.21.1 world bounds');
  // the top-slide end is TC-derived (= PEAK_CAP) since M1; the buffer floor is the spec's 60
  if (WORLD.top - TC.PEAK_CAP < 60) throw new Error(`peak buffer ${WORLD.top - TC.PEAK_CAP} < spec minimum 60`);
  if (WORLD.sr > 2 * WORLD.s + 1e-9) throw new Error('Sr violates slope-preserving cap');
  if (WORLD.minY > TC.SEA_FLOOR - 32) throw new Error('min_y must sit >=32 below SEA_FLOOR (spec assumption)');
  // the shared contract set (rows roundtrip, knee monotone/under-cap, worst-case incl. softE
  // 1.15, TC self-consistency, folding limit) — same code the preview runs live
  const { errs, worst } = validateTc(WORLD, TC, CONTINENT_ROWS);
  if (errs.length) throw new Error('validateTc: ' + errs.join(' | '));
  console.log(`spec pipeline: knee Y${TC.SOFT_KNEE_Y}..cap ${TC.PEAK_CAP}; worst-case pre-knee lands at Y ${worst.toFixed(1)} (< ${TC.PEAK_CAP})`);
  console.log(`static world: Sv=${WORLD.s} Sr=${WORLD.sr} Sh=${WORLD.sh}; range ${WORLD.minY}..${WORLD.top}; sea ${SEA}; floor row ${TC.SEA_FLOOR}`);
}

// Which noises may be horizontally stretched by the CLIMATE transform (biome channel).
const NOISE_SCALE_CURVE = new Map([
  ['minecraft:continentalness', 'sh'],
  ['minecraft:continentalness_large', 'sh'],
  ['minecraft:erosion', 'sh'],
  ['minecraft:erosion_large', 'sh'],
  ['minecraft:temperature', 'sh'],
  ['minecraft:temperature_large', 'sh'],
  ['minecraft:vegetation', 'sh'],
  ['minecraft:vegetation_large', 'sh'],
  ['minecraft:ridge', 'sr'],
]);

// LAND-BIOME SCALING — REMOVED 2026-07-10 (supersedes the 2026-07-08 landBiomeScale decision).
// The old two-field blend crossfaded temperature/vegetation between TWO DECORRELATED samplings
// of the same noise (xz_scale vs xz_scale/1.5), gated on the CRENELLATED continents field. The
// coast crenellation (±0.2·L @ λ1500, spec §2d-iii) swings the gate through its whole range in
// a few hundred blocks, so the effective temperature flip-flopped between independent fields at
// 1500-block scale near every coast/lowland — the field-reported "frozen biomes next to cherry
// and plains" patchwork. A single Sh-scaled climate field is continuous, so temperature bands
// transition strictly in order (hot → moderate → cold) at multi-km wavelength — the natural
// gradient. Land biomes are Sh× (8× as of 2026-07-13; was 12×) as a result; seam-free either way.

// tree builders, warp/voronoi/ridged nodes and the spec pipeline itself live in
// pipeline-core.mjs — shared verbatim with the preview tool. Thin NS/TC-bound wrappers:
const REF_DX = refDx(NS);
const REF_DZ = refDz(NS);
const voronoiNode = (output) => coreVoronoiNode(TC, output);

// ---- spec §2 emission — delegated to the shared core (preview parity by construction) ----
function emitSpecPipeline() {
  return buildSpecPipeline({ ns: NS, tc: TC, world: WORLD, rows: CONTINENT_ROWS, emit: write });
}
const crenellatedContinents = (baseRef) => coreCrenellated(TC, baseRef);

// ---- fetch machinery ----
mkdirSync(CACHE, { recursive: true });
async function fetchCached(remotePath, localName) {
  const local = join(CACHE, localName);
  if (existsSync(local)) return readFileSync(local, 'utf8');
  const res = await fetch(MCMETA + remotePath, { headers: { 'User-Agent': 'ArcheanRise-worldgen/3.0' } });
  if (!res.ok) throw new Error(`fetch ${remotePath}: HTTP ${res.status}`);
  const text = await res.text();
  writeFileSync(local, text);
  return text;
}
const dfCache = new Map();
async function fetchDf(path) {
  if (dfCache.has(path)) return dfCache.get(path);
  const text = await fetchCached('worldgen/density_function/' + path + '.json', path.replaceAll('/', '_') + '.json');
  const json = JSON.parse(text);
  dfCache.set(path, json);
  return json;
}

const DF_REF_KEYS = new Set(['argument', 'argument1', 'argument2', 'input', 'when_in_range',
  'when_out_of_range', 'shift_x', 'shift_y', 'shift_z', 'coordinate',
  'final_density', 'initial_density_without_jaggedness', 'barrier', 'fluid_level_floodedness',
  'fluid_level_spread', 'lava', 'temperature', 'vegetation', 'continents', 'erosion', 'depth',
  'ridges', 'preliminary_surface_level', 'vein_toggle', 'vein_ridged', 'vein_gap']);
const NOISE_ARG_TYPES = new Set(['minecraft:shift_a', 'minecraft:shift_b', 'minecraft:shift']);

const gradientLog = new Map();
const scaledNoiseLog = new Map();
const referenced = new Set();

function transform(node, w, parentType, dfPath) {
  if (Array.isArray(node)) return node.map(v => transform(v, w, parentType, dfPath));
  if (node === null || typeof node !== 'object') return node;
  const out = {};
  const type = node.type ?? parentType;
  for (const [key, value] of Object.entries(node)) {
    if (typeof value === 'string' && value.startsWith('minecraft:') && DF_REF_KEYS.has(key)
        && !NOISE_ARG_TYPES.has(node.type ?? '')) {
      const path = value.substring('minecraft:'.length);
      referenced.add(path);
      out[key] = `archean_rise:${NS}/${path}`;
      continue;
    }
    out[key] = transform(value, w, type, dfPath);
  }
  if ((out.type === 'minecraft:noise' || out.type === 'minecraft:shifted_noise')
      && typeof out.noise === 'string' && NOISE_SCALE_CURVE.has(out.noise)) {
    if (out.y_scale !== 0) {
      throw new Error(`refusing to stretch 3-D noise ${out.noise} (y_scale=${out.y_scale})`);
    }
    const curve = NOISE_SCALE_CURVE.get(out.noise);
    out.xz_scale = out.xz_scale / w[curve];
    scaledNoiseLog.set(out.noise, curve);
  }
  if (out.type === 'minecraft:y_clamped_gradient') {
    const key = `${dfPath ?? 'router'}: ${out.from_y}..${out.to_y}`;
    const inRouter = dfPath == null;
    const inDepth = dfPath === 'overworld/depth';
    if (inRouter && out.from_y === -64 && out.to_y === -40) {
      out.from_y = w.minY;
      out.to_y = w.minY + Math.round(24 * w.s);
      gradientLog.set(key, 'bottom-slide (floor-anchored)');
    } else if (inRouter && out.from_y === 240 && out.to_y === 256) {
      // TC-derived (M1, doc 13): the cap is a CONTRACT knob, no longer tied to the vanilla
      // 193-anchor scaling. Band width stays 53 blocks (keep in sync: NaturalSurface
      // TOP_SLIDE_END/SPAN — the Java probe's slide-corrected threshold).
      out.from_y = TC.PEAK_CAP - 53;
      out.to_y = TC.PEAK_CAP;
      gradientLog.set(key, 'top-slide (backstop above the soft knee)');
    } else if (inDepth && out.from_y === -64 && out.to_y === 320) {
      out.from_y = Math.round(SEA - 127 * w.s);
      out.to_y = Math.round(SEA + 257 * w.s);
      gradientLog.set(key, 'depth (sea-anchored)');
    } else {
      gradientLog.set(key, 'UNTOUCHED (byte-frozen)');
    }
  }
  return out;
}

function write(rel, json) {
  // Fail-loudly contract: JSON.stringify silently turns NaN/Infinity into null and DROPS
  // undefined-valued keys (e.g. a deleted TC key feeding octaves would fall back to the
  // codec default — a silent terrain change). No emitted worldgen JSON legitimately
  // contains null, so reject all three everywhere before serializing.
  (function assertClean(v, path) {
    if (v === undefined || v === null) throw new Error(`write ${rel}: ${path} is ${v}`);
    if (typeof v === 'number' && !Number.isFinite(v)) throw new Error(`write ${rel}: ${path} is non-finite (${v})`);
    if (Array.isArray(v)) v.forEach((e, i) => assertClean(e, `${path}[${i}]`));
    else if (typeof v === 'object') for (const k of Object.keys(v)) assertClean(v[k], `${path}.${k}`);
  })(json, 'root');
  const file = join(RES, rel);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify(json, null, 2) + '\n');
}

const baseSettings = JSON.parse(await fetchCached('worldgen/noise_settings/overworld.json', 'vanilla-overworld-noise.json'));
const baseDimType = JSON.parse(await fetchCached('dimension_type/overworld.json', 'vanilla-overworld-dimtype.json'));

// 1. noise settings: transform the router (discovers root DF references)
// temperature/vegetation stay the SINGLE Sh-scaled fields the transform produced (the two-field
// land blend was removed 2026-07-10 — see the LAND-BIOME SCALING note above).
const settings = transform(structuredClone(baseSettings), WORLD, null, null);
// SPEC §1 GLOBAL WATER RULE + RIVERS R2c CORRIDOR WATER — the aquifer "stepped elevated pools"
// trio (doc 11 §5a; the load-bearing mechanism read from decompiled 1.21.1 Aquifer/NoiseChunk in
// docs/research/10 §1.2 and re-verified here against the loom-cache sources). Three router slots,
// each composed OVER the vanilla aquifer and CORRIDOR-MASKED so non-river aquifers stay byte-exact.
// The 2-D masks come from the river_water DF (RiverWaterDensityFunction), reading the SAME graph +
// carve the R2b river_carve DF uses; away from rivers they return the neutral value (0 / sentinel).
{
  const riverWater = (mode) => ({ type: 'archean_rise:river_water', mode });
  // Emit the 2-D masks (river_water DF) → one carver query per column. corridor_mask still scopes the
  // base_3d bed-flatten below; water_level/apron_mask stay emitted (valid DFs; the modes remain in the
  // registered river_water DF) but are no longer referenced by the router.
  //
  // R2 QUIESCENCE REWORK (2026-07-12, spec §7.3/§7.6): the aquifer "elevated stepped pools" trio —
  // (b) prelim raise, (c) corridor-wet floodedness, (c) spread decode — is RETIRED. That trio filled
  // elevated river corridors + their apron with INFINITE-SOURCE aquifer water whose surface sat at/
  // above the carved spill crests, so once the chunks actually BLOCK-TICKED (normal play) every pool
  // poured over its lip forever and the falls spread unconfined — the confirmed Gate-R2 flood
  // (dual-validated + tie-breaker: a steep 1212 cascade went 0→~75k non-source blocks in 90s). All
  // prior "quiescent" readings were on non-ticking pregen chunks. Elevated river water is now placed
  // ENTIRELY at the features stage (RiverPools + RiverFalls), in fully-contained sealed pools +
  // confined falls whose containment is verified against the REAL manifested terrain — the one place
  // quiescence can be guaranteed. Only the §1 belowSea sea-pin survives in the router: it is quiescent
  // and correct (sub-sea columns flood to 63 — oceans, estuaries, sea-level river mouths).
  write(`data/archean_rise/worldgen/density_function/${NS}/rivers/water_level.json`, fc2d(riverWater('water_level')));
  write(`data/archean_rise/worldgen/density_function/${NS}/rivers/corridor_mask.json`, fc2d(riverWater('corridor_mask')));
  write(`data/archean_rise/worldgen/density_function/${NS}/rivers/apron_mask.json`, fc2d(riverWater('apron_mask')));
  const RW_APR = `archean_rise:${NS}/rivers/apron_mask`;

  // (§1 GLOBAL WATER RULE — KEPT) + APRON-DRY (KEPT — it is a QUIESCENCE aid, not part of the retired
  // elevated-pool trio). Floodedness:
  //   1. DRY the whole river apron to 0.2 (<0.4 = aquifer OFF) so the vanilla aquifer places NO water in
  //      the cavities/caves near a river — without this the apron caves take on vanilla aquifer water
  //      that flows/settles when ticked (a terrain-side flood revealed once the elevated-pool trio is
  //      retired; measured ~5k flowing over a steep cascade box). Elevated corridors thus generate DRY
  //      and the features stage (RiverPools) fills them with sealed, contained source pools;
  //   2. then §1 belowSea PINS sub-sea columns to 1.0 (>0.8 = sea pin) so oceans/estuaries/coastal-river
  //      mouths fill to 63 (belowSea wins over the apron-dry where the nominal surface is sub-sea).
  let f = settings.noise_router.fluid_level_floodedness;     // vanilla aquifer_fluid_level_floodedness
  f = addN(f, mul(RW_APR, addN(0.2, mul(-1.0, f))));         // apron → 0.2 (dry the near-river cavities)
  const belowSea = splineOn(`archean_rise:${NS}/spec/offset_preknee`, [
    [vOf(59.0), 1.0], // fully pinned when the nominal surface is below ~59 (the Stage-B-proven §1 gate)
    [vOf(63.5), 0.0], // natural aquifer behavior resumes above the waterline
  ]);
  f = addN(f, mul(belowSea, addN(1.0, mul(-1.0, f))));
  settings.noise_router.fluid_level_floodedness = f;
  // RETIRED (the elevated-pool trio that caused the RIVER flood): the prelim raise
  // (initial_density_without_jaggedness) + corridor-wet floodedness + spread decode are GONE — they
  // filled elevated corridors with uncontained infinite-source aquifer water at L. initial_density and
  // fluid_level_spread are LEFT VANILLA; the corridor water is now placed contained at the features stage.
}
settings.noise = { ...settings.noise, min_y: WORLD.minY, height: WORLD.top - WORLD.minY };
write(`data/archean_rise/worldgen/noise_settings/${NS}.json`, settings);

// 1b. spec noises (parameters shared with the preview via pipeline-core SPEC_NOISES)
for (const [id, params] of Object.entries(SPEC_NOISES)) {
  write(`data/archean_rise/worldgen/noise/${id.split(':')[1]}.json`, params);
}
// runtime-resolved by BiomeBorderBlend (RandomState.getOrCreateNoise), not referenced from
// any DF JSON — emitted here so a clean-and-regenerate can never silently drop it.
write('data/archean_rise/worldgen/noise/biome_border_warp.json', { firstOctave: -8, amplitudes: [1.0, 0.5] });

// 2. density-function graph
const done = new Set();
while (true) {
  const pending = [...referenced].filter(p => !done.has(p));
  if (pending.length === 0) break;
  for (const path of pending) {
    done.add(path);
    const df = await fetchDf(path);
    const raw = structuredClone(df);
    if (path === 'overworld/sloped_cheese') {
      // The jag channel is retired (relief lives in the offset pipeline; the term multiplied
      // by a constant-0 jaggedness but vanilla still EVALUATED the jagged noise per sample).
      // Drop add(depth, mul(jaggedness, ...)) -> depth. Asserted against the vanilla shape so
      // an upstream change fails loudly instead of silently double-counting relief.
      const inner = raw?.argument1?.argument2?.argument; // mul(add(depth, jagTerm), factor)
      const dj = inner?.argument1;
      if (dj?.type !== 'minecraft:add' || dj.argument2?.type !== 'minecraft:mul'
          || dj.argument2.argument1 !== 'minecraft:overworld/jaggedness') {
        throw new Error('sloped_cheese: vanilla shape changed — jag-term strip needs review');
      }
      inner.argument1 = dj.argument1; // keep the depth reference; the jag term is gone
    }
    let copied = transform(raw, WORLD, null, path);
    if (path === 'overworld/sloped_cheese') {
      // R2b-3: SUPPRESS base_3d texture inside river corridors. base_3d rides on top of the
      // offset-derived surface (add at argument2); the R2b carve flattens the OFFSET to
      // reachSurface−depth, but base_3d then bumps the bed ±several blocks, lifting marginal
      // reaches above the water level → dry channels (the R2c coverage gap). Multiplying base_3d
      // by (1 − corridor_mask) zeroes the texture in the channel core (flat bed at
      // reachSurface−depth), fades it over the halo, and is a no-op outside (mask 0) and on
      // vanilla worlds (river_water is rise-only). Asserted so an upstream shape change fails loud.
      if (copied.type !== 'minecraft:add'
          || copied.argument2 !== 'archean_rise:rise/overworld/base_3d_noise') {
        throw new Error('sloped_cheese: base_3d not at argument2 — corridor suppression needs review');
      }
      copied.argument2 = mul('archean_rise:rise/overworld/base_3d_noise',
          addN(1.0, mul(-1.0, 'archean_rise:rise/rivers/corridor_mask')));
    }
    if (path === 'overworld/factor') {
      // band-thickness invariant: gradient slope shrinks by 1/Sv, factor grows by Sv.
      copied = { type: 'minecraft:mul', argument1: copied, argument2: WORLD.s };
    } else if (path === 'overworld/offset') {
      // SPEC PIPELINE replaces vanilla's offset tree entirely (§2) — the -128 ocean floor,
      // beach band, knee and relief stack are authored, not inherited.
      // DIAGNOSTIC MODE (--debug-field=ridged|hill|detail): terrain height BECOMES the raw
      // field (Y = 100 + field*300 for ridged [0,1]; Y = 250 + field*150 for signed fields),
      // so one pregen'd world yields the field's exact percentile distribution from its
      // heightmaps — the measurement the shaping curves must be designed against.
      const dbg = process.argv.find(a => a.startsWith('--debug-field='))?.split('=')[1];
      if (dbg) {
        const fieldNode = {
          ridged: () => ridgedNode(NS, TC),
          macro: () => ridgedNodeFor(NS, 'archean_rise:ridged_macro', 5, TC.MACRO_FREQ),
          ravraw: () => ridgedNodeFor(NS, 'archean_rise:ravine', 4, TC.RAVINE_FREQ),
          hill: () => ({ type: 'minecraft:shifted_noise', noise: 'archean_rise:hill', shift_x: 0.0, shift_y: 0.0, shift_z: 0.0, xz_scale: HILL_NOISE_LAMBDA * TC.BASE_FREQ, y_scale: 0.0 }),
          detail: () => ({ type: 'minecraft:shifted_noise', noise: 'archean_rise:detail', shift_x: 511.0, shift_y: 0.0, shift_z: -511.0, xz_scale: HILL_NOISE_LAMBDA * TC.DETAIL_FREQ, y_scale: 0.0 }),
          mamp: () => `archean_rise:${NS}/spec/mountain_amp`,   // the composed gate [0, ~1.2]
          vedge: () => voronoiNode('edge'),
          vcell: () => voronoiNode('cell_id'),
          land: () => `archean_rise:${NS}/spec/land_factor`,    // smoothstep gate [0, 1]
          cont: () => `archean_rise:${NS}/overworld/continents`, // crenellated L
          eros: () => `archean_rise:${NS}/overworld/erosion`,
        }[dbg];
        if (!fieldNode) throw new Error(`unknown --debug-field ${dbg}`);
        const unit = dbg === 'ridged' || dbg === 'macro' || dbg === 'ravraw' || dbg === 'mamp' || dbg === 'land' || dbg === 'vedge';
        const base = unit ? 100 : 250;
        const scale = (unit && dbg !== 'vedge') ? 300 : 150;
        console.log(`DEBUG-FIELD MODE: terrain = ${dbg}; raw = (heightmap - ${base}) / ${scale}`);
        emitSpecPipeline(); // still emit the pipeline files (references stay valid)
        copied = fc2d(addN(OFFSET_BASE, addN(vOf(base), mul(fieldNode(), blocksToV(scale)))));
      } else {
        copied = emitSpecPipeline();
      }
    } else if (path === 'overworld/continents') {
      write(`data/archean_rise/worldgen/density_function/${NS}/overworld/continents_base.json`, copied);
      copied = crenellatedContinents(`archean_rise:${NS}/overworld/continents_base`);
    }
    write(`data/archean_rise/worldgen/density_function/${NS}/${path}.json`, copied);
  }
}
console.log(`${done.size} density functions copied (+ spec pipeline files, continents_base, warp trees)`);

// 2b. RIVERS R2a ROUTING SURFACE (doc 14 §0/§2 R2a; spec §7.1): the river tracer's routing
// field — §2d continents-spline baseline in plain BLOCK units + K_ROUTE·MACRO_RELIEF·
// (mAmp·min(mAmp,1))·2-octave macro ridged — emitted as a real registry DF so the in-game
// Java RiverGraph evaluates the EXACT tree the JS reference walks (rivers-r1.mjs
// routingSurfaceTree, imported above — never a hand-written copy). TERRAIN-INERT: nothing in
// the noise router references it; the emission only adds a registry entry for the mod to read.
write(`data/archean_rise/worldgen/density_function/${NS}/rivers/routing.json`,
    routingSurfaceTree(TC, RC, CONTINENT_ROWS));
console.log(`rivers routing surface emitted (${NS}/rivers/routing — K_ROUTE ${RC.K_ROUTE} x MACRO_RELIEF ${TC.MACRO_RELIEF})`);

// 3. dimension type
const dim = structuredClone(baseDimType);
dim.min_y = WORLD.minY;
dim.height = WORLD.top - WORLD.minY;
dim.logical_height = WORLD.top - WORLD.minY;
write('data/archean_rise/dimension_type/overworld_rise.json', dim);

// 4. world preset
write('data/archean_rise/worldgen/world_preset/archean_rise.json', {
  dimensions: {
    'minecraft:overworld': {
      type: 'archean_rise:overworld_rise',
      generator: {
        type: 'minecraft:noise',
        biome_source: { type: 'minecraft:multi_noise', preset: 'minecraft:overworld' },
        settings: `archean_rise:${NS}`,
      },
    },
    'minecraft:the_end': {
      type: 'minecraft:the_end',
      generator: { type: 'minecraft:noise', biome_source: { type: 'minecraft:the_end' }, settings: 'minecraft:end' },
    },
    'minecraft:the_nether': {
      type: 'minecraft:the_nether',
      generator: {
        type: 'minecraft:noise',
        biome_source: { type: 'minecraft:multi_noise', preset: 'minecraft:nether' },
        settings: 'minecraft:nether',
      },
    },
  },
});

// 5b. ORE PHASE-0 REPAIR (docs/research/05 §4, DECISIONS 2026-07-11): the six vanilla ores
// whose above_bottom(0) anchors DILUTE on the 1024-block world are replaced by re-anchored
// copies at PER-Y-SLICE YIELD NEUTRALITY: bottom = absolute(-176) (the diamond trapezoid's
// top — continuous coverage, no barren gap, and the deliberately-rich -256..-176 floor band
// stays diamond/redstone territory). COUNT CONVENTION (fixed 2026-07-11): minecraft:uniform
// anchors are INCLUSIVE, so a band [bottom, top] spans (top - bottom + 1) Y-slices — vanilla
// [-64, top] has top+65 slices, the AR band [-176, top] has top+177; count =
// round(vanillaCount * (top+177)/(top+65)), gated to ±10% per-slice deviation (the doc-05
// acceptance gate — the emitter THROWS if a count lands outside it). The first cut's exclusive
// ratio (top+176)/(top+64) was an off-by-one that put tuff at 6 = +10.2%/slice, outside the
// gate; the exact convention moves tuff 6->5 and lapis_buried 8->7 (see DECISIONS).
// Each copy carries archean_rise:in_archean_generator so the biome-API injection is inert
// outside Archean worlds; the vanilla originals are suppressed in Archean worlds by OreGate/
// PlacedFeatureMixin. Keep the list in sync with OreGate.SUPPRESSED_IDS + ArcheanRiseFabric.
//
// BIOME PARITY (DECISIONS 2026-07-11): vanilla places ore_infested ONLY in the ten
// mountain-family biomes (BiomeDefaultFeatures.addInfestedStone call sites) and ore_clay ONLY
// in lush_caves (addLushCavesSpecialOres); the other four are overworld-wide
// (addDefaultOres/addDefaultUndergroundVariety). The replacements must inject into EXACTLY
// those biomes or the world-wide rate multiplies by the biome count (infested was ~13x).
// Sets derived from the 1.21.1 jar's shipped data/minecraft/worldgen/biome/*.json and
// cross-checked against the disassembled OverworldBiomes call sites (NOT windswept_savanna);
// explicit ID lists, not tags, for exact parity with no tag-drift. Keep in sync with
// ArcheanRiseFabric.INFESTED_BIOMES (the code-side Fabric mirror of this split).
const INFESTED_BIOMES = [
  'minecraft:windswept_hills', 'minecraft:windswept_gravelly_hills', 'minecraft:windswept_forest',
  'minecraft:meadow', 'minecraft:cherry_grove', 'minecraft:grove', 'minecraft:snowy_slopes',
  'minecraft:frozen_peaks', 'minecraft:jagged_peaks', 'minecraft:stony_peaks',
];
const ORE_BOTTOM = -176;      // absolute re-anchor (the diamond trapezoid's top)
const VANILLA_BOTTOM = -64;   // vanilla overworld min_y (the above_bottom(0) resolution)
const ORE_PHASE0 = [
  // name, vanilla top (absolute, inclusive), vanilla count, biome scope for the NeoForge modifier
  ['ore_redstone', 15, 4, 'overworld'],            // -> 10 (+4.2%/slice)
  ['ore_tuff', 0, 2, 'overworld'],                 // -> 5  (-8.2%/slice)
  ['ore_iron_small', 72, 10, 'overworld'],         // -> 18 (-1.0%/slice)
  ['ore_lapis_buried', 64, 4, 'overworld'],        // -> 7  (-6.3%/slice)
  ['ore_infested', 63, 14, INFESTED_BIOMES],       // -> 26 (-1.0%/slice)
  ['ore_clay', 256, 46, ['minecraft:lush_caves']], // -> 62 (-0.1%/slice)
];
function oreCount(name, top, vanillaCount) {
  const vanillaSlices = top - VANILLA_BOTTOM + 1;
  const arSlices = top - ORE_BOTTOM + 1;
  const count = Math.round(vanillaCount * arSlices / vanillaSlices);
  const dev = (count / arSlices) / (vanillaCount / vanillaSlices) - 1;
  if (Math.abs(dev) > 0.10) {
    throw new Error(`${name}: count ${count} is ${(dev * 100).toFixed(1)}%/slice from neutral — outside the ±10% gate`);
  }
  console.log(`ore ${name}: ${vanillaCount} @ [${VANILLA_BOTTOM},${top}] -> ${count} @ [${ORE_BOTTOM},${top}] (${(dev * 100).toFixed(1)}%/slice)`);
  return count;
}
for (const [name, top, vanillaCount] of ORE_PHASE0) {
  write(`data/archean_rise/worldgen/placed_feature/${name}.json`, {
    feature: `minecraft:${name}`, // the vanilla CONFIGURED feature — geometry untouched (Phase 2 owns geometry)
    placement: [
      { type: 'archean_rise:in_archean_generator' },
      { type: 'minecraft:count', count: oreCount(name, top, vanillaCount) },
      { type: 'minecraft:in_square' },
      { type: 'minecraft:height_range', height: {
        type: 'minecraft:uniform',
        min_inclusive: { absolute: ORE_BOTTOM },
        max_inclusive: { absolute: top },
      } },
      { type: 'minecraft:biome' },
    ],
  });
}
// NeoForge injection is data-driven (Fabric's is code-side in the entrypoint — keep the biome
// split identical): one modifier for the overworld-wide four, one each for the biome-scoped
// two (a JSON list of biome ids is a valid HolderSet where a tag string is).
write('data/archean_rise/neoforge/biome_modifier/ore_phase0.json', {
  type: 'neoforge:add_features',
  biomes: '#minecraft:is_overworld',
  features: ORE_PHASE0.filter(([, , , scope]) => scope === 'overworld').map(([name]) => `archean_rise:${name}`),
  step: 'underground_ores',
});
for (const [name, , , scope] of ORE_PHASE0) {
  if (scope === 'overworld') continue;
  write(`data/archean_rise/neoforge/biome_modifier/ore_phase0_${name.replace(/^ore_/, '')}.json`, {
    type: 'neoforge:add_features',
    biomes: scope,
    features: [`archean_rise:${name}`],
    step: 'underground_ores',
  });
}

// 5. world-preset tag
write('data/minecraft/tags/worldgen/world_preset/normal.json', {
  replace: false,
  values: ['archean_rise:archean_rise'],
});

console.log('\ny_clamped_gradient census:');
for (const [pair, action] of gradientLog) console.log(`  ${pair} -> ${action}`);
console.log('\nstretched noises (everything else byte-frozen):');
for (const [noise, curve] of scaledNoiseLog) console.log(`  ${noise} -> ${curve}`);
console.log('\nDONE');
