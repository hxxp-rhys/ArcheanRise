package dev.archeanrise.rivers;

/**
 * River-graph constants — the Java mirror of {@code DEFAULT_RC}, {@code RING_TABLE} and
 * {@code LAKE_TABLE} in {@code tools/preview/rivers-r1.mjs} (THE algorithm of record, per
 * {@code docs/research/14-rivers-r2-design.md} §0). Every value here is copied VERBATIM from that
 * file; the two direction tables are frozen double literals shared by BOTH implementations —
 * neither side may call {@code cos}/{@code sin} at runtime (JS and Java transcendentals are not
 * ULP-identical, doc 14 bit-parity rules). Change nothing here without changing rivers-r1.mjs in
 * the same commit and re-running the R2a parity gate ({@code tools/measure/river-parity.mjs}).
 *
 * <p>The determinism pads are the CODED inequality from rivers-r1.mjs (never hand-derived):
 * every trace lies within {@link #TRACE_REACH} of its source, so two traces interact only when
 * their sources are within {@link #INTERACT_R}; the cell pads are the ceil-covers of those radii.
 */
public final class RiverConstants {
	private RiverConstants() {}

	// ---- spec §7.5 river config (rivers-r1.mjs DEFAULT_RC, copied verbatim) ----
	/** Source-cell size (blocks). */
	public static final int RIVER_CELL = 4096;
	/** 0..N jittered candidate sources per cell. */
	public static final int SOURCES_PER_CELL = 3;
	/** Sources only on highland (routing baseline). */
	public static final double MIN_SOURCE_Y = 140;
	/** Trace step length (blocks). */
	public static final double RIVER_STEP = 112;
	/** Trace budget (along-path blocks). */
	public static final double RIVER_MAX_LEN = 12000;
	/** Displacement cap from source — the determinism pad driver. */
	public static final double REACH_MAX = 5000;
	/** Segment-to-segment confluence distance. */
	public static final double JOIN_RADIUS = 48;
	/** Own-path proximity that closes a loop. */
	public static final double SELF_JOIN_RADIUS = 24;
	/** Low-octave macro contribution to the routing surface (baked into the emitted routing DF). */
	public static final double K_ROUTE = 0.5;
	/** Heading persistence (blocks of routing-height equivalent). */
	public static final double INERTIA = 14;
	/** Deterministic heading wobble amplitude (same units). */
	public static final double MEANDER = 10;
	/** Per-step drop that marks a waterfall lip. */
	public static final double LIP_STEP = 4;
	/** Flat-reach quantization step (spec §7.2, R2b-4a): the running min must descend this far below
	 * a reach's flat level before the stepped water drops to a new reach (= the max pool-dam perch).
	 * Copied verbatim from rivers-r1.mjs DEFAULT_RC.REACH_STEP (tuned to 3 — DECISIONS "RIVERS
	 * R2b-4a"; the largest value that still yields the spec's 2..3-block RAPIDS_DROP class). */
	public static final double REACH_STEP = 3;
	/** How far to look for a lake outlet before flagging endorheic. */
	public static final double LAKE_SEARCH_MAX = 1024;
	/** Geometric radial growth of the outlet search. */
	public static final double LAKE_RING_GROW = 1.6;
	/** Outlet-search directions per ring. */
	public static final int LAKE_RING_DIRS = 8;
	/** Candidate directions per step. */
	public static final int RING_DIRS = 10;
	/** Routing rise tolerated before a step counts as uphill. */
	public static final double FLAT_EPS = 1.0;
	/** Straight-ahead drop (blocks/step) that skips the candidate ring. */
	public static final double STEEP_FAST = 2.0;
	/** Water-level floor — mirrors the world contract's SEA_LEVEL 63. */
	public static final double SEA_SURFACE = 63;

	/** RING_DIRS candidate directions — frozen (cos, sin) double literals from rivers-r1.mjs. */
	public static final double[][] RING_TABLE = {
			{1, 0},
			{0.8090169943749475, 0.5877852522924731},
			{0.30901699437494745, 0.9510565162951535},
			{-0.30901699437494734, 0.9510565162951536},
			{-0.8090169943749473, 0.5877852522924732},
			{-1, 1.2246467991473532e-16},
			{-0.8090169943749475, -0.587785252292473},
			{-0.30901699437494756, -0.9510565162951535},
			{0.30901699437494723, -0.9510565162951536},
			{0.8090169943749473, -0.5877852522924734},
	};
	/** LAKE_RING_DIRS outlet-search directions — frozen (cos, sin) double literals. */
	public static final double[][] LAKE_TABLE = {
			{1, 0},
			{0.7071067811865476, 0.7071067811865475},
			{6.123233995736766e-17, 1},
			{-0.7071067811865475, 0.7071067811865476},
			{-1, 1.2246467991473532e-16},
			{-0.7071067811865477, -0.7071067811865475},
			{-1.8369701987210297e-16, -1},
			{0.7071067811865474, -0.7071067811865477},
	};

	// ---- pad derivation (the CODED inequality — do not hand-derive; rivers-r1.mjs header) ----
	/** Every trace lies within this displacement of its source (REACH_MAX + one overshoot step). */
	public static final double TRACE_REACH = REACH_MAX + Math.max(RIVER_STEP, LAKE_SEARCH_MAX);
	/** Two traces can only come within JOIN_RADIUS when their sources are within this. */
	public static final double INTERACT_R = 2 * TRACE_REACH + JOIN_RADIUS;
	/** Cells of pad so every trace able to reach a region is sourced (ceil cover of TRACE_REACH). */
	public static final int VIS_PAD = (int) Math.ceil(TRACE_REACH / RIVER_CELL);
	/** Cells of pad covering the clip-exactness neighborhood (ceil cover of INTERACT_R). */
	public static final int JOIN_PAD = (int) Math.ceil(INTERACT_R / RIVER_CELL);

	static {
		if (JOIN_PAD * RIVER_CELL < INTERACT_R) {
			throw new IllegalStateException("river pad inequality violated — unreachable by construction of ceil");
		}
		// Bit-parity guard (doc 14 §0): the walk's wobble hash and the 64-block routing memo use
		// JS Math.round, whose spec ("closest integral value; ties toward +Infinity", with
		// round(0.49999999999999994) == 0 — NOT floor(x+0.5), which double-rounds to 1) matches
		// Java's Math.round(double) contract exactly on every finite input (verified empirically
		// against V8/Node 24 — see the R2a report). This check makes the port self-verifying on
		// the one input where naive floor(x+0.5) implementations differ.
		if (Math.round(0.49999999999999994) != 0L || Math.round(-0.5) != 0L || Math.round(2.5) != 3L
				|| Math.round(-2.5) != -2L) {
			throw new IllegalStateException("Math.round does not match JS Math.round semantics on this JVM");
		}
	}
}
