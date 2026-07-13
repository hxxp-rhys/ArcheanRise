package dev.archeanrise.rivers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The spec §7.2–§7.3 channel CARVE geometry — a BIT-FOR-BIT Java port of
 * {@code tools/preview/river-carve.mjs} (THE algorithm of record; see
 * {@code docs/research/14-rivers-r2-design.md} and DECISIONS "RIVERS R2b-1"). Given the
 * R1/R2a-proven {@link RiverGraph.Realized} graph, {@link Carver#carveAt} returns the carved
 * designed surface HEIGHT at a column, exactly as the JS reference does. The R2b-2 carve-parity
 * gate ({@code tools/measure/river-carve-parity.mjs}) proves this class and the JS reference
 * produce numerically identical carved heights + zones on the same graph, coordinate and natural
 * height — so read river-carve.mjs for the algorithm rationale; this file documents only the port.
 *
 * <p><b>Bit-parity discipline</b> (doc 14 §0, mirrored from river-carve.mjs): distances via
 * {@code Math.sqrt(dx*dx+dz*dz)} only (never {@code Math.hypot}); {@link #smoothstep} and the
 * stepped-pool bed + dam + spill-lip logic (R2b-4b) copied verbatim; the frozen constants
 * and {@link #LAKE_R} are the exact JS literals. The spatial bucket index reproduces the JS grid;
 * the nearest-segment scan uses a strict {@code <} on squared distance, which makes re-processing a
 * segment that lands in more than one 3×3 bucket a NO-OP (a tie never displaces the first-seen
 * winner) — so the JS {@code seen} de-dup set is a pure perf detail here and is omitted (the result
 * is byte-identical either way; the grid still de-dups consecutive same-index samples for speed).
 *
 * <p><b>Threading:</b> a {@link Carver} is immutable after {@link #buildCarver} returns; its arrays
 * and the two bucket maps are never mutated, so concurrent {@link Carver#carveAt} calls from the
 * chunk-gen worker pool are safe. Nothing here reads live-world state — the carve is a pure
 * function of (graph, column, natural height). No client imports (loader-neutral).
 */
public final class RiverCarve {
	private RiverCarve() {}

	// ---- DEFAULT_CC (river-carve.mjs, copied verbatim) ----
	// R2b-4e (spec §7.3, carve-manifest diagnosis): W_MIN 3→8 so the bed spans ≥2 horizontal noise
	// cells (grid-resolvable — a 4-wide cell can no longer bridge a narrow bed back to solid);
	// DEPTH_MIN/MAX 2/6→4/8 sinks the bed ~2 blocks further to beat the ~+1.5–2-block quarter-negative
	// bias of the 8-tall cell. The graded valley WALLS come from the {@code sink} river_carve mode
	// (Part 1) which grades the perching mountainside down toward the reach water level over the halo.
	/** width(d_along) = clamp(W_MIN + K_W·√d_along, W_MIN, W_MAX). */
	public static final double W_MIN = 8;
	public static final double W_MAX = 24;
	public static final double K_W = 0.18;
	/** depth maxes at the same along-distance width does. */
	public static final double DEPTH_MIN = 4;
	public static final double DEPTH_MAX = 8;
	public static final double K_D = 0.0343;
	/** bank blend distance beyond the channel half-width. */
	public static final double VALLEY_HALO = 64;
	/** lake-basin halo blend cap (river pools use {@link #MAX_DAM_H}). */
	public static final double LEVEE_H = 2;
	/** spec §7.5 / R2b-4a: max natural pool-dam raise holding a flat pool above low ground. */
	public static final double MAX_DAM_H = 8;
	/** the downstream spill crest sits this far below the pool surface (spec §7.2 lip). */
	public static final double LIP_SPILL_DROP = 1;
	/** along-channel length (blocks) of the spill-crest sill upstream of a step node. */
	public static final double LIP_ALONG = 4;
	/** R2b-4e: the spill crest stays BED at spillY this far beyond the half-width so the sill spans the
	 *  channel as a coherent dam and never dips into HALO→natural (the Q3 high-cascade sill defect). */
	public static final double LIP_CREST_EXTRA = 2;
	/** flat lake-basin depth below the lake surface. */
	public static final double LAKE_DEPTH = 4;
	/** spatial bucket size (≥ W_MAX/2 + VALLEY_HALO = 76). */
	public static final double GRID = 128;
	/** lake basin radius (spec LAKE_R 24..96; a fixed mid value for R2b). */
	public static final double LAKE_R = 48;

	/** Cross-section classification at a column. Stable ordinals — the parity gate compares them. */
	public enum Zone {
		OUTSIDE, // 0 — untouched natural terrain
		BED,     // 1 — flat channel bed = reachSurface − depth
		HALO,    // 2 — bank blend back to natural (smoothstep)
		LAKE;    // 3 — flat lake basin = lakeLevel − LAKE_DEPTH

		public int code() {
			return ordinal();
		}
	}

	/** carveAt result — mirrors the JS return object (every field the probes/gates read). */
	public static final class Result {
		public final double height;
		public final Zone zone;
		public final double reachW;
		public final double depth;
		public final double width;
		public final double rim;
		public final double d;
		public final int segIdx;

		Result(double height, Zone zone, double reachW, double depth, double width, double rim,
				double d, int segIdx) {
			this.height = height;
			this.zone = zone;
			this.reachW = reachW;
			this.depth = depth;
			this.width = width;
			this.rim = rim;
			this.d = d;
			this.segIdx = segIdx;
		}
	}

	/**
	 * R2c water-mask sample (see {@link Carver#sampleWater}) — the nearest WETTED feature at a column.
	 * {@code level} = the water surface Y (L), {@code dEdge} = distance from that feature's channel/basin
	 * EDGE (≤0 inside the bed/basin, growing outward), {@code depth} = so the bed sits at
	 * {@code level − depth}. {@code level} is {@link Double#NaN} when no feature is in range.
	 */
	public static final class WaterSample {
		public final double level;
		public final double dEdge;
		public final double depth;

		WaterSample(double level, double dEdge, double depth) {
			this.level = level;
			this.dEdge = dEdge;
			this.depth = depth;
		}

		public boolean present() {
			return !Double.isNaN(level);
		}
	}

	private static double smoothstep(double t) {
		if (t <= 0) {
			return 0;
		}
		if (t >= 1) {
			return 1;
		}
		return t * t * (3 - 2 * t);
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}

	/** JS bkey: bx·2^32 + bz — injective for |bx|,|bz| &lt; 2^31 (in-world bucket magnitudes). */
	private static long bkey(long bx, long bz) {
		return bx * 0x100000000L + bz;
	}

	/** One realized reach segment: endpoints, per-endpoint water level, cumulative along-path start. */
	private static final class Seg {
		final double ax;
		final double az;
		final double bx;
		final double bz;
		final double wa;
		final double wb;
		final double cumA;
		final double len;

		Seg(double ax, double az, double bx, double bz, double wa, double wb, double cumA, double len) {
			this.ax = ax;
			this.az = az;
			this.bx = bx;
			this.bz = bz;
			this.wa = wa;
			this.wb = wb;
			this.cumA = cumA;
			this.len = len;
		}
	}

	/**
	 * A carver over a realized graph — precomputes along-path cumulative distance + a spatial bucket
	 * index. Immutable; {@link #carveAt} is a pure, thread-safe query. Mirrors the JS
	 * {@code buildCarver(...)} closure return ({@code carveAt}, {@code segCount}, {@code lakeCount}).
	 */
	public static final class Carver {
		private final Seg[] segs;
		private final double[] lakeX;
		private final double[] lakeZ;
		private final double[] lakeLevel;
		private final Map<Long, int[]> grid;
		private final Map<Long, int[]> lakeGrid;

		Carver(Seg[] segs, double[] lakeX, double[] lakeZ, double[] lakeLevel,
				Map<Long, int[]> grid, Map<Long, int[]> lakeGrid) {
			this.segs = segs;
			this.lakeX = lakeX;
			this.lakeZ = lakeZ;
			this.lakeLevel = lakeLevel;
			this.grid = grid;
			this.lakeGrid = lakeGrid;
		}

		public int segCount() {
			return segs.length;
		}

		public int lakeCount() {
			return lakeX.length;
		}

		/**
		 * The carved designed HEIGHT + zone at (x, z) given the natural designed height there —
		 * verbatim port of river-carve.mjs {@code carveAt}. Pure: the graph geometry is fixed, only
		 * the halo/levee blend consumes {@code naturalH}.
		 */
		public Result carveAt(double x, double z, double naturalH) {
			long bx = (long) Math.floor(x / GRID);
			long bz = (long) Math.floor(z / GRID);

			// nearest river segment in the 3×3 bucket neighborhood (strict < → first-seen tiebreak,
			// reprocessing a multi-bucket segment is a no-op, so no de-dup set is needed)
			double bestD2 = Double.POSITIVE_INFINITY;
			double bestT = 0;
			int bestIdx = -1;
			for (long dz = -1; dz <= 1; dz++) {
				for (long dx = -1; dx <= 1; dx++) {
					int[] arr = grid.get(bkey(bx + dx, bz + dz));
					if (arr == null) {
						continue;
					}
					for (int idx : arr) {
						Seg s = segs[idx];
						double ux = s.bx - s.ax;
						double uz = s.bz - s.az;
						double len2 = ux * ux + uz * uz;
						double t = len2 > 0 ? ((x - s.ax) * ux + (z - s.az) * uz) / len2 : 0;
						t = clamp(t, 0, 1);
						double qx = s.ax + t * ux;
						double qz = s.az + t * uz;
						double d2 = (x - qx) * (x - qx) + (z - qz) * (z - qz);
						if (d2 < bestD2) {
							bestD2 = d2;
							bestT = t;
							bestIdx = idx;
						}
					}
				}
			}

			// nearest lake — center bucket only (LAKE_R 48 < GRID 128, so a lake within range is
			// registered into the query bucket by its 3×3 footprint; mirrors the JS single-bucket read)
			double bestLakeD2 = Double.POSITIVE_INFINITY;
			int bestLake = -1;
			int[] larr = lakeGrid.get(bkey(bx, bz));
			if (larr != null) {
				for (int idx : larr) {
					double d2 = (x - lakeX[idx]) * (x - lakeX[idx]) + (z - lakeZ[idx]) * (z - lakeZ[idx]);
					if (d2 < bestLakeD2) {
						bestLakeD2 = d2;
						bestLake = idx;
					}
				}
			}

			boolean haveRiver = false;
			double height = naturalH;
			Zone zone = Zone.OUTSIDE;
			double reachW = 0;
			double depth = 0;
			double width = 0;
			double rim = naturalH;
			double d = Double.POSITIVE_INFINITY;
			int segIdx = -1;

			if (bestIdx >= 0) {
				Seg s = segs[bestIdx];
				double dd = Math.sqrt(bestD2);
				double along = s.cumA + bestT * s.len;
				double w = clamp(W_MIN + K_W * Math.sqrt(along), W_MIN, W_MAX);
				double dp = clamp(DEPTH_MIN + K_D * Math.sqrt(along), DEPTH_MIN, DEPTH_MAX);
				double half = w / 2;
				// stepped-pool bed (R2b-4b, spec §7.2) — verbatim mirror of river-carve.mjs. A RISER
				// segment (wa > wb: b is a step node, == node.stepDrop > 0) is its UPPER pool's flat
				// surface; the drop is concentrated at node b, and the last LIP_ALONG blocks before b
				// rise to the spill crest (reachW − LIP_SPILL_DROP). Flat reaches (wa == wb) and
				// backwater up-steps keep the interpolation + a flat floor.
				double drop = s.wa - s.wb;
				double rW;
				double bedH;
				boolean isCrest = false;
				if (drop > 1e-9) {
					rW = s.wa;
					double alongB = (1 - bestT) * s.len;
					isCrest = alongB < LIP_ALONG;
					bedH = isCrest ? rW - LIP_SPILL_DROP : rW - dp;
				} else {
					rW = s.wa + (s.wb - s.wa) * bestT;
					bedH = rW - dp;
				}
				// pool dam (spec §7.3 containment, R2b-4b): MAX_DAM_H (was LEVEE_H) holds the flat
				// pool; the reachSurface+1 side dams sit above the spill crest.
				double rm = Math.max(naturalH, Math.min(rW + 1, naturalH + MAX_DAM_H));
				// R2b-4e: at a spill crest the BED (held at spillY) extends LIP_CREST_EXTRA beyond the
				// half-width so the sill spans the channel as a coherent dam instead of dropping into
				// HALO→natural (the Q3 high-cascade sill defect). Verbatim mirror of river-carve.mjs.
				double bedHalf = isCrest ? half + LIP_CREST_EXTRA : half;
				if (dd <= bedHalf) {
					haveRiver = true;
					zone = Zone.BED;
					height = bedH;
					reachW = rW;
					depth = dp;
					width = w;
					rim = rm;
					d = dd;
					segIdx = bestIdx;
				} else if (dd <= half + VALLEY_HALO) {
					double f = smoothstep((dd - half) / VALLEY_HALO);
					haveRiver = true;
					zone = Zone.HALO;
					height = rm + (naturalH - rm) * f;
					reachW = rW;
					depth = dp;
					width = w;
					rim = rm;
					d = dd;
					segIdx = bestIdx;
				}
			}

			// lake basin (flat), overrides the river only when it cuts more (a lake at a river mouth
			// reads as water) — same "!river || lake.height < river.height" precedence as the JS
			if (bestLake >= 0 && bestLakeD2 <= LAKE_R * LAKE_R) {
				double dl = Math.sqrt(bestLakeD2);
				double lvl = lakeLevel[bestLake];
				double rm = Math.max(naturalH, Math.min(lvl + 1, naturalH + LEVEE_H));
				Zone lzone;
				double lheight;
				if (dl <= LAKE_R - VALLEY_HALO) {
					lzone = Zone.LAKE;
					lheight = lvl - LAKE_DEPTH;
				} else {
					double f = smoothstep((dl - (LAKE_R - VALLEY_HALO)) / VALLEY_HALO);
					lzone = Zone.HALO;
					lheight = rm + (naturalH - rm) * f;
				}
				if (!haveRiver || lheight < height) {
					haveRiver = true;
					zone = lzone;
					height = lheight;
					reachW = lvl;
					depth = LAKE_DEPTH;
					width = 2 * LAKE_R;
					rim = rm;
					d = dl;
					segIdx = -1;
				}
			}

			if (!haveRiver) {
				return new Result(naturalH, Zone.OUTSIDE, 0, 0, 0, naturalH, Double.POSITIVE_INFINITY, -1);
			}
			return new Result(height, zone, reachW, depth, width, rim, d, segIdx);
		}

		/**
		 * R2c water/mask geometry at a column (doc 11 §5a aquifer trio; the R2c scratchpad plan): the
		 * nearest WETTED feature's water surface {@code level} (L = reach water / lake level), the
		 * distance {@code dEdge} from that feature's channel/basin EDGE, and its {@code depth} (bed =
		 * {@code level − depth}). Mirrors {@link #carveAt}'s nearest-segment + nearest-lake bucket scan
		 * but returns the raw geometry the water masks + carver shield consume — NOT part of the R2b
		 * carve-parity gate ({@link #carveAt} is byte-unchanged), so no JS reference is needed. Pure and
		 * thread-safe (immutable carver, no world state). Exact for {@code dEdge} within the carve
		 * influence bound (W_MAX/2 + VALLEY_HALO = 76) — the same 3×3 completeness bound carveAt uses;
		 * callers apply their own reach cutoff (≤ that bound).
		 */
		public WaterSample sampleWater(double x, double z) {
			long bx = (long) Math.floor(x / GRID);
			long bz = (long) Math.floor(z / GRID);

			// nearest river segment — identical scan to carveAt (strict < first-seen tiebreak)
			double bestD2 = Double.POSITIVE_INFINITY;
			double bestT = 0;
			int bestIdx = -1;
			for (long dz = -1; dz <= 1; dz++) {
				for (long dx = -1; dx <= 1; dx++) {
					int[] arr = grid.get(bkey(bx + dx, bz + dz));
					if (arr == null) {
						continue;
					}
					for (int idx : arr) {
						Seg s = segs[idx];
						double ux = s.bx - s.ax;
						double uz = s.bz - s.az;
						double len2 = ux * ux + uz * uz;
						double t = len2 > 0 ? ((x - s.ax) * ux + (z - s.az) * uz) / len2 : 0;
						t = clamp(t, 0, 1);
						double qx = s.ax + t * ux;
						double qz = s.az + t * uz;
						double d2 = (x - qx) * (x - qx) + (z - qz) * (z - qz);
						if (d2 < bestD2) {
							bestD2 = d2;
							bestT = t;
							bestIdx = idx;
						}
					}
				}
			}

			double riverEdge = Double.POSITIVE_INFINITY;
			double riverLevel = Double.NaN;
			double riverDepth = 0;
			if (bestIdx >= 0) {
				Seg s = segs[bestIdx];
				// snap the water surface to the flat pool on a RISER (mirror of carveAt): the whole
				// segment is the UPPER pool, so R2c fills it to wa (not the interpolated ramp). Flat
				// reaches are unchanged (wa == wb). NOT parity-gated (sampleWater has no JS mirror).
				double rW = s.wa - s.wb > 1e-9 ? s.wa : s.wa + (s.wb - s.wa) * bestT;
				double along = s.cumA + bestT * s.len;
				double w = clamp(W_MIN + K_W * Math.sqrt(along), W_MIN, W_MAX);
				double dp = clamp(DEPTH_MIN + K_D * Math.sqrt(along), DEPTH_MIN, DEPTH_MAX);
				riverEdge = Math.sqrt(bestD2) - w / 2;
				riverLevel = rW;
				riverDepth = dp;
			}

			// nearest lake — center bucket only (mirrors carveAt; LAKE_R 48 < GRID 128)
			double lakeEdge = Double.POSITIVE_INFINITY;
			double lakeLevelV = Double.NaN;
			int[] larr = lakeGrid.get(bkey(bx, bz));
			if (larr != null) {
				double bestLakeD2 = Double.POSITIVE_INFINITY;
				int bestLake = -1;
				for (int idx : larr) {
					double d2 = (x - lakeX[idx]) * (x - lakeX[idx]) + (z - lakeZ[idx]) * (z - lakeZ[idx]);
					if (d2 < bestLakeD2) {
						bestLakeD2 = d2;
						bestLake = idx;
					}
				}
				if (bestLake >= 0) {
					lakeEdge = Math.sqrt(bestLakeD2) - LAKE_R;
					lakeLevelV = lakeLevel[bestLake];
				}
			}

			if (Double.isNaN(riverLevel) && Double.isNaN(lakeLevelV)) {
				return new WaterSample(Double.NaN, Double.POSITIVE_INFINITY, 0);
			}
			// the nearest WETTED feature by edge distance (a lake basin uses the flat LAKE_DEPTH)
			if (lakeEdge < riverEdge) {
				return new WaterSample(lakeLevelV, lakeEdge, LAKE_DEPTH);
			}
			return new WaterSample(riverLevel, riverEdge, riverDepth);
		}
	}

	/**
	 * Build a carver over a realized graph — verbatim port of river-carve.mjs {@code buildCarver}:
	 * precomputes per-path cumulative along-distance, buckets every segment into the {@link #GRID}
	 * cells it passes through (sampled along its length, consecutive-duplicate suppressed), and
	 * indexes lakes into their 3×3 footprint.
	 */
	public static Carver buildCarver(RiverGraph.Realized graph) {
		ArrayList<Seg> segs = new ArrayList<>();
		for (RiverGraph.RiverPath p : graph.paths()) {
			double cum = 0;
			List<RiverGraph.PathNode> nodes = p.nodes;
			for (int i = 1; i < nodes.size(); i++) {
				RiverGraph.PathNode a = nodes.get(i - 1);
				RiverGraph.PathNode b = nodes.get(i);
				double len = Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.z - a.z) * (b.z - a.z));
				segs.add(new Seg(a.x, a.z, b.x, b.z, a.waterY, b.waterY, cum, len));
				cum += len;
			}
		}

		HashMap<Long, ArrayList<Integer>> gridB = new HashMap<>();
		for (int idx = 0; idx < segs.size(); idx++) {
			Seg s = segs.get(idx);
			int n = Math.max(1, (int) Math.ceil(s.len / GRID));
			for (int j = 0; j <= n; j++) {
				double x = s.ax + (s.bx - s.ax) * ((double) j / n);
				double z = s.az + (s.bz - s.az) * ((double) j / n);
				put(gridB, bkey((long) Math.floor(x / GRID), (long) Math.floor(z / GRID)), idx);
			}
		}

		List<RiverGraph.Lake> lakes = graph.lakes();
		double[] lakeX = new double[lakes.size()];
		double[] lakeZ = new double[lakes.size()];
		double[] lakeLevel = new double[lakes.size()];
		HashMap<Long, ArrayList<Integer>> lakeGridB = new HashMap<>();
		for (int idx = 0; idx < lakes.size(); idx++) {
			RiverGraph.Lake lk = lakes.get(idx);
			lakeX[idx] = lk.x;
			lakeZ[idx] = lk.z;
			lakeLevel[idx] = lk.level;
			long bx = (long) Math.floor(lk.x / GRID);
			long bz = (long) Math.floor(lk.z / GRID);
			for (long dz = -1; dz <= 1; dz++) {
				for (long dx = -1; dx <= 1; dx++) {
					lakeGridB.computeIfAbsent(bkey(bx + dx, bz + dz), k -> new ArrayList<>()).add(idx);
				}
			}
		}

		return new Carver(segs.toArray(new Seg[0]), lakeX, lakeZ, lakeLevel,
				freeze(gridB), freeze(lakeGridB));
	}

	/** JS {@code if (arr[arr.length-1] !== idx) arr.push(idx)} — consecutive-duplicate suppression. */
	private static void put(HashMap<Long, ArrayList<Integer>> grid, long key, int idx) {
		ArrayList<Integer> arr = grid.computeIfAbsent(key, k -> new ArrayList<>());
		if (arr.isEmpty() || arr.get(arr.size() - 1) != idx) {
			arr.add(idx);
		}
	}

	/** Freeze the build-time bucket lists into flat int[] (no boxing in the carveAt hot path). */
	private static Map<Long, int[]> freeze(HashMap<Long, ArrayList<Integer>> src) {
		HashMap<Long, int[]> out = new HashMap<>(src.size() * 2);
		for (Map.Entry<Long, ArrayList<Integer>> e : src.entrySet()) {
			ArrayList<Integer> list = e.getValue();
			int[] a = new int[list.size()];
			for (int i = 0; i < a.length; i++) {
				a[i] = list.get(i);
			}
			out.put(e.getKey(), a);
		}
		return out;
	}
}
