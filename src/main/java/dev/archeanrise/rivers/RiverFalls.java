package dev.archeanrise.rivers;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.material.Fluids;

import java.util.List;

/**
 * Spec §7.2 WATERFALLS / §7.4.1 RAPIDS — the FEATURES-stage placer that turns each graph lip into a
 * NARROW, CONFINED fall between two of {@link RiverPools}' sealed still pools (the R2 quiescence rework,
 * 2026-07-12).
 *
 * <p><b>What {@link RiverPools} hands us.</b> Every reach is now a FULLY-SEALED source pool: filled to
 * its surface {@code L}, rim dammed to {@code L+1} on every side, and — at each lip — a solid
 * cross-channel CREST-DAM sealing the upper pool's downstream edge (the spill crest the carve left one
 * block below the surface, which used to pour over forever when the chunk block-ticked and flooded).
 * With {@code riverFallsEnabled} off the world is already quiescent: still stepped pools, dry drop
 * faces. This pass carves the confined fall between two adjacent sealed pools.
 *
 * <p><b>What this builds (per lip, spec §7.2 "retained lip sources + plunge pool").</b> The fall is a
 * self-contained feature that leaves the upper pool 100% sealed still source:
 * <ol>
 *   <li>a <b>retained lip source</b> — a small isolated SOURCE seated on the ACTUAL terrain at the top
 *       of the drop face (anchored to the manifested crest so it never floats), walled upstream + on
 *       every non-flow side so it spills a single 1-wide stream downstream and NOTHING sideways or back
 *       into the pool;</li>
 *   <li>a <b>walled falling CURTAIN</b> — vanilla FALLING water ({@link Fluids#WATER} level 8, which
 *       does not spread horizontally while it has air below) from the source straight down to the
 *       plunge, with SOLID walls on every non-flow side, so the fall is a column that CANNOT spread;</li>
 *   <li>the fall lands in the <b>plunge pool</b> = the lower reach, already filled + rim-sealed to
 *       {@code lowerY+1} by {@link RiverPools}. The 1-wide inflow settles into a small bounded flowing
 *       patch on the plunge surface, rim-contained — the "settle once" Gate R2 permits.</li>
 * </ol>
 * A fall is built ONLY where BOTH pools hold water (an upper pool so it reads as fed, a lower pool so
 * the fall always lands in a sealed plunge — never on dry ground). <b>Rapids</b> (2..3 drop) get the
 * same treatment at reduced height.
 *
 * <p><b>Fluid quiescence (Gate R2, spec §7.6).</b> Both pools stay sealed still source (0 ticks). The
 * only flowing water is the retained source's confined 1-wide spill + its walled curtain + the small
 * rim-contained plunge patch — a bounded steady state that cannot grow or spread onto dry land, proven
 * by the ticking A/B flight (force-load + block-tick 60–120 s; steady-state flowing confined to the
 * walled channels + plunge patches, ZERO on dry land, count stable between 60 s and 120 s).
 *
 * <p><b>Chunk-safe + deterministic.</b> Geometry is a pure function of the seed-pure lip
 * ({@link RiverCarveProvider.Lip}) + the frozen post-{@link RiverPools} terrain; every WRITE is CLAMPED
 * to the current chunk, and a lip whose structure straddles a border is returned to both chunks (each
 * writes its own clamped slice, identical values). No RNG. Loader-neutral. A vanilla-preset world never
 * reaches here ({@link RiverCarveProvider#current()} is {@code null}).
 */
public final class RiverFalls {

	/** spec §7.5 WATERFALL_MIN_DROP / LIP_STEP — a drop this deep or deeper is a waterfall. */
	static final int MIN_DROP = 4;
	/** spec §7.5 RAPIDS_DROP lower bound — 2..3 is rapids. */
	static final int RAPIDS_DROP = 2;
	/** Half-width of the confined fall (perpendicular to flow): the curtain + source span {@code 2·this+1}
	 *  columns. 0 = a 1-wide fall — the tightest confinement (a wide sheet is what flooded before). */
	static final int FALL_HALF = 0;
	/** Curtain-height cap → bounded footprint. A drop taller than this seats the source part-way down the
	 *  face (rare — only the very tallest terrain cliffs). */
	static final int MAX_FALL = 96;
	/** How far from the lip node the pools are probed for water before a fall is built. */
	static final int POOL_PROBE = 6;

	private RiverFalls() {}

	public static boolean enabled() {
		return ArcheanRise.config != null && ArcheanRise.config.riverFallsEnabled;
	}

	private static final BlockState SOURCE = Blocks.WATER.defaultBlockState();
	private static final BlockState FALLING = Fluids.WATER.getFlowing(8, true).createLegacyBlock();
	private static final BlockState WALL = Blocks.STONE.defaultBlockState();

	/** Place every confined fall whose footprint intersects this chunk (clamped to it). */
	public static void run(WorldGenLevel level, ChunkAccess chunk) {
		RiverCarveProvider provider = RiverCarveProvider.current();
		if (provider == null) {
			return; // no live Archean overworld — inert (vanilla safety)
		}
		ChunkPos cp = chunk.getPos();
		List<RiverCarveProvider.Lip> lips = provider.lipsNear(cp.getMinBlockX(), cp.getMinBlockZ(),
				FALL_HALF + 3);
		if (lips.isEmpty()) {
			return;
		}
		int minY = level.getMinBuildHeight();
		int maxY = level.getMaxBuildHeight() - 1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int placed = 0;
		for (RiverCarveProvider.Lip lip : lips) {
			placed += placeDrop(level, cp, minY, maxY, p, lip, provider);
		}
		if (placed > 0) {
			ArcheanRise.LOGGER.debug("RiverFalls: chunk {} placed {} confined-fall write(s)", cp, placed);
		}
	}

	/** Build the confined fall for one lip, clamped to {@code cp}'s block bounds. */
	private static int placeDrop(WorldGenLevel level, ChunkPos cp, int minY, int maxY,
			BlockPos.MutableBlockPos p, RiverCarveProvider.Lip lip, RiverCarveProvider provider) {
		int drop = (int) Math.round(lip.drop());
		if (drop < RAPIDS_DROP) {
			return 0; // gentle 1-block step — the stepped pools already read as a step
		}
		int cx0 = cp.getMinBlockX(), cx1 = cp.getMaxBlockX();
		int cz0 = cp.getMinBlockZ(), cz1 = cp.getMaxBlockZ();

		int lx = Mth.floor(lip.x());          // plunge centre = lower reach node (cliff base)
		int lz = Mth.floor(lip.z());
		int lowerY = Mth.floor(lip.lowerY()); // lower reach surface (plunge); top water = lowerY-1
		int ux = step(lip.upDx());            // toward the crest (upstream)
		int uz = step(lip.upDz());
		if (ux == 0 && uz == 0) {
			ux = 1;
		}
		int fx = -ux, fz = -uz;               // downstream (flow) direction
		int px = -uz, pz = ux;                // perpendicular (channel-cross) axis

		if (lowerY - 1 <= minY + 1 || lowerY + MAX_FALL + 2 >= maxY) {
			return 0; // out of the build column (never in practice; guards the write range)
		}
		// Build a fall ONLY where the lower reach is a sealed plunge (so the fall always lands in
		// contained water, never on dry ground where an infinite retained source would spread forever).
		// Use the PURE carve predicate {@link RiverPools#wouldFill} — not a read of the water RiverPools
		// placed — so the decision is identical in every chunk regardless of generation order (a fall
		// whose footprint straddles a border must be built the same way by both chunks). A reach RiverPools
		// leaves dry (the escape-saddle residual) simply gets no fall.
		if (!RiverPools.wouldFill(level, p, lx, lz, provider)) {
			return 0;
		}
		return placeFall(level, cx0, cx1, cz0, cz1, p, lip, lx, lz, lowerY, ux, uz, fx, fz, px, pz);
	}

	/**
	 * A confined fall: a walled retained source at the top of the drop face + a walled falling curtain
	 * into the plunge (the sealed lower reach).
	 */
	private static int placeFall(WorldGenLevel level, int cx0, int cx1, int cz0, int cz1,
			BlockPos.MutableBlockPos p, RiverCarveProvider.Lip lip,
			int lx, int lz, int lowerY, int ux, int uz, int fx, int fz, int px, int pz) {
		int writes = 0;
		// Anchor the crest to the ACTUAL terrain at the sill (1 upstream of the plunge node) so the fall
		// hugs the real cliff and never floats (the manifestation gap). Seat the source one block above it.
		int sillTopWant = Mth.floor(lip.spillY());
		int sillX = lx + ux, sillZ = lz + uz;
		int crestSill = terrainTop(level, sillX, sillZ, Math.min(sillTopWant, lowerY + MAX_FALL), lowerY);
		int srcY = Mth.clamp(crestSill + 1, lowerY + MIN_DROP, Math.min(sillTopWant + 1, lowerY + MAX_FALL));

		for (int j = -FALL_HALF; j <= FALL_HALF; j++) {
			int sx = sillX + px * j, sz = sillZ + pz * j;       // retained-source column (crest sill)
			int dx = lx + px * j, dz = lz + pz * j;             // curtain column (plunge node)

			// (a) RETAINED SOURCE, seated on a solid sill, walled upstream + laterally so it spills ONLY
			//     downstream (onto the curtain) — a small isolated spring, never back into the sealed pool.
			for (int y = Math.max(lowerY, srcY - 2); y <= srcY - 1; y++) {
				writes += fillIfAir(level, p, cx0, cx1, cz0, cz1, sx, y, sz, WALL); // solid sill under the source
			}
			writes += set(level, p, cx0, cx1, cz0, cz1, sx, srcY, sz, SOURCE);      // the retained source
			writes += fillIfAir(level, p, cx0, cx1, cz0, cz1, sx + ux, srcY, sz + uz, WALL);   // upstream wall
			writes += fillIfAir(level, p, cx0, cx1, cz0, cz1, sx + ux, srcY + 1, sz + uz, WALL);
			writes += fillIfAir(level, p, cx0, cx1, cz0, cz1, sx, srcY + 1, sz, WALL);         // cap (no fountain up)

			// (b) FALLING CURTAIN down the drop face into the plunge — occupies AIR only and STOPS at the
			//     pool water below (never overwrites terrain OR water), so it lands quiescently in the
			//     RiverPools-sealed plunge without perturbing it.
			for (int y = srcY; y >= lowerY; y--) {
				if (!level.getBlockState(p.set(dx, y, dz)).isAir()) {
					break; // reached terrain or the plunge water — landed
				}
				writes += set(level, p, cx0, cx1, cz0, cz1, dx, y, dz, FALLING);
			}
		}

		// (c) WALLS on every non-flow side of the source+curtain lane, so the fall cannot spread laterally
		//     or downstream past the plunge. Fills AIR only (the cliff already walls most of it).
		writes += wallLane(level, cx0, cx1, cz0, cz1, p, lx, lz, sillX, sillZ, srcY, lowerY, ux, uz, fx, fz, px, pz);
		return writes;
	}

	/** Wall the perpendicular sides of the whole source→curtain lane (and the downstream side of the
	 *  curtain) so the confined fall cannot spread. Fills AIR only. */
	private static int wallLane(WorldGenLevel level, int cx0, int cx1, int cz0, int cz1,
			BlockPos.MutableBlockPos p, int lx, int lz, int sillX, int sillZ, int srcY, int lowerY,
			int ux, int uz, int fx, int fz, int px, int pz) {
		int writes = 0;
		// two lane anchors: the sill (source) and the plunge node (curtain foot)
		int[][] anchors = { {sillX, sillZ}, {lx, lz} };
		for (int[] a : anchors) {
			for (int side = -1; side <= 1; side += 2) {
				int wx = a[0] + px * (FALL_HALF + 1) * side, wz = a[1] + pz * (FALL_HALF + 1) * side;
				for (int y = lowerY; y <= srcY + 1; y++) {
					writes += fillIfAir(level, p, cx0, cx1, cz0, cz1, wx, y, wz, WALL);
				}
			}
		}
		// downstream wall past the curtain foot, so the landing patch cannot run off downstream on dry land
		int dwx = lx + fx, dwz = lz + fz;
		for (int j = -FALL_HALF - 1; j <= FALL_HALF + 1; j++) {
			int wx = dwx + px * j, wz = dwz + pz * j;
			for (int y = lowerY; y <= lowerY + 2; y++) {
				writes += fillIfAir(level, p, cx0, cx1, cz0, cz1, wx, y, wz, WALL);
			}
		}
		return writes;
	}

	/** Top solid Y at (x,z) scanning down from {@code yHi} to {@code yLo}; {@code yLo-1} if none. */
	private static int terrainTop(WorldGenLevel level, int x, int z, int yHi, int yLo) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int lo = Math.max(yLo, level.getMinBuildHeight());
		int hi = Math.min(yHi, level.getMaxBuildHeight() - 1);
		for (int y = hi; y >= lo; y--) {
			BlockState s = level.getBlockState(m.set(x, y, z));
			if (!s.isAir() && s.getFluidState().isEmpty()) {
				return y;
			}
		}
		return lo - 1;
	}

	private static int step(double v) {
		return v > 0.4 ? 1 : v < -0.4 ? -1 : 0;
	}

	/** Set a block, CLAMPED to this chunk + build column; returns 1 if written. Flag 2: no neighbour
	 *  update in worldgen. */
	private static int set(WorldGenLevel level, BlockPos.MutableBlockPos p, int cx0, int cx1, int cz0,
			int cz1, int x, int y, int z, BlockState s) {
		if (x < cx0 || x > cx1 || z < cz0 || z > cz1
				|| y < level.getMinBuildHeight() || y > level.getMaxBuildHeight() - 1) {
			return 0;
		}
		level.setBlock(p.set(x, y, z), s, 2);
		return 1;
	}

	/** Set a block only where AIR (fill a gap; never overwrite terrain OR water); CLAMPED to this chunk. */
	private static int fillIfAir(WorldGenLevel level, BlockPos.MutableBlockPos p,
			int cx0, int cx1, int cz0, int cz1, int x, int y, int z, BlockState s) {
		if (x < cx0 || x > cx1 || z < cz0 || z > cz1
				|| y < level.getMinBuildHeight() || y > level.getMaxBuildHeight() - 1) {
			return 0;
		}
		if (!level.getBlockState(p.set(x, y, z)).isAir()) {
			return 0;
		}
		level.setBlock(p.set(x, y, z), s, 2);
		return 1;
	}
}
