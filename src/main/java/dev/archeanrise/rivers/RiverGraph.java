package dev.archeanrise.rivers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static dev.archeanrise.rivers.RiverConstants.*;

/**
 * The spec §7.0–§7.2 trace-based river graph — a BIT-FOR-BIT Java port of
 * {@code tools/preview/rivers-r1.mjs} (THE algorithm of record; see
 * {@code docs/research/14-rivers-r2-design.md}). The R2a parity gate
 * ({@code tools/measure/river-parity.mjs}) proves this class and the JS reference produce
 * numerically identical graphs (every node coordinate, water level, flag, terminal, join target
 * and lake) on the same seed, so read rivers-r1.mjs for the full algorithm rationale; this file
 * documents only the port.
 *
 * <p><b>Purity model</b> (rivers-r1.mjs header): Phase 1 — each source's WALK is a pure function
 * of (seed, source) that may consult only its own earlier segments (self-contact clipping).
 * Phase 2 — CLIP (1-hop pure): a path's realized geometry is its trace truncated at the first
 * proximity to any canonically-earlier source's UNCLIPPED trace within the coded
 * {@link RiverConstants#JOIN_PAD} neighborhood. Phase 3 — LABELS &amp; WATER (2-hop pure): join
 * validity and junction water consume the target's own 1-hop-pure clip info and prefix water;
 * they never feed back into geometry. Any cell region therefore realizes the identical graph in
 * any query order.
 *
 * <p><b>Flat reaches</b> (spec §7.2, R2b-4a): the per-node running-min water is QUANTIZED into a
 * staircase of flat pools — a pure downstream pass holds a reach {@code level} and steps down to
 * the running min whenever it descends &ge; {@link RiverConstants#REACH_STEP} below the held level
 * ({@link Prefix#stepped}). {@code PathNode.waterY} is the stepped (flat-per-reach) level;
 * {@code reachId}/{@code stepDrop}/{@code lip} record the steps. The quantization is causal (a pure
 * prefix property, cached with the raw prefix) so the determinism model is unchanged; junction
 * backwater reconciles against the target's STEPPED prefix level. GEOMETRY (x/z, clip, joins, lake
 * positions/flags) is byte-identical to R1 — only waterY and the step metadata change; the carve
 * (R2b-4b), aquifer (R2c) and waterfalls (R2b-4d) consume this stepped water and re-gate downstream.
 *
 * <p><b>Bit-parity rules honored here</b> (doc 14 §0): distances via
 * {@code Math.sqrt(dx*dx+dz*dz)} only (never {@code Math.hypot} — different rounding); no
 * {@code Math.cos/sin} (frozen {@link RiverConstants#RING_TABLE}/{@link RiverConstants#LAKE_TABLE}
 * literals); splitmix64 in native {@code long} arithmetic (the JS BigInt code masks to 64 bits,
 * which IS Java two's-complement long math; logical shifts {@code >>>} where the JS shifts
 * non-negative masked BigInts); {@code Math.round} for the wobble hash and routing memo (Java's
 * round(double) matches JS Math.round numerically on all finite inputs — self-checked in
 * {@link RiverConstants}); JS {@code Set} insertion order reproduced with {@link LinkedHashSet}
 * where scan order breaks ties (the self-contact scan).
 *
 * <p><b>Threading:</b> the walk/clip/prefix caches and the register-once segment index are shared
 * mutable state, so {@link #realize} is {@code synchronized} (coarse, correct — R2a is consumed
 * only by the dump command; the chunk-hot-path cache design is R2b's job). Everything RETURNED is
 * deeply immutable.
 */
public final class RiverGraph {

	/** The two fields the tracer consumes — kept abstract so the graph is engine-agnostic.
	 * In-game this is {@code ServerRiverSampler} (the emitted {@code archean_rise:rise/rivers/routing}
	 * DF + the designed-surface offset evaluation); tests may supply anything pure. Samplers are
	 * RAW: the 64-block routing memo lives in this class, so {@link FieldSampler#routingAt} is only
	 * ever called at 64-block-aligned coordinates. */
	public interface FieldSampler {
		/** Routing surface (spec §7.1) at a 64-block-aligned position. */
		double routingAt(double x, double z);

		/** Designed (texture-free) terrain height — the JS {@code engine.heightAt} equivalent. */
		double designedSurfaceAt(double x, double z);
	}

	// ---- output model (immutable) ----

	/** Canonical source identity; {@link #key()} is the cross-language {@code "cellX|cellZ|idx"} form. */
	public record SourceId(int cellX, int cellZ, int idx) {
		public String key() {
			return cellX + "|" + cellZ + "|" + idx;
		}
	}

	/** Join target: the earlier path's source key, hit segment index, and parameter along it. */
	public record JoinTo(String srcKey, int segI, double t) {}

	/** One realized node. {@code waterY} is the STEPPED (flat-per-reach) water surface (spec §7.2,
	 * R2b-4a); {@code terrainY} is the designed surface used by the running-min water formula.
	 * {@code stepDrop} is the drop (blocks) at a step node (else 0); {@code reachId} is the per-path
	 * monotone flat-reach (pool) index; {@code lip} marks a step whose {@code stepDrop ≥ LIP_STEP}. */
	public static final class PathNode {
		public final double x;
		public final double z;
		public final double ry;
		public final double waterY;
		public final double terrainY;
		public final boolean lip;
		public final boolean junction;
		public final boolean backwater;
		public final boolean lake;
		public final boolean selfLoop;
		public final double stepDrop;
		public final int reachId;

		PathNode(double x, double z, double ry, double waterY, double terrainY,
				boolean lip, boolean junction, boolean backwater, boolean lake, boolean selfLoop,
				double stepDrop, int reachId) {
			this.x = x;
			this.z = z;
			this.ry = ry;
			this.waterY = waterY;
			this.terrainY = terrainY;
			this.lip = lip;
			this.junction = junction;
			this.backwater = backwater;
			this.lake = lake;
			this.selfLoop = selfLoop;
			this.stepDrop = stepDrop;
			this.reachId = reachId;
		}
	}

	/** One realized path. {@code terminal} is {@code "sea" | "join" | "endorheic"} (JS strings). */
	public static final class RiverPath {
		public final SourceId source;
		public final String terminal;
		public final JoinTo joinTo; // null unless terminal == "join"
		public final List<PathNode> nodes;

		RiverPath(SourceId source, String terminal, JoinTo joinTo, List<PathNode> nodes) {
			this.source = source;
			this.terminal = terminal;
			this.joinTo = joinTo;
			this.nodes = nodes;
		}
	}

	/** A lake: mid-path flooded basin ({@code endorheic == false}) or flagged terminal pond. */
	public static final class Lake {
		public final double x;
		public final double z;
		public final double level;
		public final boolean endorheic;
		public final boolean phantom;   // clipped onto a phantom reach (endorheic only)
		public final boolean selfLoop;  // closed its own loop (endorheic only)
		public final boolean truncated; // budget cut: maxlen/reach (endorheic only)

		Lake(double x, double z, double level, boolean endorheic,
				boolean phantom, boolean selfLoop, boolean truncated) {
			this.x = x;
			this.z = z;
			this.level = level;
			this.endorheic = endorheic;
			this.phantom = phantom;
			this.selfLoop = selfLoop;
			this.truncated = truncated;
		}
	}

	/** A realized region: paths sourced within the region + visPad, plus their lakes. */
	public record Realized(List<RiverPath> paths, List<Lake> lakes) {}

	// ---- internal model ----

	private static final class Src {
		final int cellX;
		final int cellZ;
		final int idx;
		final double x;
		final double z;
		final double ry;

		Src(int cellX, int cellZ, int idx, double x, double z, double ry) {
			this.cellX = cellX;
			this.cellZ = cellZ;
			this.idx = idx;
			this.x = x;
			this.z = z;
			this.ry = ry;
		}

		String key() {
			return cellX + "|" + cellZ + "|" + idx;
		}
	}

	private enum Term { MAXLEN, SEA, REACH, ENDORHEIC, SELF }

	private static final class TNode {
		final double x;
		final double z;
		final double ry;
		final boolean lake;
		final boolean selfLoop;

		TNode(double x, double z, double ry, boolean lake, boolean selfLoop) {
			this.x = x;
			this.z = z;
			this.ry = ry;
			this.lake = lake;
			this.selfLoop = selfLoop;
		}
	}

	private record TLake(int idx, double x, double z) {}

	private static final class Trace {
		final ArrayList<TNode> nodes;
		final ArrayList<TLake> lakes;
		final Term terminal;

		Trace(ArrayList<TNode> nodes, ArrayList<TLake> lakes, Term terminal) {
			this.nodes = nodes;
			this.lakes = lakes;
			this.terminal = terminal;
		}
	}

	private static final class Seg {
		final double ax;
		final double az;
		final double bx;
		final double bz;
		final String srcKey;
		final int segI;
		final Src src;

		Seg(double ax, double az, double bx, double bz, String srcKey, int segI, Src src) {
			this.ax = ax;
			this.az = az;
			this.bx = bx;
			this.bz = bz;
			this.srcKey = srcKey;
			this.segI = segI;
			this.src = src;
		}
	}

	/** segSegClosest result: squared distance + params on both segments (s on the first). */
	private record Contact(double d2, double s, double t) {}

	private static final class Clip {
		final int nodeI;
		final double s;
		final double t;
		final Seg hit;

		Clip(int nodeI, double s, double t, Seg hit) {
			this.nodeI = nodeI;
			this.s = s;
			this.t = t;
			this.hit = hit;
		}
	}

	private static final class Prefix {
		final ArrayList<Double> water = new ArrayList<>();
		final ArrayList<Double> terrain = new ArrayList<>();
		/** Flat-reach quantization of {@link #water} (spec §7.2, R2b-4a) — the stepped water level
		 * per node; {@link #level} carries the running reach level across lazy extensions. */
		final ArrayList<Double> stepped = new ArrayList<>();
		double level = Double.POSITIVE_INFINITY;
	}

	/** Mutable working node for the realize pass (frozen into {@link PathNode} on return). */
	private static final class WNode {
		final double x;
		final double z;
		final double ry;
		final boolean lake;
		final boolean selfLoop;
		final boolean junction;
		double waterY;
		double terrainY;
		boolean lip;
		boolean backwater;
		double stepDrop;
		int reachId;

		WNode(double x, double z, double ry, boolean lake, boolean selfLoop, boolean junction) {
			this.x = x;
			this.z = z;
			this.ry = ry;
			this.lake = lake;
			this.selfLoop = selfLoop;
			this.junction = junction;
		}
	}

	// ---- state ----

	private final long seed;
	private final FieldSampler sampler;
	/** 64-block routing memo (rivers-r1.mjs): key qx·2^21+qz, collision-free for |x|,|z| &lt; 67M. */
	private final HashMap<Long, Double> routeMemo = new HashMap<>();
	private final Map<String, Trace> traceCache = new HashMap<>();
	private final Map<String, Clip> clipCache = new HashMap<>(); // caches null (no clip) too
	private final Map<String, Prefix> prefixCache = new HashMap<>();
	private final Map<Long, ArrayList<Seg>> segIndex = new HashMap<>(); // register-once buckets
	private final HashSet<Long> walkedCells = new HashSet<>(); // instrumentation

	private static final double SELF2 = SELF_JOIN_RADIUS * SELF_JOIN_RADIUS;
	private static final double R2 = JOIN_RADIUS * JOIN_RADIUS;

	public RiverGraph(long seed, FieldSampler sampler) {
		this.seed = seed;
		this.sampler = sampler;
	}

	// ---- splitmix64 (exact VoronoiField family — the JS BigInt code masks to u64, which is
	// exactly Java long two's-complement arithmetic; shifts on the masked non-negative BigInt are
	// logical, hence >>> here) ----

	private static long sm64(long z) {
		z += 0x9e3779b97f4a7c15L;
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		return z ^ (z >>> 31);
	}

	private static double u01(long h) {
		return (h >>> 11) * 0x1.0p-53;
	}

	/** Routing surface, memoized on the 64-block grid (JS: Math.round(x/64) — Java round matches). */
	private double routeAt(double x, double z) {
		long qx = Math.round(x / 64);
		long qz = Math.round(z / 64);
		long k = qx * 2097152L + qz;
		Double v = routeMemo.get(k);
		if (v == null) {
			v = sampler.routingAt(qx * 64.0, qz * 64.0);
			routeMemo.put(k, v);
		}
		return v;
	}

	private List<Src> sourcesOfCell(int cx, int cz) {
		ArrayList<Src> out = new ArrayList<>();
		long h = sm64((long) cx * 0x9e3779b97f4a7c15L ^ (long) cz * 0xc2b2ae3d27d4eb4fL
				^ seed * 0xd1b54a32d192ed03L);
		for (int i = 0; i < SOURCES_PER_CELL; i++) {
			h = sm64(h);
			double sx = (cx + u01(h)) * RIVER_CELL;
			h = sm64(h);
			double sz = (cz + u01(h)) * RIVER_CELL;
			double ry = routeAt(sx, sz);
			if (ry >= MIN_SOURCE_Y) {
				out.add(new Src(cx, cz, i, sx, sz, ry));
			}
		}
		return out;
	}

	/** Canonical source order (cellZ, cellX, idx) — the sign contract of the JS comparator. */
	private static int canonicalCompare(Src a, Src b) {
		int c = Integer.compare(a.cellZ, b.cellZ);
		if (c != 0) {
			return c;
		}
		c = Integer.compare(a.cellX, b.cellX);
		return c != 0 ? c : Integer.compare(a.idx, b.idx);
	}

	// segment-to-segment closest approach — verbatim port (thresholds included)
	private static Contact segSegClosest(double ax, double az, double bx, double bz,
			double cx, double cz, double dx, double dz) {
		double ux = bx - ax, uz = bz - az, vx = dx - cx, vz = dz - cz, wx = ax - cx, wz = az - cz;
		double a = ux * ux + uz * uz, b = ux * vx + uz * vz, c = vx * vx + vz * vz;
		double d = ux * wx + uz * wz, e = vx * wx + vz * wz;
		double den = a * c - b * b;
		double s = den > 1e-9 ? (b * e - c * d) / den : 0;
		s = Math.max(0, Math.min(1, s));
		double t = c > 1e-9 ? (b * s + e) / c : 0;
		t = Math.max(0, Math.min(1, t));
		s = a > 1e-9 ? Math.max(0, Math.min(1, (b * t - d) / a)) : 0;
		double px = ax + s * ux, pz = az + s * uz;
		double qx = cx + t * vx, qz = cz + t * vz;
		return new Contact((px - qx) * (px - qx) + (pz - qz) * (pz - qz), s, t);
	}

	/** 256-block bucket key (JS string "floor(x/256)|floor(z/256)" — packed injectively). */
	private static long bKey256(double x, double z) {
		return ((long) Math.floor(x / 256)) * 0x100000000L + (long) Math.floor(z / 256);
	}

	private static long cellKey(int cx, int cz) {
		return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
	}

	// ---- Phase 1: pure walk — no cross-path state; own-segment self-clip ----

	/** Registers own segment (i-1 → i) into the walk-local bucket index. Bucket key first-visit
	 * order (LinkedHashSet) and append order reproduce the JS Set/array iteration exactly — the
	 * self-contact scan below resolves exact-s ties by scan order, so order is semantic. */
	private static void ownAdd(ArrayList<TNode> nodes, HashMap<Long, ArrayList<Integer>> ownIndex, int i) {
		TNode a = nodes.get(i - 1);
		TNode b = nodes.get(i);
		int n = Math.max(1, (int) Math.ceil(
				Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) / 128));
		LinkedHashSet<Long> ks = new LinkedHashSet<>();
		for (int j = 0; j <= n; j++) {
			ks.add(bKey256(a.x + (b.x - a.x) * ((double) j / n), a.z + (b.z - a.z) * ((double) j / n)));
		}
		for (long k : ks) {
			ownIndex.computeIfAbsent(k, kk -> new ArrayList<>()).add(i - 1);
		}
	}

	/** First own-segment contact of the candidate segment cur→(nx,nz) within SELF_JOIN_RADIUS,
	 * excluding only the adjacent segment; earliest-s wins, exact ties by scan order (JS parity). */
	private Contact selfContact(ArrayList<TNode> nodes, HashMap<Long, ArrayList<Integer>> ownIndex,
			double nx, double nz) {
		TNode cur = nodes.get(nodes.size() - 1);
		int lastOk = nodes.size() - 1 - 1;
		if (lastOk < 1) {
			return null;
		}
		HashSet<Integer> seen = new HashSet<>();
		int n = Math.max(1, (int) Math.ceil(
				Math.sqrt((nx - cur.x) * (nx - cur.x) + (nz - cur.z) * (nz - cur.z)) / 256));
		Contact best = null;
		for (int j = 0; j <= n; j++) {
			long bx = (long) Math.floor((cur.x + (nx - cur.x) * ((double) j / n)) / 256);
			long bz = (long) Math.floor((cur.z + (nz - cur.z) * ((double) j / n)) / 256);
			for (long dz = -1; dz <= 1; dz++) {
				for (long dx = -1; dx <= 1; dx++) {
					ArrayList<Integer> arr = ownIndex.get((bx + dx) * 0x100000000L + (bz + dz));
					if (arr == null) {
						continue;
					}
					for (int si : arr) {
						if (si > lastOk - 1 || seen.contains(si)) {
							continue;
						}
						seen.add(si);
						TNode a = nodes.get(si);
						TNode b = nodes.get(si + 1);
						Contact c = segSegClosest(cur.x, cur.z, nx, nz, a.x, a.z, b.x, b.z);
						if (c.d2 <= SELF2 && (best == null || c.s < best.s)) {
							best = c;
						}
					}
				}
			}
		}
		return best;
	}

	/** Loop closure: junction at the contact, backed off 2 blocks along the incoming segment. */
	private void clipToContact(ArrayList<TNode> nodes, Contact contact, double nx, double nz) {
		TNode cur = nodes.get(nodes.size() - 1);
		double segLen = Math.sqrt((nx - cur.x) * (nx - cur.x) + (nz - cur.z) * (nz - cur.z));
		if (segLen == 0) {
			segLen = 1; // JS `|| 1`
		}
		double sAdj = Math.max(0, contact.s - 2 / segLen);
		double jx = cur.x + (nx - cur.x) * sAdj;
		double jz = cur.z + (nz - cur.z) * sAdj;
		nodes.add(new TNode(jx, jz, routeAt(jx, jz), false, true));
	}

	private Trace walkSource(Src src) {
		walkedCells.add(cellKey(src.cellX, src.cellZ));
		ArrayList<TNode> nodes = new ArrayList<>();
		nodes.add(new TNode(src.x, src.z, src.ry, false, false));
		ArrayList<TLake> lakes = new ArrayList<>();
		HashMap<Long, ArrayList<Integer>> ownIndex = new HashMap<>();
		double[] heading = null;
		double len = 0;
		double minRy = src.ry; // routing-track running min: all descent/lake decisions live here
		Term terminal = Term.MAXLEN;

		while (len < RIVER_MAX_LEN) {
			TNode cur = nodes.get(nodes.size() - 1);
			// estuary: reached the coastal band of the ROUTING baseline
			if (cur.ry <= 63.5) {
				terminal = Term.SEA;
				break;
			}
			// displacement cap (a node may overshoot by ≤ one step — TRACE_REACH accounts for it)
			if (Math.sqrt((cur.x - src.x) * (cur.x - src.x) + (cur.z - src.z) * (cur.z - src.z)) > REACH_MAX) {
				terminal = Term.REACH;
				break;
			}

			// steep fast path: single straight probe while the heading keeps dropping hard
			boolean haveBest = false;
			double bestX = 0, bestZ = 0, bestRy = 0, bestScore = 0;
			double[] bestDir = null;
			if (heading != null) {
				double nx = cur.x + heading[0] * RIVER_STEP;
				double nz = cur.z + heading[1] * RIVER_STEP;
				double ry = routeAt(nx, nz);
				if (ry <= minRy - STEEP_FAST) {
					haveBest = true;
					bestX = nx;
					bestZ = nz;
					bestRy = ry;
					bestDir = heading;
				}
			}

			if (!haveBest) {
				// candidate ring on the routing surface with deterministic meander wobble
				double wob = (u01(sm64(Math.round(cur.x) * 31L ^ Math.round(cur.z) * 17L ^ seed)) - 0.5) * 2;
				for (int d = 0; d < RING_DIRS; d++) {
					double[] dir = RING_TABLE[d];
					double nx = cur.x + dir[0] * RIVER_STEP;
					double nz = cur.z + dir[1] * RIVER_STEP;
					double ry = routeAt(nx, nz);
					double score = ry;
					if (heading != null) {
						score -= INERTIA * (dir[0] * heading[0] + dir[1] * heading[1]);
					}
					score += MEANDER * wob * (heading != null ? (dir[0] * -heading[1] + dir[1] * heading[0]) : 0);
					if (!haveBest || score < bestScore) {
						haveBest = true;
						bestX = nx;
						bestZ = nz;
						bestRy = ry;
						bestScore = score;
						bestDir = dir;
					}
				}
			}

			if (bestRy >= minRy + FLAT_EPS) {
				// LAKE: flood to the lowest escape within the search budget; resume from the outlet.
				// The radius progression (incl. the ceil-of-double growth) is copied exactly.
				boolean haveOutlet = false;
				double outX = 0, outZ = 0, outRy = 0, outR = 0;
				for (double r = RIVER_STEP * 2; r <= LAKE_SEARCH_MAX; r = Math.min(
						Math.ceil(r * LAKE_RING_GROW), r == LAKE_SEARCH_MAX ? Double.POSITIVE_INFINITY : LAKE_SEARCH_MAX)) {
					for (int d = 0; d < LAKE_RING_DIRS; d++) {
						double nx = cur.x + LAKE_TABLE[d][0] * r;
						double nz = cur.z + LAKE_TABLE[d][1] * r;
						double ry = routeAt(nx, nz);
						if (ry < minRy && (!haveOutlet || ry < outRy)) {
							haveOutlet = true;
							outX = nx;
							outZ = nz;
							outRy = ry;
							outR = r;
						}
					}
					if (haveOutlet) {
						break;
					}
				}
				if (!haveOutlet) {
					terminal = Term.ENDORHEIC;
					break;
				}
				Contact contact = selfContact(nodes, ownIndex, outX, outZ);
				if (contact != null) {
					clipToContact(nodes, contact, outX, outZ);
					terminal = Term.SELF;
					break;
				}
				lakes.add(new TLake(nodes.size() - 1, cur.x, cur.z));
				minRy = Math.min(minRy, outRy);
				nodes.add(new TNode(outX, outZ, outRy, true, false));
				ownAdd(nodes, ownIndex, nodes.size() - 1);
				heading = null;
				len += outR;
				continue;
			}

			Contact contact = selfContact(nodes, ownIndex, bestX, bestZ);
			if (contact != null) {
				clipToContact(nodes, contact, bestX, bestZ);
				terminal = Term.SELF;
				break;
			}
			minRy = Math.min(minRy, bestRy);
			nodes.add(new TNode(bestX, bestZ, bestRy, false, false));
			ownAdd(nodes, ownIndex, nodes.size() - 1);
			heading = bestDir;
			len += RIVER_STEP;
		}
		return new Trace(nodes, lakes, terminal);
	}

	private Trace traceOf(Src src) {
		String k = src.key();
		Trace t = traceCache.get(k);
		if (t == null) {
			t = walkSource(src);
			traceCache.put(k, t);
			registerSegs(src, t);
		}
		return t;
	}

	// ---- Phase 2: per-path clip (1-hop pure, cached; register-once global bucket index) ----

	private void registerSegs(Src src, Trace tr) {
		for (int i = 1; i < tr.nodes.size(); i++) {
			TNode a = tr.nodes.get(i - 1);
			TNode b = tr.nodes.get(i);
			Seg seg = new Seg(a.x, a.z, b.x, b.z, src.key(), i - 1, src);
			int n = Math.max(1, (int) Math.ceil(
					Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) / 128));
			LinkedHashSet<Long> ks = new LinkedHashSet<>();
			for (int j = 0; j <= n; j++) {
				ks.add(bKey256(a.x + (b.x - a.x) * ((double) j / n), a.z + (b.z - a.z) * ((double) j / n)));
			}
			for (long k : ks) {
				segIndex.computeIfAbsent(k, kk -> new ArrayList<>()).add(seg);
			}
		}
	}

	/** Euclidean interaction pruning — the pure two-source predicate of the coded pad inequality. */
	private static boolean near(Src nb, Src src) {
		return Math.sqrt((nb.x - src.x) * (nb.x - src.x) + (nb.z - src.z) * (nb.z - src.z)) <= INTERACT_R;
	}

	/** First contact of src's trace with any canonically-earlier, in-range source's UNCLIPPED
	 * trace: earliest along-trace segment, then earliest s with the exact total-order tiebreak
	 * (strict &lt; on s; exact equality → canonical source order, then segI) — scan-order-free. */
	private Clip clipInfoOf(Src src) {
		String key = src.key();
		if (clipCache.containsKey(key)) {
			return clipCache.get(key);
		}
		// ensure every canonical neighbor's trace is walked AND registered before scanning
		for (int cz = src.cellZ - JOIN_PAD; cz <= src.cellZ + JOIN_PAD; cz++) {
			for (int cx = src.cellX - JOIN_PAD; cx <= src.cellX + JOIN_PAD; cx++) {
				for (Src nb : sourcesOfCell(cx, cz)) {
					if (canonicalCompare(nb, src) < 0 && near(nb, src)) {
						traceOf(nb);
					}
				}
			}
		}
		Trace tr = traceOf(src);
		Clip clip = null;
		for (int i = 1; i < tr.nodes.size() && clip == null; i++) {
			TNode a = tr.nodes.get(i - 1);
			TNode b = tr.nodes.get(i);
			HashSet<Seg> seen = new HashSet<>(); // identity semantics (Seg has no equals) — as JS
			int n = Math.max(1, (int) Math.ceil(
					Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z)) / 256));
			Contact bestC = null;
			Seg bestHit = null;
			for (int j = 0; j <= n; j++) {
				long cxB = (long) Math.floor((a.x + (b.x - a.x) * ((double) j / n)) / 256);
				long czB = (long) Math.floor((a.z + (b.z - a.z) * ((double) j / n)) / 256);
				for (long dz = -1; dz <= 1; dz++) {
					for (long dx = -1; dx <= 1; dx++) {
						ArrayList<Seg> arr = segIndex.get((cxB + dx) * 0x100000000L + (czB + dz));
						if (arr == null) {
							continue;
						}
						for (Seg seg : arr) {
							if (seen.contains(seg) || !(canonicalCompare(seg.src, src) < 0 && near(seg.src, src))) {
								continue;
							}
							seen.add(seg);
							Contact c = segSegClosest(a.x, a.z, b.x, b.z, seg.ax, seg.az, seg.bx, seg.bz);
							if (c.d2 > R2) {
								continue;
							}
							// earliest point along OUR segment wins; canonical tiebreak on EXACT s
							// equality (strict < / == forms a total order — scan-order-free)
							boolean better;
							if (bestC == null || bestHit == null) {
								better = true;
							} else if (c.s < bestC.s) {
								better = true;
							} else if (c.s == bestC.s) {
								int cc = canonicalCompare(seg.src, bestHit.src);
								better = cc < 0 || (cc == 0 && seg.segI < bestHit.segI);
							} else {
								better = false;
							}
							if (better) {
								bestC = c;
								bestHit = seg;
							}
						}
					}
				}
			}
			if (bestC != null) {
				clip = new Clip(i - 1, bestC.s, bestC.t, bestHit);
			}
		}
		clipCache.put(key, clip);
		return clip;
	}

	/** Prefix water/terrain of the UNCLIPPED trace (running min, sea-floored) — lazy + cached. */
	private Prefix prefixOf(Src src, int uptoIdx) {
		Prefix pc = prefixCache.computeIfAbsent(src.key(), k -> new Prefix());
		Trace tr = traceOf(src);
		int upto = Math.min(uptoIdx, tr.nodes.size() - 1);
		for (int i = pc.water.size(); i <= upto; i++) {
			TNode n = tr.nodes.get(i);
			double t = sampler.designedSurfaceAt(n.x, n.z);
			double prev = i == 0 ? Double.POSITIVE_INFINITY : pc.water.get(i - 1);
			double raw = Math.max(SEA_SURFACE, Math.min(Math.min(prev, n.ry), t));
			pc.terrain.add(t);
			pc.water.add(raw);
			// flat-reach quantization (spec §7.2, R2b-4a): hold a flat level, step DOWN to the
			// running min when it has descended ≥ REACH_STEP below the held level. Causal (level@i
			// depends only on raw[0..i]) → a pure prefix property, cached like the raw one.
			if (i == 0) {
				pc.level = raw;
			} else if (pc.level - raw >= REACH_STEP) {
				pc.level = raw;
			}
			pc.stepped.add(pc.level);
		}
		return pc;
	}

	private record Extent(int lastSegI, int nodeCount) {}

	/** Realized extent under the SAME geometry rules realize() applies (clip; estuary sea-trim). */
	private Extent realizedExtentOf(Src src) {
		Trace tr = traceOf(src);
		Clip clip = clipInfoOf(src);
		if (clip != null) {
			return new Extent(clip.nodeI, clip.nodeI + 2);
		}
		int len = tr.nodes.size();
		if (tr.terminal == Term.SEA) {
			Prefix pc = prefixOf(src, len - 1);
			int lastLand = -1;
			for (int i = 0; i < len; i++) {
				if (pc.terrain.get(i) >= 63) {
					lastLand = i;
				}
			}
			if (lastLand >= 0 && lastLand < len - 2) {
				len = lastLand + 2;
			}
		}
		return new Extent(len - 2, len);
	}

	// ---- Phase 3: realize — geometry + labels + water ----

	/**
	 * Realize every path sourced within [minCX-visPad .. maxCX+visPad] × [minCZ..maxCZ + pads]
	 * — byte-identical across any query shape containing them (the purity model). Synchronized:
	 * see the class doc's threading note.
	 */
	public synchronized Realized realize(int minCX, int minCZ, int maxCX, int maxCZ) {
		ArrayList<Src> sources = new ArrayList<>();
		for (int cz = minCZ - VIS_PAD; cz <= maxCZ + VIS_PAD; cz++) {
			for (int cx = minCX - VIS_PAD; cx <= maxCX + VIS_PAD; cx++) {
				sources.addAll(sourcesOfCell(cx, cz));
			}
		}
		sources.sort(RiverGraph::canonicalCompare);

		ArrayList<RiverPath> paths = new ArrayList<>();
		ArrayList<Lake> lakes = new ArrayList<>();
		for (Src src : sources) {
			Trace tr = traceOf(src);
			if (tr.nodes.size() < 2) {
				continue;
			}
			Clip clip = clipInfoOf(src);

			ArrayList<WNode> nodes = new ArrayList<>();
			String terminal;
			JoinTo joinTo = null;
			Double targetW = null;
			if (clip != null) {
				for (int i = 0; i <= clip.nodeI; i++) {
					TNode t = tr.nodes.get(i);
					nodes.add(new WNode(t.x, t.z, t.ry, t.lake, t.selfLoop, false));
				}
				// junction node at the closest approach, backed off 2 blocks along the incoming
				// segment — realized segments stop strictly short of the target
				TNode a = tr.nodes.get(clip.nodeI);
				TNode b = tr.nodes.get(clip.nodeI + 1);
				double segLen = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
				if (segLen == 0) {
					segLen = 1; // JS `|| 1`
				}
				double sAdj = Math.max(0, clip.s - 2 / segLen);
				double jx = a.x + (b.x - a.x) * sAdj;
				double jz = a.z + (b.z - a.z) * sAdj;
				nodes.add(new WNode(jx, jz, routeAt(jx, jz), false, false, true));
				// join validity (2-hop pure): junction must land within the TARGET's own realized
				// extent, and the target itself must be realized (≥ 4 nodes)
				Src target = clip.hit.src;
				Extent ext = realizedExtentOf(target);
				boolean valid = ext.nodeCount >= 4 && clip.hit.segI < ext.lastSegI;
				if (valid) {
					terminal = "join";
					joinTo = new JoinTo(clip.hit.srcKey, clip.hit.segI, clip.t);
					// junction water (R2b-4a): the target's STEPPED prefix level at the hit param —
					// the tributary pools into the target's flat reach (1-hop pure; the target's own
					// backwater raise never chains in)
					Prefix pw = prefixOf(target, clip.hit.segI + 1);
					double w0 = pw.stepped.get(clip.hit.segI);
					double w1 = pw.stepped.get(Math.min(clip.hit.segI + 1, pw.stepped.size() - 1));
					targetW = w0 + (w1 - w0) * clip.t;
				} else {
					terminal = "endorheic"; // phantom reach — tributary pools where its channel ends
				}
			} else {
				for (TNode t : tr.nodes) {
					nodes.add(new WNode(t.x, t.z, t.ry, t.lake, t.selfLoop, false));
				}
				terminal = tr.terminal == Term.SEA ? "sea" : "endorheic";
			}

			// water pass (spec §7.2): running min of (routing, designed terrain), floored at sea,
			// then QUANTIZED into flat reaches (R2b-4a). The trace-prefix part (raw running min +
			// stepped level + terrain) comes from the shared prefix cache; the junction node
			// continues BOTH the raw running min and the flat-reach stepping downstream.
			int lastTraceI = clip != null ? clip.nodeI : nodes.size() - 1;
			Prefix pc = prefixOf(src, lastTraceI);
			double wRaw = Double.POSITIVE_INFINITY;   // raw running min, carried into the junction node
			double level = Double.POSITIVE_INFINITY;  // current flat-reach level (stepped water)
			for (int i = 0; i < nodes.size(); i++) {
				WNode n = nodes.get(i);
				if (i <= lastTraceI) {
					n.waterY = level = pc.stepped.get(i);
					n.terrainY = pc.terrain.get(i);
					wRaw = pc.water.get(i);
				} else { // junction node (not part of the unclipped trace) — continue min + step
					double t = sampler.designedSurfaceAt(n.x, n.z);
					wRaw = Math.max(SEA_SURFACE, Math.min(Math.min(wRaw, n.ry), t));
					if (level - wRaw >= REACH_STEP) {
						level = wRaw;
					}
					n.waterY = level;
					n.terrainY = t;
				}
			}
			// backwater reconciliation: trailing reach pools at the target's junction level
			if (targetW != null && nodes.get(nodes.size() - 1).waterY < targetW - 1e-9) {
				for (int i = nodes.size() - 1; i >= 0 && nodes.get(i).waterY < targetW; i--) {
					nodes.get(i).waterY = targetW;
					nodes.get(i).backwater = true;
				}
			}
			// estuary trim: drop trailing sub-sea-terrain nodes, keeping one mouth node
			if (terminal.equals("sea")) {
				int lastLand = -1;
				for (int i = 0; i < nodes.size(); i++) {
					if (nodes.get(i).terrainY >= 63) {
						lastLand = i;
					}
				}
				if (lastLand >= 0 && lastLand < nodes.size() - 2) {
					nodes = new ArrayList<>(nodes.subList(0, lastLand + 2));
				}
			}
			if (nodes.size() < 4) {
				continue;
			}

			// stepped-reach metadata (spec §7.2, R2b-4a), derived from the FINAL flat-per-reach
			// water (post backwater + estuary trim): reachId (per-path monotone pool index),
			// stepDrop (the drop at a step node, else 0), lip (a step whose drop ≥ LIP_STEP =
			// WATERFALL_MIN_DROP — the SAME lip test as R1, now reading the stepped water). Flat
			// reach segments carry one assigned level so their drop is exactly 0.
			nodes.get(0).reachId = 0;
			nodes.get(0).stepDrop = 0;
			nodes.get(0).lip = false;
			int rid = 0;
			for (int i = 1; i < nodes.size(); i++) {
				double d = nodes.get(i - 1).waterY - nodes.get(i).waterY;
				if (d > 1e-9) {
					rid++;
					nodes.get(i).stepDrop = d;
					nodes.get(i).lip = d >= LIP_STEP;
				} else {
					nodes.get(i).stepDrop = 0;
					nodes.get(i).lip = false;
				}
				nodes.get(i).reachId = rid;
			}
			int lastI = clip != null ? clip.nodeI : nodes.size() - 1;
			for (TLake lk : tr.lakes) {
				if (lk.idx() <= lastI && lk.idx() < nodes.size()) {
					lakes.add(new Lake(lk.x(), lk.z(), nodes.get(lk.idx()).waterY, false, false, false, false));
				}
			}
			if (terminal.equals("endorheic")) {
				WNode end = nodes.get(nodes.size() - 1);
				lakes.add(new Lake(end.x, end.z, end.waterY, true,
						clip != null,                                              // phantom
						clip == null && tr.terminal == Term.SELF,                  // selfLoop
						clip == null && (tr.terminal == Term.MAXLEN || tr.terminal == Term.REACH))); // truncated
			}

			ArrayList<PathNode> frozen = new ArrayList<>(nodes.size());
			for (WNode n : nodes) {
				frozen.add(new PathNode(n.x, n.z, n.ry, n.waterY, n.terrainY,
						n.lip, n.junction, n.backwater, n.lake, n.selfLoop, n.stepDrop, n.reachId));
			}
			paths.add(new RiverPath(new SourceId(src.cellX, src.cellZ, src.idx), terminal, joinTo,
					List.copyOf(frozen)));
		}
		return new Realized(List.copyOf(paths), List.copyOf(lakes));
	}

	/** Instrumentation mirror of the JS stats(): cells walked and sources traced so far. */
	public synchronized int[] stats() {
		return new int[] {walkedCells.size(), traceCache.size()};
	}
}
