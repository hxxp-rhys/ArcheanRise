package dev.archeanrise.rivers;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.List;

/**
 * Spec §7.3 river water — the FEATURES-stage pass that fills the stepped river pools with STILL,
 * FULLY-CONTAINED source water so they stay quiescent when the chunk actually block-ticks.
 *
 * <p><b>Why this owns ALL elevated river water now (the R2 quiescence rework, 2026-07-12).</b> The
 * R2c aquifer "elevated stepped pools" trio (prelim raise + corridor-wet floodedness + spread decode)
 * was RETIRED from the noise router: it filled elevated corridors + their apron with INFINITE-SOURCE
 * aquifer water whose surface sat at/above the carved spill crests, so every pool poured over its lip
 * forever the moment the chunks block-ticked (the confirmed Gate-R2 flood — dual-validated). Elevated
 * corridors now generate DRY (only the §1 belowSea sea-pin survives, flooding sub-sea columns to 63).
 * This pass places EVERY elevated pool's water, at the features stage, where containment can be
 * verified against the REAL manifested terrain — the one place quiescence is guaranteed.
 *
 * <p><b>The containment guarantee (spec §7.3 "every rim column ≥ reachSurfaceY + 1").</b> A source
 * pool is STILL (zero fluid ticks) iff its rim is solid to ≥ the water surface on EVERY side. Three
 * chunk-safe, order-independent passes establish that:
 * <ol>
 *   <li><b>FILL</b> (per column): every {@code BED}/{@code LAKE} interior column is filled with SOURCE
 *       water up to the reach surface {@code L}, but ONLY where the pool is {@link #sealable} — i.e.
 *       every rim direction is either the same water body continuing OR a wall that the manifested
 *       terrain reaches, or that a dam CAN reach ({@code perch ≤ }{@link #MAX_DAM_BUILD}). A pool
 *       perched deeper than a dam can hold (the R1 escape-saddle, &lt;5%) is LEFT DRY — honest partial
 *       coverage beats a leak. The check reads the FROZEN post-carver terrain (never placed water/dam),
 *       so it is identical regardless of which chunk/pass ran first.</li>
 *   <li><b>CREST-DAM</b> (per lip): the carve leaves each pool's downstream edge as a spill crest one
 *       block BELOW the surface (the flood's mouth). Here a solid cross-channel dam is raised at the
 *       crest to {@code upperY + 1}, sealing the upper pool's downstream. With {@code riverFallsEnabled}
 *       off this is a complete seal (still stepped pools, dry drop faces); with it on {@link RiverFalls}
 *       punches a NARROW confined spillway + fall through it.</li>
 *   <li><b>RIM-DAM</b> (per column): every near-bank {@code HALO} column whose manifested terrain is
 *       below {@code surfaceY + 1} is raised to it with WALL blocks (capped at {@link #MAX_DAM_BUILD}) —
 *       the smoothed levee spec §7.3 calls for, closing the low-manifested side saddles the sink-graded
 *       walls did not reach. Self-raise only (the column raises ITSELF), so there are no cross-chunk
 *       writes and no border gaps.</li>
 * </ol>
 *
 * <p><b>Quiescence (Gate R2, spec §7.6).</b> Every placed water block is a level-0 SOURCE inside a
 * basin walled to {@code ≥ surface + 1} on all sides (verified against the real terrain), with a solid
 * floor — a static equilibrium with zero fluid ticks. The definitive proof is the ticking A/B flight
 * (force-load + block-tick; still pools stay 100% source, 0 flowing).
 *
 * <p><b>Determinism + chunk-safety.</b> Every WRITE is CLAMPED to this chunk; the fill/rim-dam are pure
 * per-column functions of the seed-pure carve + this column's own frozen terrain, and the crest-dam is
 * a per-lip structure returned to every chunk it touches (each writes its own clamped slice) — so the
 * result is order-independent across chunk borders and concurrency-safe. No RNG. Loader-neutral. A
 * vanilla-preset world never reaches here ({@link RiverCarveProvider#current()} is null).
 */
public final class RiverPools {

	/** Deepest a pool basin may be and still be filled (floor scan cap below the surface); a deeper hole
	 *  is a chasm / unresolved cliff, not a pool interior, and is left dry. */
	static final int MAX_POOL_DEPTH = 32;
	/** Tallest dam this pass will build to seal a rim. A rim perched deeper than this below the surface
	 *  cannot be held (the R1 escape-saddle residual) → {@link #sealable} leaves that pool dry. Chosen
	 *  well above the ~12–20-block "crest manifests low" gap the carve leaves at altitude, so a steep
	 *  reach whose banks manifest low still gets a retaining dam rather than a leak. */
	static final int MAX_DAM_BUILD = 40;
	/** Rim-dam band thickness beyond the channel half-width — a solid wall this many columns thick. */
	static final int RIM_BAND = 3;
	/** Max blocks the rim scan steps outward from a bed column before giving up (past the bed + band). */
	static final int RIM_SCAN = 20;
	/** The containment ring sits this far beyond the channel edge. */
	static final int RING_GUARD = 1;
	/** Vertical span scanned above the surface for a containing wall top. */
	static final int WALL_SCAN_UP = 48;
	/** ...and below, so the scan seats on the real solid top even when a rim manifested a touch low. */
	static final int WALL_SCAN_DN = MAX_DAM_BUILD + 4;
	/** Crest-dam half-span beyond the channel edge (so the dam laps into both banks and cannot be
	 *  flanked). */
	static final int CREST_DAM_FLANK = RIM_BAND + 2;
	/** Realized-lip fetch margin — the crest-dam of a lip up to this far outside the chunk can intersect it. */
	static final int LIP_MARGIN = 16 + CREST_DAM_FLANK + 4;

	/** 8-neighbourhood — a leak can open in ANY direction. */
	private static final int[][] DIRS8 = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private static final BlockState SOURCE = Blocks.WATER.defaultBlockState();
	private static final BlockState WALL = Blocks.STONE.defaultBlockState();

	private RiverPools() {}

	public static boolean enabled() {
		return ArcheanRise.config != null && ArcheanRise.config.riverPoolFillEnabled;
	}

	/** Fill + seal every river-pool column/lip whose reach surface intersects this chunk. */
	public static void run(WorldGenLevel level, ChunkAccess chunk) {
		RiverCarveProvider provider = RiverCarveProvider.current();
		if (provider == null) {
			return; // no live Archean overworld — inert (vanilla safety)
		}
		ChunkPos cp = chunk.getPos();
		int cx0 = cp.getMinBlockX(), cx1 = cp.getMaxBlockX();
		int cz0 = cp.getMinBlockZ(), cz1 = cp.getMaxBlockZ();
		int minY = level.getMinBuildHeight();
		int maxY = level.getMaxBuildHeight() - 1;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

		// ---- PASS 1: FILL pool interiors (BED + LAKE) up to L where sealable ----
		// Reads only FROZEN terrain (sealable + floor scan) — order-independent across chunks/passes.
		int placed = 0, filledCols = 0, dryCols = 0;
		for (int x = cx0; x <= cx1; x++) {
			for (int z = cz0; z <= cz1; z++) {
				RiverCarve.Result r = provider.carveAt(x, z, 0.0);
				if (r.zone != RiverCarve.Zone.BED && r.zone != RiverCarve.Zone.LAKE) {
					continue;
				}
				double L = r.reachW;
				int surfaceY = Mth.floor(L);
				int waterTopY = surfaceY - 1;
				if (waterTopY <= minY + 2 || surfaceY >= maxY - 1) {
					continue;
				}
				int n = fillColumn(level, p, cx0, cx1, cz0, cz1, x, z, waterTopY, r.width, L, provider);
				if (n > 0) {
					placed += n;
					filledCols++;
				} else if (n == LEFT_DRY) {
					dryCols++;
				}
			}
		}

		// ---- PASS 2: CREST-DAM each lip (seal the upper pool's downstream spill crest) ----
		List<RiverCarveProvider.Lip> lips = provider.lipsNear(cx0, cz0, LIP_MARGIN);
		int damBlocks = 0;
		for (RiverCarveProvider.Lip lip : lips) {
			damBlocks += damCrest(level, cx0, cx1, cz0, cz1, minY, maxY, p, lip, provider);
		}

		// ---- PASS 3: RIM-DAM the near-bank HALO band (self-raise low-manifested side saddles) ----
		for (int x = cx0; x <= cx1; x++) {
			for (int z = cz0; z <= cz1; z++) {
				RiverCarve.Result r = provider.carveAt(x, z, 0.0);
				if (r.zone != RiverCarve.Zone.HALO) {
					continue;
				}
				double half = r.width / 2.0;
				if (r.d > half + RIM_BAND) {
					continue; // beyond the rim band — natural valley wall
				}
				int surfaceY = Mth.floor(r.reachW);
				if (surfaceY <= minY + 2 || surfaceY + 1 >= maxY) {
					continue;
				}
				damBlocks += damColumnTo(level, p, cx0, cx1, cz0, cz1, x, z, surfaceY + 1);
			}
		}

		if (placed > 0 || dryCols > 0 || damBlocks > 0) {
			ArcheanRise.LOGGER.debug("RiverPools: chunk {} filled {} col(s)/{} water; {} col(s) left dry; "
					+ "{} dam block(s)", cp, filledCols, placed, dryCols, damBlocks);
		}
	}

	/** Sentinel: a real pool interior withheld by the sealable gate (a leak risk). */
	private static final int LEFT_DRY = -1;

	/**
	 * Pure predicate: would this pass FILL a sealed pool at {@code (x,z)}? True iff the column is a
	 * {@code BED}/{@code LAKE} pool with a floor within {@link #MAX_POOL_DEPTH} that is {@link #sealable}.
	 * Reads ONLY the frozen post-carver terrain (the floor scan skips fluids, so it is unaffected by
	 * water this pass already placed) — so it returns the same answer for any caller in any chunk order.
	 * {@link RiverFalls} uses it to gate a fall on the lower reach being a sealed plunge (never dry ground)
	 * WITHOUT the order-dependence of reading placed water.
	 */
	static boolean wouldFill(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int z,
			RiverCarveProvider provider) {
		RiverCarve.Result r = provider.carveAt(x, z, 0.0);
		if (r.zone != RiverCarve.Zone.BED && r.zone != RiverCarve.Zone.LAKE) {
			return false;
		}
		double L = r.reachW;
		int surfaceY = Mth.floor(L);
		int waterTopY = surfaceY - 1;
		if (waterTopY <= level.getMinBuildHeight() + 2 || surfaceY >= level.getMaxBuildHeight() - 1) {
			return false;
		}
		int floorY = terrainTop(level, p, x, z, waterTopY, waterTopY - MAX_POOL_DEPTH);
		if (floorY >= waterTopY || floorY < waterTopY - MAX_POOL_DEPTH) {
			return false; // bed at/above the surface, or no floor within a pool's depth (a drop face)
		}
		return sealable(level, p, x, z, surfaceY, r.width, L, provider);
	}

	/**
	 * Fill one interior column up to {@code waterTopY} if it is a sealable, still-dry pool interior with a
	 * floor. Returns the SOURCE blocks written ({@code 0} if nothing to do / already wet / a drop face),
	 * or {@link #LEFT_DRY} when a real interior was withheld by {@link #sealable}.
	 */
	private static int fillColumn(WorldGenLevel level, BlockPos.MutableBlockPos p,
			int cx0, int cx1, int cz0, int cz1, int x, int z, int waterTopY,
			double width, double L, RiverCarveProvider provider) {
		int surfaceY = waterTopY + 1;
		if (isWater(level, p, x, waterTopY, z)) {
			return 0; // already filled (e.g. belowSea sea water at an estuary) — supplement, don't double-place
		}
		int lowestScan = surfaceY - MAX_POOL_DEPTH;
		int floorY = Integer.MIN_VALUE;
		for (int y = waterTopY; y >= lowestScan; y--) {
			if (!isAir(level, p, x, y, z)) {
				floorY = y; // solid carved bed OR existing water — the pool floor
				break;
			}
		}
		if (floorY == Integer.MIN_VALUE || floorY >= waterTopY) {
			return 0; // no floor within a pool's depth (drop face / cavity), or bed at/above the surface
		}
		if (!sealable(level, p, x, z, surfaceY, width, L, provider)) {
			return LEFT_DRY;
		}
		int written = 0;
		for (int y = floorY + 1; y <= waterTopY; y++) {
			written += setIfAir(level, p, cx0, cx1, cz0, cz1, x, y, z, SOURCE);
		}
		return written;
	}

	/**
	 * True when the pool at {@code (x,z)} can be SEALED to its surface on every side. Steps OUTWARD in
	 * each of the 8 compass directions until it leaves the pool's own water body (a column that is not
	 * this-or-a-higher reach's {@code BED}/{@code LAKE}) — the FIRST such column is the rim in that
	 * direction — and checks that a wall reaches the surface there: the manifested terrain already does
	 * ({@code top ≥ surfaceY+1}) or a dam CAN ({@code top ≥ surfaceY+1 − }{@link #MAX_DAM_BUILD}, i.e. the
	 * crest-dam / rim-dam this pass builds will seal it). A rim perched deeper than a dam can hold, with
	 * no wall, is an escape saddle — the pool would leak — so the fill is withheld. Scanning to the ACTUAL
	 * rim (not a fixed radius) is what makes the check correct for wide reaches: a fixed ring overshoots
	 * the rim for edge columns and passes pools whose true bank is a cliff. Reads FROZEN terrain only
	 * (the {@link #terrainTop} scan skips fluids, and "dammable" subsumes "already walled"), so it is
	 * order-independent across chunks/passes.
	 */
	private static boolean sealable(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int z,
			int surfaceY, double width, double L, RiverCarveProvider provider) {
		int need = surfaceY + 1;
		int dammableFloor = need - MAX_DAM_BUILD;
		for (int[] d : DIRS8) {
			for (int s = 1; s <= RIM_SCAN; s++) {
				int bx = x + d[0] * s, bz = z + d[1] * s;
				RiverCarve.Result rr = provider.carveAt(bx, bz, 0.0);
				if ((rr.zone == RiverCarve.Zone.BED || rr.zone == RiverCarve.Zone.LAKE)
						&& rr.reachW >= L - 1.0) {
					continue; // still inside the same-or-higher water body — keep stepping to the rim
				}
				// first column outside the pool in this direction = the rim. Must be a wall or dammable.
				int top = terrainTop(level, p, bx, bz, need + WALL_SCAN_UP, need - WALL_SCAN_DN);
				if (top < dammableFloor) {
					return false; // perched deeper than a dam can hold, no wall → would leak
				}
				break; // this direction is sealed; on to the next
			}
			// running the whole scan still in water (a reach wider than RIM_SCAN in this direction —
			// only ALONG the channel for river widths ≤ 24) means the rim is farther out and is checked by
			// the edge columns; not a leak here.
		}
		return true;
	}

	/**
	 * Raise a solid cross-channel dam at this lip's spill crest to {@code upperY + 1}, sealing the upper
	 * pool's downstream edge (the carve leaves the crest one block below the surface — the flood's mouth).
	 * The dam spans the channel width + {@link #CREST_DAM_FLANK} into each bank, and fills UP from the
	 * ACTUAL terrain top so it closes the manifestation gap (a crest that manifested ~20 low gets a
	 * ~20-tall dam). {@link RiverFalls} (when enabled) punches a narrow confined spillway + fall through
	 * it; with falls off it is a complete seal. Every write is clamped to this chunk.
	 */
	private static int damCrest(WorldGenLevel level, int cx0, int cx1, int cz0, int cz1,
			int minY, int maxY, BlockPos.MutableBlockPos p, RiverCarveProvider.Lip lip,
			RiverCarveProvider provider) {
		if (lip.drop() < RiverFalls.RAPIDS_DROP) {
			return 0; // a gentle 1-block step needs no dam (the stepped pools already read as a step)
		}
		int ux = step(lip.upDx()), uz = step(lip.upDz());
		if (ux == 0 && uz == 0) {
			ux = 1;
		}
		int px = -uz, pz = ux; // perpendicular (channel-cross) axis
		int lipX = Mth.floor(lip.x()), lipZ = Mth.floor(lip.z());
		int upperY = Mth.floor(lip.upperY());
		int damTop = upperY + 1;
		if (damTop <= minY + 2 || damTop >= maxY) {
			return 0;
		}
		int along = (int) Math.round(RiverCarve.LIP_ALONG);
		// A THICK SOLID BAND across the whole crest region (lip node → LIP_ALONG+2 upstream, spanning the
		// channel + flank), NOT a 1-thick line: a diagonal path's crest line would leave diagonal gaps the
		// pool leaks through, and step()-quantized headings can miss the true crest. Each column dams to
		// upperY+1 where the terrain is within MAX_DAM_BUILD of it — so the band seals the crest (terrain
		// near upperY) and is a no-op on the deep drop face (terrain far below, left as the natural cliff).
		int written = 0;
		for (int a = 0; a <= along + 2; a++) {
			int cxC = lipX + ux * a, czC = lipZ + uz * a;
			RiverCarve.Result cr = provider.carveAt(cxC, czC, 0.0);
			double half = cr.width > 0 ? cr.width / 2.0 : RiverCarve.W_MIN / 2.0;
			int span = (int) Math.ceil(half) + CREST_DAM_FLANK;
			for (int j = -span; j <= span; j++) {
				int x = cxC + px * j, z = czC + pz * j;
				written += damCrestColumn(level, p, cx0, cx1, cz0, cz1, x, z, damTop);
			}
		}
		return written;
	}

	/**
	 * Seal one crest-dam column up to {@code targetTopY}, OVERWRITING air AND water (unlike the rim-dam,
	 * which only fills air): Pass 1 fills the carved spill crest with a 1-block spill of pool water, and
	 * that block MUST become solid or the pool still pours over. Fills from the frozen solid terrain top
	 * up. No-op where already sealed or where the terrain sits more than {@link #MAX_DAM_BUILD} below
	 * (a deep drop — the pool it would hold was left dry by {@link #sealable}). Clamped to this chunk.
	 */
	private static int damCrestColumn(WorldGenLevel level, BlockPos.MutableBlockPos p,
			int cx0, int cx1, int cz0, int cz1, int x, int z, int targetTopY) {
		if (x < cx0 || x > cx1 || z < cz0 || z > cz1) {
			return 0; // outside this chunk (a neighbour writes its own slice)
		}
		int top = terrainTop(level, p, x, z, targetTopY, targetTopY - WALL_SCAN_DN);
		if (top >= targetTopY || top < targetTopY - MAX_DAM_BUILD) {
			return 0; // already walled, or too deep to seal fully
		}
		int written = 0;
		for (int y = top + 1; y <= targetTopY; y++) {
			written += setAny(level, p, x, y, z, WALL); // overwrite air OR the spill water block
		}
		return written;
	}

	/**
	 * Raise column {@code (x,z)} to a solid top of {@code targetTopY} by filling AIR with WALL from the
	 * manifested terrain top upward — a self-raise (writes only this column). Builds ONLY a full seal:
	 * where the terrain sits more than {@link #MAX_DAM_BUILD} below the target this is a no-op (no
	 * partial "spike" on a deep drop face — the pool that needed it was already left dry by
	 * {@link #sealable}), and where the terrain already reaches the target it is a no-op too. Fills AIR
	 * only (never overwrites terrain or water). Clamped to this chunk.
	 */
	private static int damColumnTo(WorldGenLevel level, BlockPos.MutableBlockPos p,
			int cx0, int cx1, int cz0, int cz1, int x, int z, int targetTopY) {
		if (x < cx0 || x > cx1 || z < cz0 || z > cz1) {
			return 0; // outside this chunk (a neighbour writes its own slice)
		}
		int top = terrainTop(level, p, x, z, targetTopY, targetTopY - WALL_SCAN_DN);
		if (top >= targetTopY || top < targetTopY - MAX_DAM_BUILD) {
			return 0; // already walled, or too deep to seal fully (a drop face — leave the natural cliff)
		}
		int written = 0;
		for (int y = top + 1; y <= targetTopY; y++) {
			written += fillIfAir(level, p, x, y, z, WALL);
		}
		return written;
	}

	/** Top SOLID Y at (x,z) scanning down from {@code yHi} to {@code yLo}; {@code yLo-1} if none. Skips
	 *  air AND fluids, so it reads the deterministic frozen terrain, never placed pool water. */
	private static int terrainTop(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int z,
			int yHi, int yLo) {
		int lo = Math.max(yLo, level.getMinBuildHeight());
		int hi = Math.min(yHi, level.getMaxBuildHeight() - 1);
		for (int y = hi; y >= lo; y--) {
			BlockState s = level.getBlockState(p.set(x, y, z));
			if (!s.isAir() && s.getFluidState().isEmpty()) {
				return y;
			}
		}
		return lo - 1;
	}

	private static int step(double v) {
		return v > 0.4 ? 1 : v < -0.4 ? -1 : 0;
	}

	private static boolean isAir(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
		return level.getBlockState(p.set(x, y, z)).isAir();
	}

	private static boolean isWater(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
		return !level.getBlockState(p.set(x, y, z)).getFluidState().isEmpty();
	}

	/** Set a block only where AIR, CLAMPED to this chunk + build column; returns 1 if written. Flag 2:
	 *  no neighbour update in worldgen (the FloatDespeckle/RiverFalls discipline). */
	private static int setIfAir(WorldGenLevel level, BlockPos.MutableBlockPos p, int cx0, int cx1,
			int cz0, int cz1, int x, int y, int z, BlockState s) {
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

	/** Set a block only where AIR (the caller has already clamped X/Z to this chunk); returns 1 if written. */
	private static int fillIfAir(WorldGenLevel level, BlockPos.MutableBlockPos p,
			int x, int y, int z, BlockState s) {
		if (y < level.getMinBuildHeight() || y > level.getMaxBuildHeight() - 1) {
			return 0;
		}
		if (!level.getBlockState(p.set(x, y, z)).isAir()) {
			return 0;
		}
		level.setBlock(p.set(x, y, z), s, 2);
		return 1;
	}

	/** Set a block OVERWRITING whatever is there (air or water — used only by the crest-dam to seal the
	 *  1-block spill; the caller has already clamped X/Z to this chunk); returns 1 if written. */
	private static int setAny(WorldGenLevel level, BlockPos.MutableBlockPos p,
			int x, int y, int z, BlockState s) {
		if (y < level.getMinBuildHeight() || y > level.getMaxBuildHeight() - 1) {
			return 0;
		}
		level.setBlock(p.set(x, y, z), s, 2);
		return 1;
	}
}
