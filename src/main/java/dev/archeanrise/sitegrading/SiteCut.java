package dev.archeanrise.sitegrading;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * SiteGrading v2 CUT+FILL grade pass (blueprint §3.4, Phases 3a/3b/4). When the pieces are flat-snapped
 * to the platform ({@link DesignSurface#clampProjection} → the start piece's real projected height
 * since the 2026-07-10 burial fix, 3c), this grades the terrain TO that
 * platform so the coplanar pieces are neither buried nor floating:
 * <ul>
 *   <li><b>Own-piece cells (ANCHOR house / PATH street):</b> CUT the natural hill ABOVE the piece's box
 *       top (un-bury the roof/path + door approaches, leaving the placed piece intact), and FILL air/water
 *       strictly BELOW the piece ground plane for support (from the frozen {@code OCEAN_FLOOR_WG} floor,
 *       displacing water for over-sea support). NO resurface — the placed piece IS the surface; writing
 *       over it would deface the street/house (crit-5 own-structure inviolability).</li>
 *   <li><b>APRON cells (natural collar, no own piece):</b> CUT the hill down to the field target {@code T}
 *       / FILL the dip/water up to {@code T}, then RESURFACE the graded top with a biome-appropriate
 *       surface block so there are no bare-stone platforms.</li>
 * </ul>
 * Runs at {@code StructureStart.placeInChunk} TAIL, writing ONLY the current chunk; the field is the same
 * in every chunk, so the earthwork is seam-free.
 *
 * <p><b>Determinism (blueprint §3.1).</b> Every write is a pure function of seed. The Y-bounds are seed-
 * pure: {@code T = field.heightAt} (serialized pieces + predictor), and {@code WORLD_SURFACE_WG}/
 * {@code OCEAN_FLOOR_WG} are frozen at FEATURES (source-verified: CARVERS/FEATURES carry only
 * FINAL_HEIGHTMAPS, and {@code ProtoChunk.setBlockState} updates only the persisted-status heightmaps —
 * neither feature spill nor this pass mutates them). The live block reads only gate air/solid replacement
 * (the band → all-air / air-and-water→soil regardless of what spill left there), and the resurface material
 * is a BIOME lookup ({@link #surfaceMaterial}) — a pure seed function, NOT a live block read. So the block
 * SET and its states are seed-pure → demand-gen and pregen produce byte-identical output.
 *
 * <p><b>Foreign-yield (tertiary-safety core, blueprint §3.4/§5).</b> Every OTHER start referenced by this
 * chunk or its 8 neighbours has its {@code box} dilated by {@code BEARD_THIN + siteGradingForeignHaloExtra}
 * treated as fully off-limits — grading never touches a neighbour. See {@link #collectForeignHalos}.
 */
public final class SiteCut {

	/** Chunks a structure's box/references reach — the FEATURES region's readable radius (STRUCTURE_STARTS). */
	private static final int MAX_STRUCTURE_CHUNK_REACH = 8;

	/** Dilated XZ footprint of a foreign start (full column protection — Y is not restricted). */
	private record ForeignHalo(int minX, int maxX, int minZ, int maxZ) {
		boolean contains(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}
	}

	/** Per-(start,chunk) counters — logged for the earthwork/determinism instrumentation. */
	public record Result(int gradedColumns, int blocksCut, int blocksFilled, int foreignColumns, int foreignHalos) {
		/**
		 * VOLUMETRIC earthwork this chunk (cut + fill) — the DEBUG-log gate. Intentionally EXCLUDES the
		 * apron resurface cap: resurface writes 0–3 surface/subsurface blocks per apron column and can be
		 * idempotent (grass→grass where the apron is already at target), so counting it would fire the
		 * per-chunk DEBUG log for chunks that moved no terrain. Every village is logged once via the INFO
		 * per-field summary regardless, so a resurface-only chunk is not lost.
		 */
		public int writes() {
			return blocksCut + blocksFilled;
		}
	}

	private SiteCut() {}

	/**
	 * Grade the current chunk to {@code field}. Returns null when the grade sub-switch is off (field is
	 * still built + logged; no block writes).
	 */
	public static Result grade(WorldGenLevel level, StructureStart start, TargetField field, ChunkPos chunkPos) {
		if (ArcheanRise.config != null && !ArcheanRise.config.siteGradingCut) {
			return null;
		}

		int cMinX = chunkPos.getMinBlockX();
		int cMaxX = chunkPos.getMaxBlockX();
		int cMinZ = chunkPos.getMinBlockZ();
		int cMaxZ = chunkPos.getMaxBlockZ();
		List<ForeignHalo> foreignHalos = collectForeignHalos(level, start, chunkPos, cMinX, cMaxX, cMinZ, cMaxZ);

		// This start's piece boxes (houses AND streets) — used to cut the hill ABOVE a piece's box top
		// without touching the PLACED piece (plane..box top IS the template: house walls, street lamps).
		List<BoundingBox> ownPieces = new ArrayList<>();
		for (StructurePiece piece : start.getPieces()) {
			if (piece instanceof PoolElementStructurePiece) {
				ownPieces.add(piece.getBoundingBox());
			}
		}

		// Cap the per-column earthwork: a vetoed-in site's relief ≤ vetoMaxRelief, so (vetoMaxRelief +
		// apronRampMax) never clips a gradeable column; it only bounds pathological depth when the veto
		// is disabled. Under-footprint (ANCHOR) support fill is UNCAPPED (must reach the seabed, crit 6).
		int maxDepth = ArcheanRise.config == null ? 64
				: ArcheanRise.config.siteGradingVetoMaxRelief + ArcheanRise.config.siteGradingApronRampMax;
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState soil = Blocks.DIRT.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		int gradedColumns = 0;
		int blocksCut = 0;
		int blocksFilled = 0;
		int foreignColumns = 0;
		for (int x = cMinX; x <= cMaxX; x++) {
			for (int z = cMinZ; z <= cMaxZ; z++) {
				byte kind = field.kindAt(x, z);
				if (kind == TargetField.NATURAL) {
					continue; // untouched natural terrain
				}
				if (inAnyForeign(foreignHalos, x, z)) {
					foreignColumns++;
					continue; // foreign-yield
				}
				int t = field.heightAt(x, z);
				int surfaceTop = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1; // frozen surface top
				int oceanFloor = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;   // frozen solid floor

				if (kind == TargetField.ANCHOR || kind == TargetField.PATH) {
					// Own-piece cell (house OR street): cut the natural hill ABOVE the piece's box top
					// (un-bury the roof/path + door approaches), leaving the placed piece (plane..box top)
					// intact, and FILL the natural gap strictly BELOW the piece BOX for support over a
					// dip/water. NO resurface — the placed piece (house walls / street path) IS the column's
					// surface; writing soil over it would deface the street/house. (crit-5 own-structure
					// inviolability.)
					int pieceTop = pieceTopAt(ownPieces, x, z, t);
					if (surfaceTop > pieceTop) {
						int cutBottom = Math.max(pieceTop, surfaceTop - maxDepth);
						for (int y = surfaceTop; y > cutBottom; y--) {
							pos.set(x, y, z);
							if (level.getBlockState(pos).isAir()) {
								continue;
							}
							level.setBlock(pos, air, 2);
							blocksCut++;
						}
					}
					// Support fill BELOW the piece box only (oceanFloor+1 .. box.minY-1), displacing water
					// for over-sea support (crit-6; UNCAPPED to the seabed). The piece's own box volume
					// (box.minY..t) is NOT touched here: it holds the piece's DECLARED content — a house
					// foundation zone (closed air-only by the GradePad beard / M4 foundationFill) OR a
					// piece-declared OPEN volume such as a village WELL's water shaft, which must NOT be
					// dirt-filled (crit-5). Capping the fill at box.minY-1 distinguishes natural sea/gap
					// (solidify) from in-piece water (preserve) by piece geometry — deterministically.
					int pieceBottom = pieceBottomAt(ownPieces, x, z, t);
					blocksFilled += fill(level, pos, air, soil, x, z, oceanFloor, pieceBottom - 1);
					gradedColumns++;
					continue;
				}

				// APRON (natural collar, no own piece): cut the hill down to t / fill the dip/water up to t,
				// then resurface the graded top with a biome-appropriate surface block.
				BlockState surfaceMat = surfaceMaterial(level, pos, x, z, t);
				int newTop;
				if (surfaceTop > t) {
					int cutBottom = Math.max(t, surfaceTop - maxDepth);
					for (int y = surfaceTop; y > cutBottom; y--) {
						pos.set(x, y, z);
						if (level.getBlockState(pos).isAir()) {
							continue;
						}
						level.setBlock(pos, air, 2);
						blocksCut++;
					}
					newTop = cutBottom;
				} else if (oceanFloor < t) {
					int fillFrom = Math.max(oceanFloor, t - maxDepth);
					blocksFilled += fill(level, pos, air, soil, x, z, fillFrom, t);
					newTop = t;
				} else {
					newTop = t; // natural already at target
				}
				resurface(level, pos, x, z, newTop, surfaceMat, soil);
				gradedColumns++;
			}
		}
		return new Result(gradedColumns, blocksCut, blocksFilled, foreignColumns, foreignHalos.size());
	}

	/** Fill air/water in {@code (from .. to]} with soil (never touches an existing solid, e.g. a house floor). */
	private static int fill(WorldGenLevel level, BlockPos.MutableBlockPos pos, BlockState air, BlockState soil,
			int x, int z, int from, int to) {
		int n = 0;
		for (int y = from + 1; y <= to; y++) {
			pos.set(x, y, z);
			BlockState cur = level.getBlockState(pos);
			if (cur.isAir() || !cur.getFluidState().isEmpty()) {
				level.setBlock(pos, soil, 2);
				n++;
			}
		}
		return n;
	}

	/** Cap the graded top with the column's natural surface material + a shallow dirt subsurface. */
	private static void resurface(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z,
			int top, BlockState surfaceMat, BlockState soil) {
		pos.set(x, top, z);
		if (!level.getBlockState(pos).isAir()) {
			level.setBlock(pos, surfaceMat, 2);
		}
		for (int dy = 1; dy <= 2; dy++) {
			pos.set(x, top - dy, z);
			BlockState cur = level.getBlockState(pos);
			if (!cur.isAir() && cur.getFluidState().isEmpty() && cur != surfaceMat) {
				level.setBlock(pos, soil, 2);
			}
		}
	}

	/**
	 * Biome-appropriate resurface material. DETERMINISTIC — the biome is a pure function of seed, unlike
	 * a live {@code getBlockState} read which run-to-run feature-spill (boulders/disks/lakes at the
	 * fringe) can perturb, breaking the determinism gate (blueprint §3.1 fix #1). SurfaceRules replay is
	 * the blueprint's ideal (§3.4) but hits the private-node MC-internals wall (BC1); this biome heuristic
	 * is the documented fallback: sand in sandy biomes, grass elsewhere (villages spawn in
	 * plains/savanna/taiga/snowy → grass, desert → sand).
	 */
	static BlockState surfaceMaterial(WorldGenLevel level, BlockPos.MutableBlockPos pos,
			int x, int z, int y) {
		var biome = level.getBiome(pos.set(x, y, z));
		if (biome.is(Biomes.DESERT) || biome.is(Biomes.BEACH) || biome.is(Biomes.SNOWY_BEACH)
				|| biome.is(BiomeTags.IS_BADLANDS)) {
			return Blocks.SAND.defaultBlockState();
		}
		return Blocks.GRASS_BLOCK.defaultBlockState();
	}

	/**
	 * Foreign-start halos over the 3×3 chunk neighbourhood, dilated by the beard halo, de-duped by
	 * identity, kept only when the dilated footprint overlaps THIS chunk. Enumerates each neighbour's
	 * {@code getAllReferences()} directly (not {@code StructureManager.startsForStructure}, whose
	 * transitive origin-deref can hit a chunk outside the FEATURES region and THROW), and reads a
	 * referenced start's origin only within the region's readable radius (chessboard distance ≤ 8); a
	 * farther origin (only a near-max-reach foreign structure) is skipped — never force-gen, never a crash.
	 */
	private static List<ForeignHalo> collectForeignHalos(WorldGenLevel level, StructureStart start,
			ChunkPos chunkPos, int cMinX, int cMaxX, int cMinZ, int cMaxZ) {
		int haloExtra = ArcheanRise.config == null ? 2 : ArcheanRise.config.siteGradingForeignHaloExtra;
		int halo = SiteGrading.BEARD_THIN + haloExtra;
		BoundingBox ownBox = start.getBoundingBox();
		Set<StructureStart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<ForeignHalo> foreignHalos = new ArrayList<>();
		for (int dcx = -1; dcx <= 1; dcx++) {
			for (int dcz = -1; dcz <= 1; dcz++) {
				ChunkAccess neighbour = level.getChunk(chunkPos.x + dcx, chunkPos.z + dcz,
						ChunkStatus.STRUCTURE_REFERENCES);
				for (var entry : neighbour.getAllReferences().entrySet()) {
					var structure = entry.getKey();
					for (long originLong : entry.getValue()) {
						ChunkPos origin = new ChunkPos(originLong);
						if (chunkPos.getChessboardDistance(origin.x, origin.z) > MAX_STRUCTURE_CHUNK_REACH) {
							continue; // origin unreadable at this hook — skip (never force-gen / crash)
						}
						StructureStart other = level.getChunk(origin.x, origin.z, ChunkStatus.STRUCTURE_STARTS)
								.getStartForStructure(structure);
						if (other == null || !other.isValid() || other == start || !seen.add(other)) {
							continue;
						}
						if (other.getStructure() == start.getStructure()
								&& sameBox(other.getBoundingBox(), ownBox)) {
							continue; // the graded start itself, returned as a distinct object (defensive)
						}
						BoundingBox b = other.getBoundingBox();
						// Y-AWARE foreign-yield: skip an UNDERGROUND foreign structure (mineshaft, stronghold,
						// ancient city) whose box top is below sea level — it has no surface blocks or beard the
						// surface grade could disturb, and protecting its whole XZ column would (catastrophically)
						// yield the entire village that sits above it. Only surface structures need the collar.
						// Accepted residual: an APRON support-fill over deep water above such a structure can
						// replace its surrounding open WATER with soil (never its solid blocks — fill skips
						// solids); this is bounded by waterVetoDepth and strictly better than yielding the
						// whole overhead village. Documented in limitations/structure-grading.md.
						if (b.maxY() < SiteGrading.SEA_LEVEL) {
							continue;
						}
						int minX = b.minX() - halo;
						int maxX = b.maxX() + halo;
						int minZ = b.minZ() - halo;
						int maxZ = b.maxZ() + halo;
						if (maxX < cMinX || minX > cMaxX || maxZ < cMinZ || minZ > cMaxZ) {
							continue;
						}
						foreignHalos.add(new ForeignHalo(minX, maxX, minZ, maxZ));
					}
				}
			}
		}
		return foreignHalos;
	}

	/** Highest box top (maxY) over this start's pieces covering (x,z); {@code fallback} if none. */
	private static int pieceTopAt(List<BoundingBox> ownPieces, int x, int z, int fallback) {
		int top = Integer.MIN_VALUE;
		for (BoundingBox b : ownPieces) {
			if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
				top = Math.max(top, b.maxY());
			}
		}
		return top == Integer.MIN_VALUE ? fallback : top;
	}

	/**
	 * Lowest box bottom (minY) over this start's pieces covering (x,z); {@code fallback} if none. The
	 * support fill stops strictly below this, so a piece's DECLARED sub-plane volume (a well's water
	 * shaft, a piece-interior void) is never dirt-filled (crit-5); only the natural gap under the box
	 * is solidified for support.
	 */
	private static int pieceBottomAt(List<BoundingBox> ownPieces, int x, int z, int fallback) {
		int bottom = Integer.MAX_VALUE;
		for (BoundingBox b : ownPieces) {
			if (x >= b.minX() && x <= b.maxX() && z >= b.minZ() && z <= b.maxZ()) {
				bottom = Math.min(bottom, b.minY());
			}
		}
		return bottom == Integer.MAX_VALUE ? fallback : bottom;
	}

	private static boolean inAnyForeign(List<ForeignHalo> halos, int x, int z) {
		for (ForeignHalo h : halos) {
			if (h.contains(x, z)) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameBox(BoundingBox a, BoundingBox b) {
		return a.minX() == b.minX() && a.minY() == b.minY() && a.minZ() == b.minZ()
				&& a.maxX() == b.maxX() && a.maxY() == b.maxY() && a.maxZ() == b.maxZ();
	}
}
