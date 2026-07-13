package dev.archeanrise.sitegrading;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 1 (site check) + step 2 (deterministic candidate search).
 *
 * Probes the vanilla preliminary-surface predictor (initial_density_without_jaggedness,
 * threshold 0.390625 — NoiseChunk.computePreliminarySurfaceLevel parity; texture-blind by
 * design, the +/-G envelope absorbs base_3d texture) on a 9x9/pitch-32 grid, scores K=9
 * candidate anchors, and returns the chosen anchor + DesignSurface. Consumes NO randomness
 * (pure deterministic scoring — the vanilla rotation/pool RNG stream is untouched).
 */
public final class SitePlanner {
	private static final int SEA_LEVEL = SiteGrading.SEA_LEVEL; // single source of truth (the scale invariant)
	/** Half-width (blocks) of the footprint CORE over which the water-support veto is measured — the
	 * houses cluster near the village centre, so nearby water bodies out in the apron never count. */
	private static final int WATER_CORE = 32;
	/** Candidate offsets (blocks): origin first (distance penalty prefers it). */
	private static final int[][] OFFSETS = {
			{0, 0}, {16, 0}, {-16, 0}, {0, 16}, {0, -16}, {32, 0}, {-32, 0}, {0, 32}, {0, -32}};

	public record Plan(BlockPos anchorPos, DesignSurface surface, boolean fit, boolean gradeable,
			boolean veto, int relief, int roughness, int waterDepth) {}

	private SitePlanner() {}

	public static Plan plan(Structure.GenerationContext context, BlockPos original, int shiftBudget,
			int maxDistanceFromCenter) {
		RandomState randomState = context.randomState();
		DensityFunction predictor = randomState.router().initialDensityWithoutJaggedness();
		int top = context.heightAccessor().getMaxBuildHeight();
		int bottom = context.heightAccessor().getMinBuildHeight();

		int originX = original.getX() - (DesignSurface.GRID_N / 2) * DesignSurface.PITCH;
		int originZ = original.getZ() - (DesignSurface.GRID_N / 2) * DesignSurface.PITCH;
		int[] heights = new int[DesignSurface.GRID_N * DesignSurface.GRID_N];
		for (int gz = 0; gz < DesignSurface.GRID_N; gz++) {
			for (int gx = 0; gx < DesignSurface.GRID_N; gx++) {
				heights[gz * DesignSurface.GRID_N + gx] = probeSurface(predictor,
						originX + gx * DesignSurface.PITCH, originZ + gz * DesignSurface.PITCH,
						bottom, top);
			}
		}

		int bestIndex = -1;
		double bestScore = Double.MAX_VALUE;
		int[] bestMetrics = null;
		for (int i = 0; i < OFFSETS.length; i++) {
			int dx = OFFSETS[i][0];
			int dz = OFFSETS[i][1];
			if (Math.abs(dx) > shiftBudget || Math.abs(dz) > shiftBudget) {
				continue;
			}
			int cx = original.getX() + dx;
			int cz = original.getZ() + dz;
			if (!biomeValidAt(context, cx, cz, heights, originX, originZ)) {
				continue; // never trade a structure vanilla would place for a biome veto
			}
			int[] m = metrics(heights, originX, originZ, cx, cz); // {spread, waterCount, maxRun, count}
			double water = m[1] / (double) m[3];
			double score = m[0] + 40.0 * water + (Math.abs(dx) + Math.abs(dz)) / 32.0
					+ (m[2] >= 24 ? 20.0 : 0.0); // contiguous-water-run demotion (verifier D5)
			boolean fit = m[0] <= 14 && water <= 0.25 && m[2] < 24;
			if (fit && bestIndex >= 0 && isFit(bestMetrics)) {
				// keep first/best fit by score ordering below
			}
			if (score < bestScore) {
				bestScore = score;
				bestIndex = i;
				bestMetrics = m;
			}
		}
		if (bestIndex < 0) { // all candidates biome-vetoed: use original (vanilla behavior)
			bestIndex = 0;
			bestMetrics = metrics(heights, originX, originZ, original.getX(), original.getZ());
		}

		int chosenX = original.getX() + OFFSETS[bestIndex][0];
		int chosenZ = original.getZ() + OFFSETS[bestIndex][1];
		int anchor = landAnchor(heights, originX, originZ, chosenX, chosenZ);
		DesignSurface surface = new DesignSurface(originX, originZ, heights, anchor);
		double water = bestMetrics[1] / (double) bestMetrics[3];
		boolean fit = bestMetrics[0] <= 14 && water <= 0.25 && bestMetrics[2] < 24;
		boolean gradeable = bestMetrics[0] <= 14 + 2 * SiteGrading.GRADE_BUDGET && water <= 0.4;
		// Veto (blueprint §2, re-scoped by the 2026-07-06 vanilla-projection fix): probe the WATER-
		// CLAMPED predictor floor M_wc = max(floor, SEA_LEVEL) at PITCH-16 (the field's sampler) over the
		// FOOTPRINT the village occupies — anchor ± (maxDistanceFromCenter + PAD_RADIUS), the ground the
		// pieces + the density-stage GradePad beard actually touch. relief (max−min of M_wc) is the
		// vertical span the vanilla per-piece-projected village must sit across; beyond vetoMaxRelief the
		// jigsaw paths/villager routes stop being walkable, so the site is rejected here instead of built
		// broken. The roughness term rejects a genuine local cliff bisecting the footprint on the same
		// nodes. FOOTPRINT SCOPE, not the apron-inflated ±(maxDist+rampMax+2+PITCH) the earlier field-
		// bridging veto used: that +rampMax predicted the CUT+FILL apron. The grade IS built now (behind
		// default-off siteGradingCutFill), but the veto deliberately stays FOOTPRINT-scoped: the apron-
		// inflated envelope over-vetoed sites whose OWN footprint was buildable merely because a scenery
		// mountain sat ~100 blocks away (live: a footprint spanning relief 34 measured envelope relief 79),
		// and the flat-snap grade regrades the footprint to the platform. Kept footprint-scoped per
		// DECISIONS.md 2026-07-07 (the flat-snap-grade decision). The
		// WATER-support check stays over the small footprint CORE (±WATER_CORE): a deep column under a
		// house is a crit-6/Phase-4 support concern, and measuring water over the full envelope mass-
		// vetoes ordinary coastal villages. Probe ONLY when the veto is enabled. Pure seed fn; no RNG.
		int envelopeR = maxDistanceFromCenter + SiteGrading.PAD_RADIUS;
		int[] v = ArcheanRise.config != null && ArcheanRise.config.siteGradingVeto
				? vetoEnvelope(predictor, chosenX, chosenZ, envelopeR, WATER_CORE, bottom, top)
				: new int[] {0, 0, 0}; // {relief, roughness, waterDepth}
		boolean veto = SiteGrading.vetoes(v[0], v[1], v[2]);
		return new Plan(new BlockPos(chosenX, original.getY(), chosenZ), surface, fit, gradeable,
				veto, v[0], v[1], v[2]);
	}

	/**
	 * Veto measurement over the village FOOTPRINT, on the PITCH-16 water-clamped predictor model.
	 * Probes the predictor solid floor on a fresh symmetric grid over {@code anchor ± envelopeR}
	 * (envelopeR = maxDistanceFromCenter + {@link SiteGrading#PAD_RADIUS}, rounded up to a whole node —
	 * the pieces + GradePad beard footprint), water-clamps to {@code M_wc = max(floor, SEA_LEVEL)}, and
	 * returns {relief, roughness, waterDepth}:
	 * <ul>
	 *   <li>relief = max−min of M_wc over the footprint — the vertical span the vanilla per-piece-
	 *       projected village sits across; &gt; {@code siteGradingVetoMaxRelief} ⇒ too steep to keep
	 *       walkable.</li>
	 *   <li>roughness = max |Δ| between 8-connected adjacent nodes — a genuine LOCAL CLIFF a global
	 *       relief measure misses; &gt; {@link SiteGrading#VETO_ROUGHNESS_MAX} ⇒ node slope &gt; 1.</li>
	 *   <li>waterDepth = deepest per-column support (SEA_LEVEL − rawFloor) within the FOOTPRINT CORE
	 *       (|d| ≤ footprintR), a crit-6 / Phase-4 support concern, never aggregated over the footprint.</li>
	 * </ul>
	 * Pure function of (seed → predictor). Byte-identical to {@link NaturalSurface} where the grids
	 * overlap: same predictor, same PITCH, same global lattice phase, same {@code probeColumn}.
	 */
	private static int[] vetoEnvelope(DensityFunction predictor, int cx, int cz, int envelopeR,
			int footprintR, int bottom, int top) {
		final int pitch = NaturalSurface.PITCH; // 16
		// Phase-align the probe grid to the GLOBAL pitch lattice (node coords are multiples of pitch,
		// via floorDiv) AND probe with the field's own NaturalSurface.probeColumn, so the veto samples
		// the BYTE-IDENTICAL nodes the field's NaturalSurface does where the two grids overlap. This
		// makes the R1 roughness gate rigorous (veto-node Δ == field-node Δ, no scan-start mismatch on
		// overhang columns). Covers ⊇ ±envelopeR.
		int gx0 = Math.floorDiv(cx - envelopeR, pitch);
		int gx1 = Math.floorDiv(cx + envelopeR + pitch - 1, pitch);
		int gz0 = Math.floorDiv(cz - envelopeR, pitch);
		int gz1 = Math.floorDiv(cz + envelopeR + pitch - 1, pitch);
		int nx = gx1 - gx0 + 1;
		int nz = gz1 - gz0 + 1;
		int[] mwc = new int[nx * nz];
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		int deepest = 0;
		for (int gz = 0; gz < nz; gz++) {
			int z = (gz0 + gz) * pitch;
			for (int gx = 0; gx < nx; gx++) {
				int x = (gx0 + gx) * pitch;
				int floor = NaturalSurface.probeColumn(predictor, x, z, bottom, top);
				int m = Math.max(floor, SiteGrading.SEA_LEVEL);
				mwc[gz * nx + gx] = m;
				min = Math.min(min, m);
				max = Math.max(max, m);
				if (Math.abs(x - cx) <= footprintR && Math.abs(z - cz) <= footprintR
						&& floor < SiteGrading.SEA_LEVEL) {
					deepest = Math.max(deepest, SiteGrading.SEA_LEVEL - floor);
				}
			}
		}
		// Local roughness = max |Δ| between 8-connected adjacent nodes (the R1 gate). Each node
		// compares to its E, S, SE, SW neighbours so every 8-connected node pair is measured once.
		int roughness = 0;
		for (int gz = 0; gz < nz; gz++) {
			for (int gx = 0; gx < nx; gx++) {
				int v = mwc[gz * nx + gx];
				if (gx + 1 < nx) {
					roughness = Math.max(roughness, Math.abs(v - mwc[gz * nx + gx + 1]));
				}
				if (gz + 1 < nz) {
					roughness = Math.max(roughness, Math.abs(v - mwc[(gz + 1) * nx + gx]));
					if (gx + 1 < nx) {
						roughness = Math.max(roughness, Math.abs(v - mwc[(gz + 1) * nx + gx + 1]));
					}
					if (gx - 1 >= 0) {
						roughness = Math.max(roughness, Math.abs(v - mwc[(gz + 1) * nx + gx - 1]));
					}
				}
			}
		}
		return new int[] {max - min, roughness, deepest};
	}

	private static boolean isFit(int[] m) {
		return m != null && m[0] <= 14 && m[1] / (double) m[3] <= 0.25 && m[2] < 24;
	}

	/**
	 * Delegates to {@link NaturalSurface#probeColumn} (2026-07-10 burial fix): the 9×9 design grid,
	 * the veto envelope, and the cut+fill field now share ONE probe, all reporting the TEXTURE-FREE
	 * SURFACE (walk-up to the −0.703125 crossing) instead of the coarse preliminary floor that read
	 * 6–56 blocks underground and mis-classified the realigned coastal band (real land at Y 63–66
	 * probed at 56–60 → "water" → the anchor=64 fallback).
	 */
	private static int probeSurface(DensityFunction predictor, int x, int z, int bottom, int top) {
		return NaturalSurface.probeColumn(predictor, x, z, bottom, top);
	}

	/** Spread/water metrics over the 5x5 grid-node window around a candidate center. */
	private static int[] metrics(int[] heights, int originX, int originZ, int cx, int cz) {
		int nodeX = Math.round((cx - originX) / (float) DesignSurface.PITCH);
		int nodeZ = Math.round((cz - originZ) / (float) DesignSurface.PITCH);
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		int water = 0;
		int count = 0;
		int maxRun = 0;
		for (int dz = -2; dz <= 2; dz++) {
			int run = 0;
			for (int dx = -2; dx <= 2; dx++) {
				int gx = Math.max(0, Math.min(DesignSurface.GRID_N - 1, nodeX + dx));
				int gz = Math.max(0, Math.min(DesignSurface.GRID_N - 1, nodeZ + dz));
				int h = heights[gz * DesignSurface.GRID_N + gx];
				min = Math.min(min, h);
				max = Math.max(max, h);
				count++;
				if (h < SEA_LEVEL) {
					water++;
					run += DesignSurface.PITCH;
					maxRun = Math.max(maxRun, run);
				} else {
					run = 0;
				}
			}
		}
		return new int[] {max - min, water, maxRun, count};
	}

	/** Anchor = median of LAND nodes in the inner 5x5, clamped >= 64 (verifier D6). */
	private static int landAnchor(int[] heights, int originX, int originZ, int cx, int cz) {
		int nodeX = Math.round((cx - originX) / (float) DesignSurface.PITCH);
		int nodeZ = Math.round((cz - originZ) / (float) DesignSurface.PITCH);
		List<Integer> land = new ArrayList<>();
		for (int dz = -2; dz <= 2; dz++) {
			for (int dx = -2; dx <= 2; dx++) {
				int gx = Math.max(0, Math.min(DesignSurface.GRID_N - 1, nodeX + dx));
				int gz = Math.max(0, Math.min(DesignSurface.GRID_N - 1, nodeZ + dz));
				int h = heights[gz * DesignSurface.GRID_N + gx];
				if (h >= SEA_LEVEL) {
					land.add(h);
				}
			}
		}
		if (land.isEmpty()) {
			return 64;
		}
		land.sort(Integer::compareTo);
		return Math.max(64, land.get(land.size() / 2));
	}

	private static boolean biomeValidAt(Structure.GenerationContext context, int x, int z,
			int[] heights, int originX, int originZ) {
		int gx = Math.max(0, Math.min(DesignSurface.GRID_N - 1,
				Math.round((x - originX) / (float) DesignSurface.PITCH)));
		int gz = Math.max(0, Math.min(DesignSurface.GRID_N - 1,
				Math.round((z - originZ) / (float) DesignSurface.PITCH)));
		int y = heights[gz * DesignSurface.GRID_N + gx];
		return context.validBiome().test(context.biomeSource().getNoiseBiome(
				QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z),
				context.randomState().sampler()));
	}
}
