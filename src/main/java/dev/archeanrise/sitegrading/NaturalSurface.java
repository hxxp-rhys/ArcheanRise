package dev.archeanrise.sitegrading;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Coarse predictor-sampled natural surface over an arbitrary envelope, bilinearly interpolated.
 *
 * The heights come from the vanilla preliminary-surface predictor
 * (initial_density_without_jaggedness, threshold {@link #THRESHOLD}) — the solid floor below any
 * water, texture-blind — so the surface is a PURE FUNCTION OF (seed, position) with no live-world
 * read. That is what lets the {@link TargetField} be computed identically in every chunk that
 * touches a structure (cross-chunk determinism), unlike reading the block world (feature-spill
 * dependent) or the WG heightmaps (only valid for current-chunk columns).
 *
 * Probed on a {@link #PITCH}-spaced grid over the envelope (cheap: ~a few hundred column scans),
 * then bilinearly interpolated to block resolution, clamp-to-edge outside the grid.
 */
public final class NaturalSurface {
	/**
	 * Coarse "definitely solid" threshold — NoiseChunk.computePreliminarySurfaceLevel parity. This
	 * value reads the floor 1.09375/(4·factor·slope) ≈ 6–56 blocks BELOW the real surface (vanilla
	 * tolerates that: its consumers are conservative/relative). It is kept ONLY to localize a solid
	 * y without sky false-positives; the reported floor comes from {@link #SURFACE_CROSS}.
	 */
	public static final double THRESHOLD = 0.390625;
	/**
	 * The TEXTURE-FREE SURFACE crossing (2026-07-10 burial fix): in the slide-free band the
	 * predictor is {@code clamp(−0.703125 + 4·depth·factor)}, and the real surface (sloped_cheese
	 * ≈ 0 minus base_3d texture) sits where {@code 4·depth·factor ≈ 0}, i.e. predictor =
	 * −0.703125. Probing at the coarse threshold alone anchored the cut+fill flat platform (and
	 * the apron targets / water classification) 6–56 blocks underground — the buried-structures
	 * field report. NOTE: this value CANNOT be used for a top-down scan — above the 708 top-slide
	 * the tree evaluates to −0.078125 (&gt; −0.703125), a permanent sky false-positive — hence the
	 * walk-UP from the coarse floor below.
	 */
	public static final double SURFACE_CROSS = -0.703125;
	/**
	 * Top-slide geometry — keep in sync with tools/generate-worldgen.mjs (from_y = PEAK_CAP − 53
	 * = 655, to_y = PEAK_CAP = 708). Inside the band the predictor is rescaled by
	 * t = (708−y)/53, so the surface crossing must be slide-corrected: threshold(y) =
	 * −0.078125 + t·(0.078125 + SURFACE_CROSS). With the raw constant the walk-up's break NEVER
	 * fires for surfaces in the band (the tree tends to −0.078125 &gt; −0.703125 as y→708) and the
	 * probe would run away into the sky (validator finding, 2026-07-10). At t=1 (y ≤ 655) this
	 * reduces exactly to SURFACE_CROSS; at t=0 (y ≥ 708) the sky value −0.078125 equals the
	 * threshold, so the strict '&gt;' comparison stops the walk.
	 */
	private static final int TOP_SLIDE_END = 708; // = TC.PEAK_CAP (M1, 2026-07-10)
	private static final int TOP_SLIDE_SPAN = 53; // 708 − 655
	public static final int PITCH = 16;

	/** The slide-corrected texture-free-surface threshold at height y (== SURFACE_CROSS below the band). */
	private static double surfaceCross(int y) {
		double t = Math.min(1.0, Math.max(0.0, (TOP_SLIDE_END - y) / (double) TOP_SLIDE_SPAN));
		return -0.078125 + t * (0.078125 + SURFACE_CROSS);
	}

	private final int originX; // world x of node [0]
	private final int originZ;
	private final int nx; // node columns
	private final int nz;
	private final int[] heights; // row-major nz x nx

	private NaturalSurface(int originX, int originZ, int nx, int nz, int[] heights) {
		this.originX = originX;
		this.originZ = originZ;
		this.nx = nx;
		this.nz = nz;
		this.heights = heights;
	}

	/** Probe the predictor over [minX..maxX] x [minZ..maxZ] (world blocks), inclusive, PITCH grid. */
	public static NaturalSurface probe(DensityFunction predictor, int minX, int minZ, int maxX, int maxZ,
			int bottom, int top) {
		int originX = Math.floorDiv(minX, PITCH) * PITCH;
		int originZ = Math.floorDiv(minZ, PITCH) * PITCH;
		int nx = (Math.floorDiv(maxX, PITCH) * PITCH - originX) / PITCH + 2; // +2: cover maxX + one guard
		int nz = (Math.floorDiv(maxZ, PITCH) * PITCH - originZ) / PITCH + 2;
		int[] heights = new int[nx * nz];
		for (int gz = 0; gz < nz; gz++) {
			for (int gx = 0; gx < nx; gx++) {
				heights[gz * nx + gx] = probeColumn(predictor, originX + gx * PITCH, originZ + gz * PITCH,
						bottom, top);
			}
		}
		return new NaturalSurface(originX, originZ, nx, nz, heights);
	}

	/**
	 * The predictor's TRUE-SURFACE floor at (x,z) — two-stage (2026-07-10 burial fix):
	 * <ol>
	 *   <li>Top-down 8-block scan + binary refine at the coarse {@link #THRESHOLD} — localizes a
	 *       "definitely solid" y with no sky false-positives (the coarse value is unreachable in air).</li>
	 *   <li>Walk UP from there to the last y whose value still exceeds the SLIDE-CORRECTED
	 *       {@link #surfaceCross(int)} threshold — the texture-free surface. The predictor is
	 *       monotone-decreasing in y between the slides (depth is a falling gradient, factor is
	 *       2-D), so the first downward crossing is unique; inside the 655..708 top-slide band the
	 *       correction rescales the threshold with the slide so the crossing stays at the surface
	 *       (an uncorrected constant threshold never breaks there and runs away into the sky).</li>
	 * </ol>
	 * Package-visible so the SitePlanner veto probes the IDENTICAL column the field uses
	 * (byte-identical relief/roughness where the two grids overlap).
	 */
	static int probeColumn(DensityFunction predictor, int x, int z, int bottom, int top) {
		int coarse = bottom;
		for (int y = top - 1; y >= bottom; y -= 8) { // top is exclusive (one past highest block)
			if (predictor.compute(new DensityFunction.SinglePointContext(x, y, z)) > THRESHOLD) {
				coarse = y;
				break;
			}
		}
		int lo = coarse;
		int hi = Math.min(top, coarse + 8);
		while (hi - lo > 1) {
			int mid = (lo + hi) / 2;
			if (predictor.compute(new DensityFunction.SinglePointContext(x, mid, z)) > THRESHOLD) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		// Stage 2: walk up from the coarse floor to the texture-free surface crossing. Bias is
		// bounded by ~1.09375/(4·factor·slope) ≤ ~56 blocks at the world's lowest live factor
		// (96 cap = headroom); the threshold is slide-corrected so the walk stops at the surface
		// (or exactly at the sky value) inside the 655..708 band too.
		int floor = lo;
		for (int y = lo + 1; y < top && y <= lo + 96; y++) {
			if (predictor.compute(new DensityFunction.SinglePointContext(x, y, z)) > surfaceCross(y)) {
				floor = y;
			} else {
				break;
			}
		}
		return floor;
	}

	private int nodeHeight(int gx, int gz) {
		gx = Mth.clamp(gx, 0, nx - 1);
		gz = Mth.clamp(gz, 0, nz - 1);
		return heights[gz * nx + gx];
	}

	/** Bilinearly interpolated natural solid-floor height at world (x,z), clamp-to-edge. */
	public int heightAt(int x, int z) {
		double fx = (x - originX) / (double) PITCH;
		double fz = (z - originZ) / (double) PITCH;
		int x0 = Mth.floor(fx);
		int z0 = Mth.floor(fz);
		double tx = Mth.clamp(fx - x0, 0.0, 1.0);
		double tz = Mth.clamp(fz - z0, 0.0, 1.0);
		double h = Mth.lerp2(tx, tz,
				nodeHeight(x0, z0), nodeHeight(x0 + 1, z0),
				nodeHeight(x0, z0 + 1), nodeHeight(x0 + 1, z0 + 1));
		return (int) Math.round(h);
	}
}
