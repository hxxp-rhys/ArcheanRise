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
import net.minecraft.world.level.chunk.LevelChunkSection;

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

	/**
	 * Bounding-box half-extent (blocks) the flood may span before it is judged LARGE (kept).
	 *
	 * <p>Raised 24 → 48 in v0.3.19. The natural detached masses Archean Rise's arched mountains produce are
	 * far bigger than the jaggedness specks this was originally sized for: the one measured on 2026-07-14
	 * (with ZERO mods loaded) is **771 blocks with a 57 × 40 × 53 bounding box** — half-extents of ~28, so
	 * the old radius rejected it before the block cap ever got a look in. 48 clears the observed worst case
	 * with headroom, and the {@code maxBlocks} cap remains the tighter bound in practice, so this does not
	 * make a grounded-mountain flood meaningfully more expensive (it bails on the block cap first).
	 */
	private static final int SEARCH_RADIUS = 48;

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

		// SCAN BOUND (v0.3.19) — stop at the chunk's highest non-empty section, not at the build ceiling.
		//
		// This used to walk every column from minY to getMaxBuildHeight()-1 = y 767. In an Archean Rise
		// world the land tops out around y 300 and everything above it is empty sky, so ~450 of the 705
		// y-levels per column were pure air: ~115,000 wasted getBlockState calls per chunk, every chunk.
		// That cost is the reason this pass could not be afforded and shipped disabled. A floating island
		// is solid, so it lives inside a non-empty section by definition — bounding the scan to the top
		// non-empty section cannot miss one, and it is exact rather than heuristic (unlike trusting a
		// heightmap that carvers may have mutated).
		int top = level.getMinBuildHeight();
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = sections.length - 1; i >= 0; i--) {
			if (!sections[i].hasOnlyAir()) {
				top = chunk.getSectionYFromSectionIndex(i) * 16 + 15;
				break;
			}
		}
		top = Math.min(top, level.getMaxBuildHeight() - 1);
		if (top < minY) {
			return; // nothing solid at or above minY in this chunk
		}
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
					classify(level, x, y, z, maxBlocks, visited, removals);
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
			LongOpenHashSet visited, List<BlockPos> removals) {
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
			for (int dx = -1; dx <= 1 && !large; dx++) {
				for (int dy = -1; dy <= 1 && !large; dy++) {
					for (int dz = -1; dz <= 1 && !large; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) {
							continue;
						}
						int nx = x + dx, ny = y + dy, nz = z + dz;

						// REGION-EDGE GUARD (v0.3.19) — never judge a component we cannot fully SEE.
						//
						// isSolid() reports anything outside the readable features region as non-solid. That
						// silently CLIPS a component at the region edge, so a mass that carries on into the
						// next chunk — and is attached to the ground out there — looks closed, is judged a
						// detached artifact, and is deleted.
						//
						// The original code was safe from this only by accident: at maxBlocks = 16 the flood
						// could never reach the edge, and the class doc leaned on exactly that ("the maxBlocks
						// cap keeps any large formation that reaches the region edge"). Raising the cap to 2048
						// to catch real floating masses destroys that assumption — measured 2026-07-14: it tore
						// the support out from under a hillside and left a NEW 229-block island above
						// additionalstructures:bush_4, a site that was clean before.
						//
						// So: touching the edge means "unjudgeable", not "closed". Treat it as LARGE and KEEP.
						// Removal stays sound at ANY cap — the pass can only ever delete a component it has
						// seen in full.
						if (!level.hasChunk(nx >> 4, nz >> 4)) {
							large = true;
							break;
						}

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
			if (large) {
				break;
			}
		}
		if (large) {
			return; // KEEP — anything past the cap/radius, or reaching the region edge, is preserved
		}
		// REMOVE THE WHOLE COMPONENT (v0.3.19) — not just this chunk's share of it.
		//
		// This used to add only the blocks inside the current chunk, on the reasoning that every chunk the
		// clump touches would flood it, reach the same verdict, and clear its own share ("no tear"). That
		// holds only while clumps are TINY. A chunk seeds a flood solely from blocks with >= 4 open faces:
		// a 16-block blob is all surface, so every chunk holding a piece of it has a seed. A 771-block mass
		// has a SOLID INTERIOR — a chunk holding only interior blocks has no qualifying seed, never floods
		// it, and never clears its share. The clump is then half-removed and the remnant is left hanging.
		//
		// Measured 2026-07-14 at maxBlocks=2048: 972 blocks cleared near additionalstructures:bush_4 and a
		// 229-block remnant left floating — a NEW island, at a site that was clean. The tear, not the cap,
		// was the defect the raised cap exposed.
		//
		// Clearing the entire component in one pass removes the tear by construction. Whichever chunk gets
		// there first takes the whole thing; the others simply find air and do nothing, so the outcome does
		// not depend on chunk order. Writes stay inside the readable region — the edge guard above
		// guarantees the component never left it.
		for (BlockPos bp : component) {
			removals.add(bp.immutable());
		}
	}
}
