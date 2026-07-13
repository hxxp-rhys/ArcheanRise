package dev.archeanrise.sitegrading;

import net.minecraft.util.Mth;

/**
 * The slope-limited "design surface" every piece of a graded structure snaps to.
 *
 * Built from a 9x9 probe grid (pitch 32, extent +/-128 around the ORIGINAL chunk center —
 * covers the footprint and every shift candidate; blueprint verifier D1/D3). Heights come
 * from the vanilla preliminary-surface predictor, so the surface is a pure deterministic
 * function of (seed, chunk). Queries outside the grid clamp to the edge (specified, so all
 * implementations are bit-identical).
 *
 * D(x,z) = anchor + clamp(interp(H)(x,z) - anchor, -G, +G); pieces are clamped to D +/- G.
 */
public final class DesignSurface {
	public static final int GRID_N = 9;
	public static final int PITCH = 32;

	private final int originX; // world x of grid node [0]
	private final int originZ;
	private final int[] heights; // row-major GRID_N x GRID_N, predictor surface heights
	private final int anchor;
	/**
	 * The flat-snap platform, captured from the START piece's REAL heightmap projection (the first
	 * {@link #clampProjection} call — addPieces projects the start before any child). The predictor-
	 * median {@code anchor} is a MODEL height and historically read below the true surface (the
	 * 2026-07-10 buried-structures fix); the platform must be the reality the world actually builds.
	 * volatile: written during addPieces, read by the DEFERRED Placer BFS, which may run on another
	 * worker thread (the stub consumer re-arms the holder there).
	 */
	private volatile int platform = Integer.MIN_VALUE;

	DesignSurface(int originX, int originZ, int[] heights, int anchor) {
		this.originX = originX;
		this.originZ = originZ;
		this.heights = heights;
		this.anchor = anchor;
	}

	public int anchor() {
		return anchor;
	}

	int heightAtNode(int gx, int gz) {
		gx = Mth.clamp(gx, 0, GRID_N - 1);
		gz = Mth.clamp(gz, 0, GRID_N - 1);
		return heights[gz * GRID_N + gx];
	}

	/** Bilinear interpolation of the probe grid, clamp-to-edge. */
	public double interpolatedHeight(int x, int z) {
		double gx = (x - originX) / (double) PITCH;
		double gz = (z - originZ) / (double) PITCH;
		int x0 = Mth.floor(gx);
		int z0 = Mth.floor(gz);
		double fx = Mth.clamp(gx - x0, 0.0, 1.0);
		double fz = Mth.clamp(gz - z0, 0.0, 1.0);
		return Mth.lerp2(fx, fz,
				heightAtNode(x0, z0), heightAtNode(x0 + 1, z0),
				heightAtNode(x0, z0 + 1), heightAtNode(x0 + 1, z0 + 1));
	}

	/** The design surface D(x,z): terrain-following, clamped to anchor +/- G. */
	public int designHeight(int x, int z) {
		double h = interpolatedHeight(x, z);
		return anchor + Mth.clamp((int) Math.round(h - anchor),
				-SiteGrading.GRADE_BUDGET, SiteGrading.GRADE_BUDGET);
	}

	/**
	 * Piece heightmap projection — the 3c FLAT-SNAP INTERLOCK (2026-07-07). Gated on
	 * {@code siteGradingCutFill}:
	 * <ul>
	 *   <li><b>cutFill ON</b> → return the flat {@code anchor}: every house AND street projects to ONE
	 *       plane, so the pieces are coplanar (path junctions differ by 0 — the vanilla ±3–4 block
	 *       template spread that breaks village paths is eliminated by construction). The SiteGrading v2
	 *       CUT+FILL pass ({@link SiteCut}) then grades the terrain to that flat platform (cut the hill
	 *       down, fill the dips/water up, resurface), so the coplanar pieces are neither buried nor
	 *       floating. This is the "placement only flattens when the CUT grades to the platform" interlock.</li>
	 *   <li><b>cutFill OFF</b> → pass through {@code vanillaHeight} (the real WORLD_SURFACE_WG): vanilla
	 *       per-piece projection, villages ramp with the terrain. The interim used when the earthwork is
	 *       off — flat-snapping WITHOUT grading leaves pieces inset/floating on slopes (why this was
	 *       reverted in v0.2.13), so the two MUST move together.</li>
	 * </ul>
	 * {@code x,z} unused (the platform is flat); kept for the WrapOperation call shape.
	 */
	public int clampProjection(int x, int z, int vanillaHeight) {
		var config = dev.archeanrise.ArcheanRise.config;
		// Flat-snap ONLY when the grade will actually run (cutFill AND cut). Flat-snapping without the
		// earthwork leaves coplanar pieces inset/floating on slopes — the reverted-v0.2.13 failure — so the
		// two must move together; cut=false (field-hash-only) keeps vanilla per-piece projection.
		if (config != null && config.siteGradingCutFill && config.siteGradingCut) {
			// 2026-07-10 buried-structures fix: the platform is the START piece's REAL projected height
			// (first call = start piece; vanilla computed it anyway and we used to discard it), NOT the
			// predictor-median anchor — the predictor model read 6–56 blocks below the true surface, so
			// anchor-platformed villages were built underground and the cut+fill (whose targets share
			// the same model) never planned the rescue. Pieces stay coplanar (one captured Y), and the
			// TargetField/GradePad planes follow automatically (both derive from the piece boxes).
			if (platform == Integer.MIN_VALUE) {
				platform = vanillaHeight;
			}
			return platform; // flat-snap: coplanar pieces on the platform the CUT+FILL grades to
		}
		return vanillaHeight; // interim / field-only: vanilla per-piece projection (no earthwork)
	}
}
