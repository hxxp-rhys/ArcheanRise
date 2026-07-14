package dev.archeanrise.sitegrading;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.util.ArrayList;
import java.util.List;

/**
 * Issues 2/3/4 — post-placement EXTERIOR grade for add-on (foreign, {@code !isGradable}) BEARD_THIN
 * surface structures. The noise-stage {@link ForeignInsetBeard} clears the box interior; this shapes
 * the natural terrain AROUND the box (never the structure's own blocks — everything it writes is
 * outside/below the piece box, guaranteed natural) so the inset no longer reads as a machined rectangle:
 * <ul>
 *   <li><b>Issue 2 — sloped cut face:</b> where the hill rises above the box roof outside the footprint,
 *       cut it back to a {@link #SLOPE_MIN}..{@link #SLOPE_MAX} (rise/run) ramp that rises to meet the
 *       natural hill — replacing the vertical wall the beard leaves.</li>
 *   <li><b>Issue 3 — overhang fill:</b> where a piece floats over a drop &gt; {@link #overhangMin()}
 *       blocks, fill a support column under it and a steep, ±1-jittered declining bank out to the land.</li>
 *   <li><b>Issue 4 — tunnel naturalization:</b> where a piece bored through a hill (natural surface above
 *       the box roof), flare the tunnel mouth — remove {@link #tunnelBase()} + (1..3, seed) blocks off the
 *       real solid ceiling/sides so it reads as a cave, not a drilled box.</li>
 * </ul>
 *
 * <p><b>Hook / coverage:</b> runs at the shared features-stage pre-pass (post-carver, pre-structure),
 * enumerating foreign starts REFERENCED by the current chunk (not just box-intersecting), so ramps that
 * reach beyond the box are written seam-free from whichever chunk covers them.
 *
 * <p><b>Determinism:</b> Y-bounds come from the frozen WG heightmaps + the seed-placed piece boxes + a
 * positional hash (no {@code java.util.Random}); cuts are byte-identical, the issue-3 fill is bounded-
 * residual exactly like {@link SiteCut}. A per-start {@link NaturalSurface} predictor probe (global start
 * envelope) is cached so every chunk touching the start sees the identical true-hill surface.
 */
public final class ForeignInsetGrade {

	private static final double SLOPE_MIN = 1.5; // gentlest permitted rise/run (3/2)
	private static final double SLOPE_MAX = 4.0; // steepest permitted rise/run (4/1)
	/** Beyond the box roof the hill is natural again; must match ForeignInsetBeard.ROOF_TAPER. */
	private static final int ROOF_TAPER = 8;
	private static final int MAX_STRUCTURE_CHUNK_REACH = 8;
	/**
	 * Blocks of unbroken rock that must sit beneath the ramp before the cut is allowed (0.3.18 overhang
	 * guard, {@link #unsupportedAtRamp}). Big enough that an arch slab (measured: 4 blocks) and an arch
	 * stem (0) are caught; small enough that an honest hillside standing on a cave roof still reads as
	 * ground and keeps its grade.
	 */
	private static final int SUPPORT_PROBE = 16;

	private ForeignInsetGrade() {}

	public static boolean enabled() {
		return SiteGrading.enabled() && (ArcheanRise.config == null || ArcheanRise.config.insetForeignGrade);
	}

	private static int gradeReach() {
		return ArcheanRise.config == null ? 24 : ArcheanRise.config.insetForeignGradeReach;
	}

	private static int overhangMin() {
		return ArcheanRise.config == null ? 3 : ArcheanRise.config.insetForeignOverhangMin;
	}

	private static int tunnelBase() {
		return ArcheanRise.config == null ? 2 : ArcheanRise.config.insetForeignTunnelBase;
	}

	private record Pieces(List<BoundingBox> boxes, List<Integer> planes) {}

	/** Grade the current chunk's columns to every eligible foreign start that reaches it. */
	public static void grade(WorldGenLevel level, ChunkPos chunkPos, StructureManager structureManager) {
		RandomState randomState = level.getLevel().getChunkSource().randomState();
		ChunkGenerator generator = level.getLevel().getChunkSource().getGenerator();
		DensityFunction predictor = randomState.router().initialDensityWithoutJaggedness();
		long seed = level.getLevel().getSeed();
		int reach = gradeReach();
		int cMinX = chunkPos.getMinBlockX(), cMaxX = chunkPos.getMaxBlockX();
		int cMinZ = chunkPos.getMinBlockZ(), cMaxZ = chunkPos.getMaxBlockZ();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (StructureStart start : structureManager.startsForStructure(chunkPos,
				s -> ForeignInsetBeard.isEligible(s, level.registryAccess()))) {
			// BURIAL GATE (0.3.14) — must skip exactly the starts ForeignInsetBeard skipped, or the beard and
			// the grade would treat different piece sets (the invariant ForeignInsetBeard documents). The
			// predicate is seed-pure, so both passes reach the same verdict for the same start.
			if (BuriedStructures.isBuriedStart(start, generator, randomState, level, level.registryAccess())) {
				continue;
			}
			Pieces pieces = collectPieces(start);
			if (pieces.boxes.isEmpty()) {
				continue;
			}
			// Envelope over ALL of this start's pieces (global, not the current chunk) so heightAt is
			// identical from every chunk touching the start.
			int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (BoundingBox b : pieces.boxes) {
				minX = Math.min(minX, b.minX()); minZ = Math.min(minZ, b.minZ());
				maxX = Math.max(maxX, b.maxX()); maxZ = Math.max(maxZ, b.maxZ());
			}
			int pad = reach + NaturalSurface.PITCH;
			NaturalSurface natural = NaturalSurface.probe(predictor, minX - pad, minZ - pad, maxX + pad, maxZ + pad,
					level.getMinBuildHeight(), level.getMaxBuildHeight());

			for (int x = cMinX; x <= cMaxX; x++) {
				for (int z = cMinZ; z <= cMaxZ; z++) {
					gradeColumn(level, pos, pieces, natural, seed, x, z, reach);
				}
			}
		}
	}

	private static void gradeColumn(WorldGenLevel level, BlockPos.MutableBlockPos pos, Pieces pieces,
			NaturalSurface natural, long seed, int x, int z, int reach) {
		// Find the nearest piece box (by horizontal Euclidean distance to its edges) and whether we are
		// inside any footprint.
		int nearest = -1;
		double nearestRun = Double.MAX_VALUE;
		boolean inside = false;
		int insidePiece = -1;
		for (int i = 0; i < pieces.boxes.size(); i++) {
			BoundingBox b = pieces.boxes.get(i);
			int dx = Math.max(0, Math.max(b.minX() - x, x - b.maxX()));
			int dz = Math.max(0, Math.max(b.minZ() - z, z - b.maxZ()));
			double run = Math.sqrt((double) dx * dx + (double) dz * dz);
			if (dx == 0 && dz == 0) {
				inside = true;
				insidePiece = i;
			}
			if (run < nearestRun) {
				nearestRun = run;
				nearest = i;
			}
		}
		if (nearest < 0 || nearestRun > reach) {
			return;
		}
		int naturalHill = natural.heightAt(x, z);
		BoundingBox nb = pieces.boxes.get(nearest);

		if (inside) {
			BoundingBox box = pieces.boxes.get(insidePiece);
			int plane = pieces.planes.get(insidePiece);
			// Issue 3 support: fill a genuine overhang (piece floats far above the solid floor).
			int solidFloor = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
			if (plane - solidFloor > overhangMin()) {
				fill(level, pos, x, z, solidFloor, plane - 1);
			}
			// Issue 4 tunnel: the hill sat above this piece's roof -> the piece bored through it. Flare the
			// real solid ceiling (above box.maxY + ROOF_TAPER, where the beard's carve ends) at the mouth.
			if (naturalHill > box.maxY() + ROOF_TAPER && isMouth(pieces, x, z)) {
				int ceil = firstSolidAtOrAbove(level, pos, x, z, box.maxY() + ROOF_TAPER);
				if (ceil > 0) {
					int extra = tunnelBase() + 1 + (int) Math.floorMod(hash(seed, x, z), 3); // base + 1..3
					for (int y = ceil; y < ceil + extra && y < level.getMaxBuildHeight(); y++) {
						clearSolid(level, pos, x, y, z);
					}
				}
			}
			return;
		}

		// Outside every footprint: Issue 2 sloped cut face up to natural terrain.
		double slope = slopeAt(seed, nb, x, z);
		int rampBase = nb.maxY();
		int rampTop = rampBase + (int) Math.round(nearestRun * slope);
		if (rampTop >= naturalHill) {
			return; // ramp already met natural terrain — leave the hill's bulk
		}

		// OVERHANG GUARD (0.3.18) — never cut into rock that is not resting on the ground.
		//
		// The cut removes everything above rampTop and leaves whatever is below it. That is only sound if
		// what is left is standing on something. Archean Rise's mountains ARCH: a column can read
		// solid / void / solid. When rampTop lands inside the upper mass, the cut shaves its top off and
		// leaves a thin slab sitting on nothing but air — and in the next column along, the same cut takes
		// out the stem the arch was standing on. The leftover slabs are a floating island.
		//
		// Measured (2026-07-14), a 5x5 bush on an arched shoulder:
		//     column (5941,-3236)   before: solid 227..249            after: <gone>      (the stem)
		//     column (5950,-3232)   before: solid 252..290            after: 252..255    (the slab)
		// 187 severed attachments, 240 orphaned cells. Footprint area was NOT the discriminator — a 5x4
		// bush was clean and a 25x27 sewage system was not. The terrain is.
		//
		// So: probe down from the ramp. If we meet air (or water) within SUPPORT_PROBE blocks, the ramp has
		// landed in or under an arch and the rock here is load-bearing for something we cannot see from one
		// column. Cut nothing. This is purely SUBTRACTIVE — it only ever declines to cut, so a column of
		// honest ground-resting hillside behaves exactly as it did before, and no terrain that used to
		// survive can now be removed.
		if (unsupportedAtRamp(level, pos, x, z, rampTop)) {
			return;
		}
		// CONTIGUITY GUARD (0.3.18) — cut only the run of rock standing ON the ramp, never across a void.
		//
		// This used to scan from the surface DOWN to rampTop, clearing every solid block on the way. In an
		// arched mountain a column reads solid / void / solid, so that downward scan reached straight over
		// the air gap and deleted the detached upper mass as well — a mass which carries on past the apron.
		// Taking out only the slice of it that happened to fall inside our 24-block reach severed the rest.
		//
		// Measured (2026-07-14), a buttress column beside additionalstructures:sewage_system_1:
		//     before: solid 204..253   [void]   269..282      <- two masses
		//     after : solid 204..222                          <- the old cut took BOTH
		// The ramp sat at y 222, thirty blocks below the upper mass it destroyed. 98 severed attachments;
		// 774 cells left hanging.
		//
		// Walking UP from the ramp and stopping at the first air confines the cut to the one mass the ramp
		// is actually shaping. Anything above a void is a separate body of rock whose extent this column
		// cannot see, so we leave it alone. Purely SUBTRACTIVE: on an ordinary hillside — one unbroken run
		// from the ramp to the surface — this removes exactly the blocks the old loop did.
		int scanTop = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
		boolean cut = false;
		for (int y = rampTop + 1; y <= scanTop; y++) {
			if (!clearSolid(level, pos, x, y, z)) {
				break; // first void above the ramp — everything higher is a separate mass
			}
			cut = true;
		}
		if (cut) {
			resurface(level, pos, x, z, rampTop);
		}
	}

	/** Deterministic per-structure base slope + per-column ±jitter, clamped to [SLOPE_MIN, SLOPE_MAX]. */
	private static double slopeAt(long seed, BoundingBox box, int x, int z) {
		double s0 = SLOPE_MIN + (SLOPE_MAX - SLOPE_MIN) * hash01(hash(seed, box.minX(), box.minZ()));
		double j = (hash01(hash(seed ^ 0x5DEECE66DL, x, z)) - 0.5) * 0.5;
		return Math.max(SLOPE_MIN, Math.min(SLOPE_MAX, s0 + j));
	}

	/** A footprint column is a tunnel MOUTH if an 8-neighbour column is NOT inside any footprint. */
	private static boolean isMouth(Pieces pieces, int x, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				if (!insideAny(pieces, x + dx, z + dz)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean insideAny(Pieces pieces, int x, int z) {
		for (BoundingBox b : pieces.boxes) {
			if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
				return true;
			}
		}
		return false;
	}

	private static int firstSolidAtOrAbove(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int from) {
		int top = level.getMaxBuildHeight() - 1;
		for (int y = Math.max(from, level.getMinBuildHeight()); y <= top; y++) {
			BlockState s = level.getBlockState(pos.set(x, y, z));
			if (!s.isAir() && s.getFluidState().isEmpty()) {
				return y;
			}
		}
		return -1;
	}

	/**
	 * True when the rock this column would be left standing on, after cutting down to {@code rampTop}, is
	 * NOT resting on the ground — i.e. the ramp has landed inside, or underneath, an arch.
	 *
	 * <p>Probe straight down from {@code rampTop}. Air or water within {@link #SUPPORT_PROBE} blocks means
	 * the slab we are about to leave behind is floating, or the mass we are about to cut is the stem holding
	 * an arch up. Either way, one column cannot see what else that rock is carrying, so we decline to cut.
	 *
	 * <p>The probe is BOUNDED on purpose. An honest hillside sitting on a cave roof is solid for tens of
	 * blocks below the ramp and reads as supported, so the grade keeps working over caves; only genuinely
	 * thin, air-backed rock — the arch slabs and stems that produce the floating islands — is spared.
	 *
	 * <p>Seed-pure in the only sense that matters here: it reads the post-carver block world, which is
	 * exactly what the cut itself reads ({@code scanTop} via WORLD_SURFACE_WG, {@code clearSolid}), and it
	 * is evaluated per column, so every chunk that touches this column reaches the same verdict.
	 */
	private static boolean unsupportedAtRamp(WorldGenLevel level, BlockPos.MutableBlockPos pos,
			int x, int z, int rampTop) {
		int floor = level.getMinBuildHeight();
		for (int n = 0, y = rampTop; n <= SUPPORT_PROBE; n++, y--) {
			if (y < floor) {
				return false; // walked out the bottom of the world on solid rock — supported
			}
			BlockState s = level.getBlockState(pos.set(x, y, z));
			if (s.isAir() || !s.getFluidState().isEmpty()) {
				return true; // a void under the ramp — arch slab or arch stem; do not cut
			}
		}
		return false; // SUPPORT_PROBE blocks of unbroken rock beneath the ramp — this is ground
	}

	/** Set a solid block to air (never touch air/fluid); returns true if a solid was removed. */
	private static boolean clearSolid(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
		BlockState s = level.getBlockState(pos.set(x, y, z));
		if (!s.isAir() && s.getFluidState().isEmpty()) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
			return true;
		}
		return false;
	}

	/** Fill air/water in (from..to] with soil for support (never replaces an existing solid). */
	private static void fill(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int from, int to) {
		BlockState soil = Blocks.DIRT.defaultBlockState();
		for (int y = from + 1; y <= to; y++) {
			BlockState cur = level.getBlockState(pos.set(x, y, z));
			if (cur.isAir() || !cur.getFluidState().isEmpty()) {
				level.setBlock(pos, soil, 2);
			}
		}
	}

	/** Cap the cut/fill top with a biome-appropriate surface + shallow dirt subsurface (seed-pure). */
	private static void resurface(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, int top) {
		BlockState surface = SiteCut.surfaceMaterial(level, pos, x, z, top);
		if (!level.getBlockState(pos.set(x, top, z)).isAir()) {
			level.setBlock(pos, surface, 2);
		}
		BlockState soil = Blocks.DIRT.defaultBlockState();
		for (int dy = 1; dy <= 2; dy++) {
			BlockState cur = level.getBlockState(pos.set(x, top - dy, z));
			if (!cur.isAir() && cur.getFluidState().isEmpty() && cur != surface) {
				level.setBlock(pos, soil, 2);
			}
		}
	}

	private static Pieces collectPieces(StructureStart start) {
		List<BoundingBox> boxes = new ArrayList<>();
		List<Integer> planes = new ArrayList<>();
		for (StructurePiece piece : start.getPieces()) {
			if (piece instanceof PoolElementStructurePiece pool
					&& pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
				BoundingBox box = piece.getBoundingBox();
				if (box.maxY() < SiteGrading.SEA_LEVEL) {
					continue; // underground piece — not a surface inset
				}
				boxes.add(box);
				planes.add(box.minY() + pool.getGroundLevelDelta());
			}
		}
		return new Pieces(boxes, planes);
	}

	/** splitmix64 positional hash (seed-pure, order-independent) — never java.util.Random. */
	private static long hash(long seed, int x, int z) {
		long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		return h ^ (h >>> 31);
	}

	private static double hash01(long h) {
		return (h >>> 11) * 0x1.0p-53;
	}
}
