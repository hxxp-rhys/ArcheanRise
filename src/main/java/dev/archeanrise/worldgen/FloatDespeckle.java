package dev.archeanrise.worldgen;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.sitegrading.SiteGrading;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * Issue 5 — size-gated float despeckle. Removes SMALL, fully-detached terrain clumps (the residual
 * floating-block artifacts that survive the v0.3 anisotropy fix — an uncompensated jaggedness ×Sv
 * spike sliced off by an amplified cave/surface band) while PRESERVING any large formation, so a
 * deliberately-added floating sky island (a possible future feature) is never touched. This
 * consciously revises the real-scale-design.md section-6 "prevent, do not remove" rule to
 * "prevent where cheap, and REMOVE small unsupported artifacts" — a pure density term cannot express
 * connectivity/support, so removal is the only source-agnostic, sky-island-safe guarantee (see
 * DECISIONS.md 2026-07-08).
 *
 * <p><b>Hook:</b> HEAD of {@code ChunkGenerator.applyBiomeDecoration} — post-carver (noise-caves AND
 * carver-caves final) and PRE-structure/PRE-feature, so it reads pure terrain and never sees or
 * removes a structure/feature block.
 *
 * <p><b>Sky-island-safe + deterministic by construction.</b> A candidate solid seeds a 26-connected
 * flood over solids (air/fluid are boundaries), capped at {@code maxBlocks}+1 and a bounding radius.
 * A component's identity is INDEPENDENT of which block seeds it, so a clump straddling a chunk border
 * gets the IDENTICAL verdict from either chunk (each removes only its own in-chunk share — no tear).
 * The verdict is a pure function of the seed-frozen post-carver terrain: a grounded finger or a large
 * island escapes the cap/radius → KEEP; only a component that closes within the cap → REMOVE. No RNG.
 */
public final class FloatDespeckle {

	/** Bounding-box half-extent (blocks) the flood may span before it is judged LARGE (kept). */
	private static final int SEARCH_RADIUS = 24;

	private FloatDespeckle() {}

	public static boolean enabled() {
		return ArcheanRise.config != null && ArcheanRise.config.floatDespeckleEnabled;
	}

	private static int maxBlocks() {
		return ArcheanRise.config == null ? 16 : ArcheanRise.config.floatDespeckleMaxBlocks;
	}

	private static int minY() {
		return ArcheanRise.config == null ? SiteGrading.SEA_LEVEL : ArcheanRise.config.floatDespeckleMinY;
	}

	/** True for a terrain-solid block (air and fluids — aquifer water/lava — are flood boundaries).
	 *  A block outside the readable features region is treated as a boundary (non-solid): reads stay
	 *  in-bounds, and the {@code maxBlocks} cap keeps any large formation that reaches the region edge. */
	private static boolean isSolid(WorldGenLevel level, BlockPos pos) {
		if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
			return false;
		}
		BlockState s = level.getBlockState(pos);
		return !s.isAir() && s.getFluidState().isEmpty();
	}

	/** A block is a despeckle CANDIDATE only if it is mostly exposed (>=4 of 6 face-neighbours open) —
	 *  this skips solid terrain surface (one open face) and floods only near-isolated blobs. */
	private static int openFaceCount(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
		int open = 0;
		if (!isSolid(level, p.set(x + 1, y, z))) open++;
		if (!isSolid(level, p.set(x - 1, y, z))) open++;
		if (!isSolid(level, p.set(x, y + 1, z))) open++;
		if (!isSolid(level, p.set(x, y - 1, z))) open++;
		if (!isSolid(level, p.set(x, y, z + 1))) open++;
		if (!isSolid(level, p.set(x, y, z - 1))) open++;
		return open;
	}

	public static void run(WorldGenLevel level, ChunkAccess chunk) {
		int maxBlocks = maxBlocks();
		int minY = Math.max(minY(), level.getMinBuildHeight());
		int top = level.getMaxBuildHeight() - 1;
		int cx0 = chunk.getPos().getMinBlockX();
		int cx1 = chunk.getPos().getMaxBlockX();
		int cz0 = chunk.getPos().getMinBlockZ();
		int cz1 = chunk.getPos().getMaxBlockZ();

		LongOpenHashSet visited = new LongOpenHashSet();
		BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		List<BlockPos> removals = new ArrayList<>();

		for (int x = cx0; x <= cx1; x++) {
			for (int z = cz0; z <= cz1; z++) {
				for (int y = minY; y <= top; y++) {
					if (visited.contains(BlockPos.asLong(x, y, z))) {
						continue;
					}
					if (!isSolid(level, scan.set(x, y, z))) {
						continue;
					}
					if (openFaceCount(level, probe, x, y, z) < 4) {
						continue; // part of a solid surface/mass, not a near-isolated blob
					}
					classify(level, x, y, z, maxBlocks, visited, removals, cx0, cx1, cz0, cz1);
				}
			}
		}
		if (!removals.isEmpty()) {
			BlockState air = Blocks.AIR.defaultBlockState();
			for (BlockPos p : removals) {
				level.setBlock(p, air, 2);
			}
			ArcheanRise.LOGGER.debug("FloatDespeckle: chunk {} removed {} floating block(s)",
					chunk.getPos(), removals.size());
		}
	}

	/**
	 * Flood the 26-connected solid component seeded at (sx,sy,sz), bounded by {@code maxBlocks}+1 and
	 * {@link #SEARCH_RADIUS}. If it CLOSES within both bounds it is a detached artifact → its
	 * current-chunk blocks are queued for removal; otherwise (grounded finger / large island) it is
	 * kept. All visited blocks are recorded so nearby candidates in the same component are skipped.
	 */
	private static void classify(WorldGenLevel level, int sx, int sy, int sz, int maxBlocks,
			LongOpenHashSet visited, List<BlockPos> removals, int cx0, int cx1, int cz0, int cz1) {
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		List<BlockPos> component = new ArrayList<>();
		long seed = BlockPos.asLong(sx, sy, sz);
		queue.enqueue(seed);
		visited.add(seed);
		boolean large = false;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

		while (!queue.isEmpty()) {
			long cur = queue.dequeueLong();
			int x = BlockPos.getX(cur), y = BlockPos.getY(cur), z = BlockPos.getZ(cur);
			component.add(new BlockPos(x, y, z));
			if (component.size() > maxBlocks
					|| Math.abs(x - sx) > SEARCH_RADIUS || Math.abs(y - sy) > SEARCH_RADIUS
					|| Math.abs(z - sz) > SEARCH_RADIUS) {
				large = true; // grounded finger or large formation (future sky island) — keep, stop growing
				break;
			}
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) {
							continue;
						}
						int nx = x + dx, ny = y + dy, nz = z + dz;
						long nk = BlockPos.asLong(nx, ny, nz);
						if (visited.contains(nk)) {
							continue;
						}
						if (isSolid(level, p.set(nx, ny, nz))) {
							visited.add(nk);
							queue.enqueue(nk);
						}
					}
				}
			}
		}
		if (large) {
			return; // KEEP — sky-island-safe: anything past the cap/radius is preserved
		}
		for (BlockPos bp : component) {
			if (bp.getX() >= cx0 && bp.getX() <= cx1 && bp.getZ() >= cz0 && bp.getZ() <= cz1) {
				removals.add(bp.immutable());
			}
		}
	}
}
