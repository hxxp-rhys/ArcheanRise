// RIVERS R1 — the spec §7.0–7.2 trace-based river graph, previewer-only (the spec hard-gates
// R1 before ANY mod code). Environment-agnostic: consumed by the preview UI overlay and by the
// Node gates runner (rivers-r1-gates.mjs).
//
// Determinism model (spec §7.0 "any chunk can reconstruct the identical graph … in any order"):
// two-phase, exact by construction, with a strict purity ladder:
//   Phase 1 — WALK: each source's trace is a PURE function of (seed, knobs, source). The walk
//   never consults other paths; it MAY consult its own earlier segments (self-contact clipping
//   — still pure). Displacement is capped at REACH_MAX, and a node may overshoot the cap by at
//   most one step, so every trace lies within TRACE_REACH = REACH_MAX + max(RIVER_STEP,
//   LAKE_SEARCH_MAX) of its source. Two traces can therefore only come within JOIN_RADIUS when
//   their sources are within INTERACT_R = 2·TRACE_REACH + JOIN_RADIUS — the pad inequality is
//   CODED below, not hand-derived.
//   Phase 2 — CLIP (1-hop pure): a path's realized geometry is its trace truncated at the
//   first segment-to-segment proximity (≤ JOIN_RADIUS) with any canonically-earlier source's
//   UNCLIPPED trace (canonical order: cellZ, cellX, idx). Clipping against unclipped traces
//   keeps every clip a pure function of a FIXED joinPad neighborhood — no clip-chain — and
//   proximity trimming (junction backed off 2 blocks) means realized segments of different
//   paths never intersect.
//   Phase 3 — LABELS & WATER (2-hop pure): join validity and junction water consume the
//   TARGET's own 1-hop-pure clip info and prefix water. Consuming 1-hop-pure facts adds one
//   bounded pad hop and never recurses, because labels and water NEVER feed back into anyone's
//   geometry or clip decisions. A join whose junction lands beyond the target's own realized
//   extent (the target was clipped earlier — a "phantom reach") is reclassified as a flagged
//   terminal pond: hydrologically the tributary's water pools where its channel ends (§7.1's
//   endorheic terminal); R2 may bridge or prune these (fraction reported by the gate battery).
//
// Routing (spec §7.1): candidates are scored on the ROUTING SURFACE — the §2d spline baseline
// plus a low-octave macro proxy — NEVER the final heightfield (no descent on final heights, no
// global accumulation). Water level per node (spec §7.2) = max(SEA_LEVEL, min(prev, routing,
// designed terrain)) — a running min floored at sea level; sub-sea inland dips are §1 global
// water. Where a tributary arrives BELOW its target's water at the junction, its trailing
// reach is raised to the target's level (backwater pool — max(w, targetW) preserves the
// running-min monotonicity), so junctions are network-consistent.
//
// FLAT REACHES (spec §7.2 "group nodes into reaches (pools) … consecutive reaches step down";
// R2b-4a): the per-node running min is then QUANTIZED into a staircase of flat pools. A pure
// downstream pass holds a flat reach `level` and steps DOWN to the running min whenever it has
// descended ≥ REACH_STEP below the held level; node.waterY is the stepped (flat-per-reach)
// level. node.reachId (per-path monotone pool index), node.stepDrop (the drop at a step node)
// and node.lip (a step whose drop ≥ LIP_STEP = WATERFALL_MIN_DROP) record the steps. The
// quantization is CAUSAL — the level at node i depends only on the path's own running-min prefix
// raw[0..i] — so it is a pure prefix property (cached alongside the raw prefix) and the R1
// purity/determinism model is preserved unchanged. Junction backwater now reconciles against the
// target's STEPPED prefix level (the tributary pools into the target's flat reach — still 1-hop
// pure; the target's own backwater raise never chains in). GEOMETRY (node x/z, clip, joins, lake
// positions/flags) is byte-identical to R1 — only waterY and the step metadata change; the carve
// (R2b-4b), aquifer (R2c) and waterfalls (R2b-4d) consume this stepped water and re-gate downstream.
import {
  DEFAULT_TC, splineOn, mul, addN, minN, fc2d, ridgedNodeFor,
} from '../pipeline-core.mjs';

// ---- river config (spec §7.5 is amended to these values; preview-knobable in a later pass) --
export const DEFAULT_RC = {
  RIVER_CELL: 4096,        // source-cell size (blocks)
  SOURCES_PER_CELL: 3,     // 0..N jittered candidate sources per cell
  MIN_SOURCE_Y: 140,       // sources only on highland (routing baseline)
  RIVER_STEP: 112,         // trace step length (blocks)
  RIVER_MAX_LEN: 12000,    // trace budget (along-path blocks; a final lake jump may overshoot
                           // by ≤ LAKE_SEARCH_MAX — the gates use the exact bound)
  REACH_MAX: 5000,         // displacement cap from source — the determinism pad driver;
                           // deep-inland traces end as FLAGGED (truncated) endorheic lakes
  JOIN_RADIUS: 48,         // segment-to-segment confluence distance
  SELF_JOIN_RADIUS: 24,    // own-path proximity that closes a loop (below meander spacing;
                           // prevents self-crossings — the loop drains into a terminal pond)
  K_ROUTE: 0.5,            // low-octave macro contribution to the routing surface
  INERTIA: 14,             // heading persistence (blocks of routing-height equivalent)
  MEANDER: 10,             // deterministic heading wobble amplitude (same units)
  LIP_STEP: 4,             // per-step drop that marks a waterfall lip (spec WATERFALL_MIN_DROP)
  REACH_STEP: 3,           // flat-reach quantization (spec §7.2, R2b-4a): the running min must
                           // descend this far below a reach's flat level before the water steps
                           // down to a new reach. = the maximum pool-dam perch (a pool holds flat
                           // until it would perch REACH_STEP above the falling terrain, then steps
                           // to meet it). Bigger = longer pools + taller dams/falls; smaller =
                           // frequent small cascades. TUNED to 3 (DECISIONS "RIVERS R2b-4a", from a
                           // REACH_STEP sweep on 6 seeds): 3 is the largest value that still yields
                           // the spec's RAPIDS_DROP class (steps are ≥ REACH_STEP, so only ≤3
                           // produces 2..3-block rapids; ≥4 makes every step a waterfall). Gives
                           // ~3-block natural pool sills, ~335-blk pools (~2.5 reaches/km), and
                           // preserves the big cliff falls. Gate-bounded by MAX_DAM_H=8
                           // (rivers-r1-gates): containment fails only near REACH_STEP≈26.
  LAKE_SEARCH_MAX: 1024,   // how far to look for a lake outlet before flagging endorheic
  LAKE_RING_GROW: 1.6,     // geometric radial growth of the outlet search (192·1.6^k, capped)
  LAKE_RING_DIRS: 8,       // outlet-search directions per ring (lake searches dominate the
                           // eval budget on basin-heavy seeds — keep this lean)
  RING_DIRS: 10,           // candidate directions per step
  FLAT_EPS: 1.0,           // routing rise tolerated before a step counts as uphill (lets rivers
                           // cross plateau flats instead of laking every step)
  STEEP_FAST: 2.0,         // straight-ahead drop (blocks/step) that skips the candidate ring —
                           // steep mountain runs go straight; the ring still runs on flats,
                           // where meanders and lake decisions live. Pure perf lever.
  SEA_SURFACE: 63,         // water-level floor — mirrors the world contract's SEA_LEVEL 63
};

// Direction tables FROZEN as double literals (R2a bit-parity rule, doc 14 §0): the Java port
// copies these exact values — neither implementation may call cos/sin at runtime, whose
// rounding differs across engines. Derived once from the values the R1-stamped battery ran on.
export const RING_TABLE = [
  [1, 0],
  [0.8090169943749475, 0.5877852522924731],
  [0.30901699437494745, 0.9510565162951535],
  [-0.30901699437494734, 0.9510565162951536],
  [-0.8090169943749473, 0.5877852522924732],
  [-1, 1.2246467991473532e-16],
  [-0.8090169943749475, -0.587785252292473],
  [-0.30901699437494756, -0.9510565162951535],
  [0.30901699437494723, -0.9510565162951536],
  [0.8090169943749473, -0.5877852522924734],
];      // RING_DIRS candidate directions
export const LAKE_TABLE = [
  [1, 0],
  [0.7071067811865476, 0.7071067811865475],
  [6.123233995736766e-17, 1],
  [-0.7071067811865475, 0.7071067811865476],
  [-1, 1.2246467991473532e-16],
  [-0.7071067811865477, -0.7071067811865475],
  [-1.8369701987210297e-16, -1],
  [0.7071067811865474, -0.7071067811865477],
];      // LAKE_RING_DIRS outlet-search directions

// splitmix64 (exact VoronoiField family — deterministic, seed-pure)
const U64 = (1n << 64n) - 1n;
function sm64(z) {
  z = (z + 0x9e3779b97f4a7c15n) & U64;
  z = ((z ^ (z >> 30n)) * 0xbf58476d1ce4e5b9n) & U64;
  z = ((z ^ (z >> 27n)) * 0x94d049bb133111ebn) & U64;
  return (z ^ (z >> 31n)) & U64;
}
const u01 = (h) => Number(h >> 11n) * Math.pow(2, -53);

/** The routing-surface DF tree: §2d baseline + K_ROUTE·gated low-octave macro (smooth proxy).
 *  The macro term is gated by mAmp·min(mAmp,1) — the same amplitude gate the terrain uses —
 *  so rivers seek the valleys BETWEEN expressed massifs (spec §7.1 intent; plain mountainAmp
 *  would bias routing inside provinces whose relief is never actually expressed). */
export function routingSurfaceTree(tc, rc, rows) {
  const ns = 'rise';
  const L = `archean_rise:${ns}/overworld/continents`;
  const yBase = splineOn(L, rows.map(([l, y]) => [l, y])); // plain BLOCK units (not v-units)
  const macroLow = ridgedNodeFor(ns, 'archean_rise:ridged_macro', 2, tc.MACRO_FREQ);
  const mAmp = `archean_rise:${ns}/spec/mountain_amp`;
  const gated = mul(mul(mAmp, minN(mAmp, 1.0)), macroLow);
  return fc2d(addN(yBase, mul(rc.K_ROUTE * tc.MACRO_RELIEF, gated)));
}

// segment-to-segment closest approach: squared distance, params on BOTH segments, and the
// closest point on the first segment
function segSegClosest(ax, az, bx, bz, cx, cz, dx, dz) {
  const ux = bx - ax, uz = bz - az, vx = dx - cx, vz = dz - cz, wx = ax - cx, wz = az - cz;
  const a = ux * ux + uz * uz, b = ux * vx + uz * vz, c = vx * vx + vz * vz;
  const d = ux * wx + uz * wz, e = vx * wx + vz * wz;
  const den = a * c - b * b;
  let s = den > 1e-9 ? (b * e - c * d) / den : 0;
  s = Math.max(0, Math.min(1, s));
  let t = c > 1e-9 ? (b * s + e) / c : 0;
  t = Math.max(0, Math.min(1, t));
  s = a > 1e-9 ? Math.max(0, Math.min(1, (b * t - d) / a)) : 0;
  const px = ax + s * ux, pz = az + s * uz;
  const qx = cx + t * vx, qz = cz + t * vz;
  return { d2: (px - qx) * (px - qx) + (pz - qz) * (pz - qz), s, t, px, pz };
}

/**
 * Create the river realizer for (engine, seed, tc, rc, rows).
 * realize(minCellX, minCellZ, maxCellX, maxCellZ) → { paths, lakes } — paths sourced within
 * the region + visPad, byte-identical across any query shape containing them.
 */
export function createRivers({ engine, seed, tc = DEFAULT_TC, rc = DEFAULT_RC, rows }) {
  const routeAtRaw = engine.evalTree(routingSurfaceTree(tc, rc, rows));
  // 64-block memo grid: the routing surface is smooth by construction (baseline + 2-octave
  // macro at λ4800) and ring/lake sampling revisits positions heavily — the memo is the
  // difference between ~13 and <5 ms/cell. Shared across queries (values are pure).
  // Key: qx·2^21 + qz is collision-free for |q| < 2^20, i.e. |x|,|z| < 67M — beyond the world
  // border (a small-multiplier key collided at ±6.4M and made memo values query-ORDER-dependent).
  const memo = new Map();
  const routeAt = (x, z) => {
    const qx = Math.round(x / 64), qz = Math.round(z / 64);
    const k = qx * 2097152 + qz;
    let v = memo.get(k);
    if (v === undefined) {
      v = routeAtRaw(qx * 64, qz * 64);
      memo.set(k, v);
    }
    return v;
  };
  const seedN = BigInt(seed);

  // ---- pad derivation (CODED inequality — do not hand-derive; see header) ----
  const TRACE_REACH = rc.REACH_MAX + Math.max(rc.RIVER_STEP, rc.LAKE_SEARCH_MAX);
  const INTERACT_R = 2 * TRACE_REACH + rc.JOIN_RADIUS;
  const visPad = Math.ceil(TRACE_REACH / rc.RIVER_CELL);   // traces able to reach the region
  const joinPad = Math.ceil(INTERACT_R / rc.RIVER_CELL);   // clip-exactness neighborhood
  if (joinPad * rc.RIVER_CELL < INTERACT_R) {
    throw new Error('river pad inequality violated — unreachable by construction of ceil');
  }

  function sourcesOfCell(cx, cz) {
    const out = [];
    const base = sm64(((BigInt(cx) * 0x9e3779b97f4a7c15n) ^ (BigInt(cz) * 0xc2b2ae3d27d4eb4fn)
        ^ (seedN * 0xd1b54a32d192ed03n)) & U64);
    let h = base;
    for (let i = 0; i < rc.SOURCES_PER_CELL; i++) {
      h = sm64(h);
      const sx = (cx + u01(h)) * rc.RIVER_CELL;
      h = sm64(h);
      const sz = (cz + u01(h)) * rc.RIVER_CELL;
      const ry = routeAt(sx, sz);
      if (ry >= rc.MIN_SOURCE_Y) {
        out.push({ x: sx, z: sz, ry, cellX: cx, cellZ: cz, idx: i });
      }
    }
    return out;
  }
  const canonicalLt = (a, b) => a.cellZ - b.cellZ || a.cellX - b.cellX || a.idx - b.idx;

  // ---- Phase 1: pure walk — no cross-path state; own-segment self-clip; cached ----
  const traceCache = new Map();
  const walkedCells = new Set(); // instrumentation for the neighborhood gate
  function walkSource(src) {
    walkedCells.add(src.cellX + '|' + src.cellZ);
    const nodes = [{ x: src.x, z: src.z, ry: src.ry }];
    const lakes = []; // { idx (inlet node), x, z }
    let heading = null;
    let len = 0;
    let minRy = src.ry; // routing-track running min: all descent/lake decisions live here
    let terminal = 'maxlen';

    // own-segment bucket index for self-contact (pure: own geometry only). A proper loop
    // closing on itself produced SELF-crossings with conflicting water at one column
    // (validator finding) — a contact within SELF_JOIN_RADIUS of any own segment except the
    // ADJACENT one (which shares the current node, so its distance is trivially 0) clips the
    // walk: the loop drains into a terminal pond. Straight-line spacing to the next-older
    // segment is a full RIVER_STEP, so healthy meanders never trigger.
    const ownIndex = new Map();
    const ownKey = (x, z) => Math.floor(x / 256) + '|' + Math.floor(z / 256);
    const ownAdd = (i) => { // segment i-1 → i
      const a = nodes[i - 1], b = nodes[i];
      const n = Math.max(1, Math.ceil(Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) / 128));
      const ks = new Set();
      for (let j = 0; j <= n; j++) ks.add(ownKey(a.x + (b.x - a.x) * (j / n), a.z + (b.z - a.z) * (j / n)));
      for (const k of ks) {
        let arr = ownIndex.get(k);
        if (!arr) ownIndex.set(k, arr = []);
        arr.push(i - 1);
      }
    };
    const SELF2 = rc.SELF_JOIN_RADIUS * rc.SELF_JOIN_RADIUS;
    const selfContact = (nx, nz) => {
      // scan own segments near the candidate segment cur→(nx,nz), excluding ONLY the adjacent
      // segment (it shares the current node — proximity to it is trivially 0). Straight-line
      // spacing to the next-older segment is a full RIVER_STEP, well above SELF_JOIN_RADIUS,
      // so healthy meanders never trigger; hairpin folds and micro-loops do.
      const cur = nodes[nodes.length - 1];
      const lastOk = nodes.length - 1 - 1;
      if (lastOk < 1) return null;
      const seen = new Set();
      const n = Math.max(1, Math.ceil(Math.sqrt((nx - cur.x) * (nx - cur.x) + (nz - cur.z) * (nz - cur.z)) / 256));
      let best = null;
      for (let j = 0; j <= n; j++) {
        const bx = Math.floor((cur.x + (nx - cur.x) * (j / n)) / 256);
        const bz = Math.floor((cur.z + (nz - cur.z) * (j / n)) / 256);
        for (let dz = -1; dz <= 1; dz++) {
          for (let dx = -1; dx <= 1; dx++) {
            const arr = ownIndex.get((bx + dx) + '|' + (bz + dz));
            if (!arr) continue;
            for (const si of arr) {
              if (si > lastOk - 1 || seen.has(si)) continue;
              seen.add(si);
              const a = nodes[si], b = nodes[si + 1];
              const c = segSegClosest(cur.x, cur.z, nx, nz, a.x, a.z, b.x, b.z);
              if (c.d2 <= SELF2 && (!best || c.s < best.s)) best = c;
            }
          }
        }
      }
      return best;
    };
    const clipToContact = (contact, nx, nz) => {
      const cur = nodes[nodes.length - 1];
      const segLen = Math.sqrt((nx - cur.x) * (nx - cur.x) + (nz - cur.z) * (nz - cur.z)) || 1;
      const sAdj = Math.max(0, contact.s - 2 / segLen);
      const jx = cur.x + (nx - cur.x) * sAdj;
      const jz = cur.z + (nz - cur.z) * sAdj;
      nodes.push({ x: jx, z: jz, ry: routeAt(jx, jz), selfLoop: true });
    };

    while (len < rc.RIVER_MAX_LEN) {
      const cur = nodes[nodes.length - 1];
      // estuary: reached the coastal band of the ROUTING baseline
      if (cur.ry <= 63.5) { terminal = 'sea'; break; }
      // displacement cap: the interaction-bounding disc around the source (a node may
      // overshoot by ≤ one step — TRACE_REACH accounts for it)
      if (Math.sqrt((cur.x - src.x) * (cur.x - src.x) + (cur.z - src.z) * (cur.z - src.z)) > rc.REACH_MAX) { terminal = 'reach'; break; }

      // steep fast path: while the current heading keeps dropping hard, take it on a single
      // probe eval — a full ring per step is the dominant cost and adds nothing on steep runs
      let best = null;
      if (heading) {
        const nx = cur.x + heading[0] * rc.RIVER_STEP;
        const nz = cur.z + heading[1] * rc.RIVER_STEP;
        const ry = routeAt(nx, nz);
        if (ry <= minRy - rc.STEEP_FAST) best = { x: nx, z: nz, ry, dir: heading };
      }

      if (best === null) {
        // candidate ring on the routing surface, deterministic meander wobble (the seaward
        // pull is implicit: the routing baseline itself slopes to the coast)
        const wob = (u01(sm64((BigInt(Math.round(cur.x)) * 31n) ^ (BigInt(Math.round(cur.z)) * 17n)
            ^ (seedN & U64))) - 0.5) * 2;
        for (let d = 0; d < rc.RING_DIRS; d++) {
          const dir = RING_TABLE[d];
          const nx = cur.x + dir[0] * rc.RIVER_STEP;
          const nz = cur.z + dir[1] * rc.RIVER_STEP;
          const ry = routeAt(nx, nz);
          let score = ry;
          if (heading) score -= rc.INERTIA * (dir[0] * heading[0] + dir[1] * heading[1]);
          score += rc.MEANDER * wob * (heading ? (dir[0] * -heading[1] + dir[1] * heading[0]) : 0);
          if (!best || score < best.score) best = { x: nx, z: nz, ry, score, dir };
        }
      }

      if (best.ry >= minRy + rc.FLAT_EPS) {
        // LAKE: no candidate below the basin floor (routing track) — flood to the lowest
        // escape within the search budget; the river resumes from the outlet (spec §7.1)
        let outlet = null;
        for (let r = rc.RIVER_STEP * 2; r <= rc.LAKE_SEARCH_MAX; r = Math.min(
            Math.ceil(r * rc.LAKE_RING_GROW), r === rc.LAKE_SEARCH_MAX ? Infinity : rc.LAKE_SEARCH_MAX)) {
          for (let d = 0; d < rc.LAKE_RING_DIRS; d++) {
            const nx = cur.x + LAKE_TABLE[d][0] * r;
            const nz = cur.z + LAKE_TABLE[d][1] * r;
            const ry = routeAt(nx, nz);
            if (ry < minRy && (!outlet || ry < outlet.ry)) outlet = { x: nx, z: nz, ry, r };
          }
          if (outlet) break;
        }
        if (!outlet) { terminal = 'endorheic'; break; }
        const contact = selfContact(outlet.x, outlet.z);
        if (contact) { clipToContact(contact, outlet.x, outlet.z); terminal = 'self'; break; }
        lakes.push({ idx: nodes.length - 1, x: cur.x, z: cur.z });
        minRy = Math.min(minRy, outlet.ry);
        nodes.push({ x: outlet.x, z: outlet.z, ry: outlet.ry, lake: true });
        ownAdd(nodes.length - 1);
        heading = null;
        len += outlet.r;
        continue;
      }

      const contact = selfContact(best.x, best.z);
      if (contact) { clipToContact(contact, best.x, best.z); terminal = 'self'; break; }
      minRy = Math.min(minRy, best.ry);
      nodes.push({ x: best.x, z: best.z, ry: best.ry });
      ownAdd(nodes.length - 1);
      heading = best.dir;
      len += rc.RIVER_STEP;
    }
    return { nodes, lakes, terminal, source: { cellX: src.cellX, cellZ: src.cellZ, idx: src.idx } };
  }

  const srcKey = (s) => s.cellX + '|' + s.cellZ + '|' + s.idx;
  const traceOf = (src) => {
    const k = srcKey(src);
    let t = traceCache.get(k);
    if (!t) { t = walkSource(src); traceCache.set(k, t); registerSegs(src, t); }
    return t;
  };

  // ---- Phase 2: per-path clip (1-hop pure, cached) ----
  // clipInfoOf(src): first contact of src's trace with any canonically-earlier source's
  // UNCLIPPED trace within joinPad cells. Implementation detail: segments live in a GLOBAL
  // register-once bucket index (rebuilt-per-call local indexes dominated the cold profile),
  // but the SCAN filters candidates to exactly the canonical joinPad neighbor set — so the
  // outcome is a pure function of (src, its joinPad neighborhood) regardless of what else the
  // query history happened to register.
  const clipCache = new Map();
  const R2 = rc.JOIN_RADIUS * rc.JOIN_RADIUS;
  const segIndex = new Map(); // bucket -> [{ax,az,bx,bz,srcKey,segI,src}] (register-once)
  const bKey = (x, z) => Math.floor(x / 256) + '|' + Math.floor(z / 256);
  function registerSegs(src, tr) {
    for (let i = 1; i < tr.nodes.length; i++) {
      const a = tr.nodes[i - 1], b = tr.nodes[i];
      const seg = { ax: a.x, az: a.z, bx: b.x, bz: b.z, srcKey: srcKey(src), segI: i - 1, src };
      const n = Math.max(1, Math.ceil(Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) / 128));
      const ks = new Set();
      for (let j = 0; j <= n; j++) {
        ks.add(bKey(a.x + (b.x - a.x) * (j / n), a.z + (b.z - a.z) * (j / n)));
      }
      for (const k of ks) {
        let arr = segIndex.get(k);
        if (!arr) segIndex.set(k, arr = []);
        arr.push(seg);
      }
    }
  }
  function clipInfoOf(src) {
    const key = srcKey(src);
    if (clipCache.has(key)) return clipCache.get(key);
    // ensure every canonical neighbor's trace is walked AND registered before scanning.
    // Euclidean pruning: interaction beyond INTERACT_R is impossible by the coded pad
    // inequality, so the Chebyshev cell box's corner cells (up to √2·joinPad away) need not
    // be walked — a pure two-source predicate, identical in the walk loop and the scan filter.
    const near = (nb) => Math.sqrt((nb.x - src.x) * (nb.x - src.x) + (nb.z - src.z) * (nb.z - src.z)) <= INTERACT_R;
    for (let cz = src.cellZ - joinPad; cz <= src.cellZ + joinPad; cz++) {
      for (let cx = src.cellX - joinPad; cx <= src.cellX + joinPad; cx++) {
        for (const nb of sourcesOfCell(cx, cz)) {
          if (canonicalLt(nb, src) < 0 && near(nb)) traceOf(nb);
        }
      }
    }
    const inScope = (seg) => canonicalLt(seg.src, src) < 0 && near(seg.src);
    const tr = traceOf(src);
    let clip = null;
    for (let i = 1; i < tr.nodes.length && !clip; i++) {
      const a = tr.nodes[i - 1], b = tr.nodes[i];
      const seen = new Set();
      const n = Math.max(1, Math.ceil(Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) / 256));
      let bestC = null;
      for (let j = 0; j <= n; j++) {
        const cx = Math.floor((a.x + (b.x - a.x) * (j / n)) / 256);
        const cz = Math.floor((a.z + (b.z - a.z) * (j / n)) / 256);
        for (let dz = -1; dz <= 1; dz++) {
          for (let dx = -1; dx <= 1; dx++) {
            const arr = segIndex.get((cx + dx) + '|' + (cz + dz));
            if (!arr) continue;
            for (const seg of arr) {
              if (seen.has(seg) || !inScope(seg)) continue;
              seen.add(seg);
              const c = segSegClosest(a.x, a.z, b.x, b.z, seg.ax, seg.az, seg.bx, seg.bz);
              if (c.d2 > R2) continue;
              // earliest point along OUR segment wins; canonical tiebreak on EXACT s equality.
              // (strict < / exact-equality forms a total order — an epsilon window here is
              // non-transitive, so the winner could depend on scan order; validator finding)
              if (!bestC || c.s < bestC.s
                  || (c.s === bestC.s
                      && (canonicalLt(seg.src, bestC.hit.src) < 0
                          || (canonicalLt(seg.src, bestC.hit.src) === 0 && seg.segI < bestC.hit.segI)))) {
                bestC = { ...c, hit: seg };
              }
            }
          }
        }
      }
      if (bestC) clip = { nodeI: i - 1, ...bestC };
    }
    clipCache.set(key, clip);
    return clip;
  }
  // prefix water/terrain of a trace (running min over its UNCLIPPED nodes) — 1-hop pure;
  // lazy + cached. Terrain is kept so the sea-trim extent below needs no re-evaluation.
  const prefixCache = new Map();
  function prefixOf(src, uptoIdx) {
    const key = srcKey(src);
    let pc = prefixCache.get(key);
    if (!pc) { pc = { water: [], terrain: [], stepped: [], level: Infinity }; prefixCache.set(key, pc); }
    const tr = traceOf(src);
    const upto = Math.min(uptoIdx, tr.nodes.length - 1);
    for (let i = pc.water.length; i <= upto; i++) {
      const n = tr.nodes[i];
      const t = engine.heightAt(n.x, n.z);
      const prev = i === 0 ? Infinity : pc.water[i - 1];
      const raw = Math.max(rc.SEA_SURFACE, Math.min(prev, n.ry, t));
      pc.terrain.push(t);
      pc.water.push(raw);
      // flat-reach quantization (spec §7.2, R2b-4a): hold a flat level, step DOWN to the running
      // min when it has descended ≥ REACH_STEP below the held level. Causal (level@i depends only
      // on raw[0..i]) → the stepped prefix is a pure prefix property, cached like the raw one.
      if (i === 0) pc.level = raw;
      else if (pc.level - raw >= rc.REACH_STEP) pc.level = raw;
      pc.stepped.push(pc.level);
    }
    return pc;
  }

  /** Realized extent of a source's path under the SAME geometry rules realize() applies
   *  (clip truncation; estuary sea-trim for unclipped sea traces) — used for join validity. */
  function realizedExtentOf(src) {
    const tr = traceOf(src);
    const clip = clipInfoOf(src);
    if (clip) return { lastSegI: clip.nodeI, nodeCount: clip.nodeI + 2 };
    let len = tr.nodes.length;
    if (tr.terminal === 'sea') {
      const pc = prefixOf(src, len - 1);
      let lastLand = -1;
      for (let i = 0; i < len; i++) if (pc.terrain[i] >= 63) lastLand = i;
      if (lastLand >= 0 && lastLand < len - 2) len = lastLand + 2;
    }
    return { lastSegI: len - 2, nodeCount: len };
  }

  // ---- Phase 3: realize — geometry + labels + water ----
  function realize(minCX, minCZ, maxCX, maxCZ) {
    const sources = [];
    for (let cz = minCZ - visPad; cz <= maxCZ + visPad; cz++) {
      for (let cx = minCX - visPad; cx <= maxCX + visPad; cx++) {
        sources.push(...sourcesOfCell(cx, cz));
      }
    }
    sources.sort(canonicalLt);

    const paths = [];
    const lakes = [];
    for (const src of sources) {
      const tr = traceOf(src);
      if (tr.nodes.length < 2) continue;
      const clip = clipInfoOf(src);

      let nodes, terminal, joinTo = null, targetW = null;
      if (clip) {
        nodes = tr.nodes.slice(0, clip.nodeI + 1).map(n => ({ ...n }));
        // junction node at the closest approach, backed off 2 blocks along the incoming
        // segment — realized segments stop strictly short of the target (no touch/crossing)
        const a = tr.nodes[clip.nodeI], b = tr.nodes[clip.nodeI + 1];
        const segLen = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) || 1;
        const sAdj = Math.max(0, clip.s - 2 / segLen);
        const jx = a.x + (b.x - a.x) * sAdj;
        const jz = a.z + (b.z - a.z) * sAdj;
        nodes.push({ x: jx, z: jz, ry: routeAt(jx, jz), junction: true });
        // join validity (2-hop pure): the junction must land within the TARGET's own realized
        // extent, and the target itself must be realized (≥ 4 nodes)
        const target = clip.hit.src;
        const ext = realizedExtentOf(target);
        const valid = ext.nodeCount >= 4 && clip.hit.segI < ext.lastSegI;
        if (valid) {
          terminal = 'join';
          joinTo = { srcKey: clip.hit.srcKey, segI: clip.hit.segI, t: clip.t };
          // junction water (R2b-4a): the target's STEPPED prefix level at the hit param — the
          // tributary pools into the target's FLAT reach. A PREFIX property of the target's
          // unclipped trace (independent of the target's own clip, since segI < its clip index),
          // and the stepped level is causal in that prefix → still 1-hop pure. NOTE: if the
          // target's own trailing reach is later backwater-raised, a tributary joining inside that
          // raised suffix reconciles against the RAW-stepped prefix level (1-hop bound — raises
          // never chain).
          const pw = prefixOf(target, clip.hit.segI + 1).stepped;
          const w0 = pw[clip.hit.segI], w1 = pw[Math.min(clip.hit.segI + 1, pw.length - 1)];
          targetW = w0 + (w1 - w0) * clip.t;
        } else {
          terminal = 'endorheic'; // phantom reach — tributary pools where its channel ends
        }
      } else {
        nodes = tr.nodes.map(n => ({ ...n }));
        terminal = tr.terminal === 'sea' ? 'sea' : 'endorheic';
      }

      // water pass (spec §7.2): running min of (routing, designed terrain), floored at sea, then
      // QUANTIZED into flat reaches (R2b-4a). The trace-prefix part (raw running min + stepped
      // level + terrain) comes from the shared prefix cache; the junction node continues BOTH the
      // raw running min and the flat-reach stepping downstream.
      const lastTraceI = clip ? clip.nodeI : nodes.length - 1;
      const pc = prefixOf(src, lastTraceI);
      let wRaw = Infinity;   // raw running min, carried into the junction node
      let level = Infinity;  // current flat-reach level (stepped water), carried likewise
      for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i];
        if (i <= lastTraceI) {
          n.waterY = level = pc.stepped[i];
          n.terrainY = pc.terrain[i];
          wRaw = pc.water[i];
        } else { // junction node (not part of the unclipped trace) — continue running min + step
          const t = engine.heightAt(n.x, n.z);
          wRaw = Math.max(rc.SEA_SURFACE, Math.min(wRaw, n.ry, t));
          if (level - wRaw >= rc.REACH_STEP) level = wRaw;
          n.waterY = level;
          n.terrainY = t;
        }
      }
      // backwater reconciliation: a tributary arriving BELOW the target's junction level is
      // flooded back — its trailing reach pools at the target's level. max() preserves the
      // running-min monotonicity (suffix raise of a non-increasing sequence).
      if (targetW !== null && nodes[nodes.length - 1].waterY < targetW - 1e-9) {
        for (let i = nodes.length - 1; i >= 0 && nodes[i].waterY < targetW; i--) {
          nodes[i].waterY = targetW;
          nodes[i].backwater = true;
        }
      }
      // estuary trim: the routing-baseline estuary test overshoots into bays — drop trailing
      // sub-sea-terrain nodes, keeping one mouth node past the last land node. (Trailing-only:
      // mid-path sub-sea dips are §1 global water and stay.)
      if (terminal === 'sea') {
        let lastLand = -1;
        for (let i = 0; i < nodes.length; i++) if (nodes[i].terrainY >= 63) lastLand = i;
        if (lastLand >= 0 && lastLand < nodes.length - 2) nodes = nodes.slice(0, lastLand + 2);
      }
      if (nodes.length < 4) continue;

      // stepped-reach metadata (spec §7.2, R2b-4a), derived from the FINAL flat-per-reach water
      // (post backwater + estuary trim): reachId (per-path monotone pool index), stepDrop (the
      // drop at a step node, else 0), lip (a step whose drop ≥ LIP_STEP = WATERFALL_MIN_DROP — the
      // SAME lip test as R1, now reading the stepped water). Flat-reach segments carry one assigned
      // level so their drop is exactly 0 (never a spurious step).
      nodes[0].reachId = 0;
      nodes[0].stepDrop = 0;
      nodes[0].lip = false;
      let rid = 0;
      for (let i = 1; i < nodes.length; i++) {
        const d = nodes[i - 1].waterY - nodes[i].waterY;
        if (d > 1e-9) {
          rid++;
          nodes[i].stepDrop = d;
          nodes[i].lip = d >= rc.LIP_STEP;
        } else {
          nodes[i].stepDrop = 0;
          nodes[i].lip = false;
        }
        nodes[i].reachId = rid;
      }
      const lastI = clip ? clip.nodeI : nodes.length - 1;
      for (const lk of tr.lakes) {
        if (lk.idx <= lastI && lk.idx < nodes.length) {
          lakes.push({ x: lk.x, z: lk.z, level: nodes[lk.idx].waterY, endorheic: false });
        }
      }
      if (terminal === 'endorheic') {
        const end = nodes[nodes.length - 1];
        lakes.push({
          x: end.x, z: end.z, level: end.waterY, endorheic: true,
          // honest flags: WHY is this endorheic?
          phantom: !!clip,                                        // clipped onto a phantom reach
          selfLoop: !clip && tr.terminal === 'self',              // closed its own loop
          truncated: !clip && (tr.terminal === 'maxlen' || tr.terminal === 'reach'), // budget cut
        });
      }
      paths.push({ nodes, terminal, joinTo, source: tr.source });
    }
    return { paths, lakes };
  }

  /**
   * Per-chunk column query (spec §7.6 perf contract): gather the segments near one chunk ONCE,
   * then answer per-column nearest-river queries against that local set. R2's integration
   * shape; the gate battery times both halves (gather ≤ 0.5 ms, query ≤ 2 µs).
   */
  function columnQuery(realized, chunkX, chunkZ, halo = 64) {
    const x0 = chunkX * 16 - halo, x1 = chunkX * 16 + 16 + halo;
    const z0 = chunkZ * 16 - halo, z1 = chunkZ * 16 + 16 + halo;
    const local = [];
    for (const p of realized.paths) {
      for (let i = 1; i < p.nodes.length; i++) {
        const a = p.nodes[i - 1], b = p.nodes[i];
        if (Math.max(a.x, b.x) < x0 || Math.min(a.x, b.x) > x1
            || Math.max(a.z, b.z) < z0 || Math.min(a.z, b.z) > z1) continue;
        local.push({ ax: a.x, az: a.z, bx: b.x, bz: b.z, wa: a.waterY, wb: b.waterY });
      }
    }
    return (x, z) => {
      let bestD2 = Infinity, bestW = 0;
      for (const s of local) {
        const dx = s.bx - s.ax, dz = s.bz - s.az;
        const t = Math.max(0, Math.min(1, ((x - s.ax) * dx + (z - s.az) * dz) / (dx * dx + dz * dz || 1)));
        const qx = s.ax + t * dx, qz = s.az + t * dz;
        const d2 = (x - qx) * (x - qx) + (z - qz) * (z - qz);
        if (d2 < bestD2) { bestD2 = d2; bestW = s.wa + (s.wb - s.wa) * t; }
      }
      return { d2: bestD2, waterY: bestW };
    };
  }

  const stats = () => ({ walkedCells: walkedCells.size, walkedSources: traceCache.size });
  return { realize, columnQuery, routeAt, visPad, joinPad, padCells: visPad, stats };
}
