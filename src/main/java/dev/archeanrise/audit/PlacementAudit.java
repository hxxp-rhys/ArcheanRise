package dev.archeanrise.audit;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.platform.Platform;
import dev.archeanrise.sitegrading.SiteGrading;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code /archeanrise-audit placement <x> <z>} — the block-level SiteGrading placement auditor
 * (blueprint §7, C1/C2/C4 subset). Locates the nearest gradable village to (x,z) in the ALREADY
 * GENERATED world, then measures the REAL post-placement blocks (not the field's prediction):
 * <ul>
 *   <li><b>Piece planes</b> — house (RIGID) vs street (terrain_matching) plane-Y ranges, the
 *       template spread that drives path breaks.</li>
 *   <li><b>C2 path slope</b> — max |Δ| between 4-adjacent street piece PLANES (the deterministic path
 *       height, immune to decoration noise), plus the actual graded GROUND flatness under every street
 *       column (heightmap scanned past logs); a villager-walkable path needs plane Δ ≤ 1 on flat ground.</li>
 *   <li><b>C4 inset</b> — per house, the highest adjacent street PLANE vs the house floor plane; a
 *       positive delta = the house floor is stepped below the path network it connects to.</li>
 * </ul>
 * Synchronous (one village, loads ~a few hundred chunks to FULL); writes a markdown report and
 * returns a chat/log summary. Use before/after a grading change to prove "broken → walkable".
 */
public final class PlacementAudit {
	/** How far (chunks) around (x,z) to search for a gradable village start. */
	private static final int SEARCH_CHUNKS = 8;

	private PlacementAudit() {}

	public static String audit(ServerLevel level, int wx, int wz) {
		StructureStart start = findNearestGradable(level, wx, wz);
		if (start == null) {
			return String.format(Locale.ROOT,
					"No gradable village found within %d chunks of %d,%d (in %s).",
					SEARCH_CHUNKS, wx, wz, level.dimension().location());
		}
		return analyze(level, start, wx, wz);
	}

	/** Force-load the ±SEARCH_CHUNKS neighbourhood to FULL and pick the gradable start nearest (wx,wz). */
	private static StructureStart findNearestGradable(ServerLevel level, int wx, int wz) {
		RegistryAccess ra = level.registryAccess();
		int ccx = wx >> 4;
		int ccz = wz >> 4;
		Set<StructureStart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		StructureStart best = null;
		long bestDistSq = Long.MAX_VALUE;
		for (int dcx = -SEARCH_CHUNKS; dcx <= SEARCH_CHUNKS; dcx++) {
			for (int dcz = -SEARCH_CHUNKS; dcz <= SEARCH_CHUNKS; dcz++) {
				ChunkPos cp = new ChunkPos(ccx + dcx, ccz + dcz);
				level.getChunk(cp.x, cp.z); // ensure generated + loaded (FULL) so starts/heightmaps are real
				for (StructureStart s : level.structureManager().startsForStructure(cp,
						st -> SiteGrading.isGradable(st, ra))) {
					if (!s.isValid() || !seen.add(s)) {
						continue;
					}
					BoundingBox b = s.getBoundingBox();
					long cx = Math.max(b.minX(), Math.min(wx, b.maxX()));
					long cz = Math.max(b.minZ(), Math.min(wz, b.maxZ()));
					long dx = cx - wx;
					long dz = cz - wz;
					long distSq = dx * dx + dz * dz;
					if (distSq < bestDistSq) {
						bestDistSq = distSq;
						best = s;
					}
				}
			}
		}
		return best;
	}

	private static String analyze(ServerLevel level, StructureStart start, int wx, int wz) {
		BoundingBox bb = start.getBoundingBox();
		// Per-column plane maps (packed key = ((long)x << 32) ^ (z & 0xffffffffL)).
		Map<Long, Integer> streetPlane = new HashMap<>(); // terrain_matching piece columns
		List<BoundingBox> houseBoxes = new ArrayList<>();  // RIGID piece boxes
		List<Integer> housePlanes = new ArrayList<>();
		int houseMinY = Integer.MAX_VALUE, houseMaxY = Integer.MIN_VALUE;
		int streetMinY = Integer.MAX_VALUE, streetMaxY = Integer.MIN_VALUE;
		int rigidCount = 0, matchingCount = 0, otherCount = 0;

		for (StructurePiece piece : start.getPieces()) {
			if (!(piece instanceof PoolElementStructurePiece pool)) {
				otherCount++;
				continue;
			}
			BoundingBox box = piece.getBoundingBox();
			int plane = box.minY() + pool.getGroundLevelDelta();
			StructureTemplatePool.Projection proj = pool.getElement().getProjection();
			if (proj == StructureTemplatePool.Projection.RIGID) {
				rigidCount++;
				houseBoxes.add(box);
				housePlanes.add(plane);
				houseMinY = Math.min(houseMinY, plane);
				houseMaxY = Math.max(houseMaxY, plane);
			} else {
				matchingCount++;
				streetMinY = Math.min(streetMinY, plane);
				streetMaxY = Math.max(streetMaxY, plane);
				for (int x = box.minX(); x <= box.maxX(); x++) {
					for (int z = box.minZ(); z <= box.maxZ(); z++) {
						// last piece wins on overlap (deterministic — serialized order), like the field
						streetPlane.put(key(x, z), plane);
					}
				}
			}
		}

		// C2 — path slope. PRIMARY = the street piece PLANES (deterministic; the intended path height
		// each street piece places at). This is structure-blind and immune to the water/void/decoration
		// pollution that wrecks a raw WORLD_SURFACE read, and it matches the field's pathPin.
		int[][] neigh = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		int c2Max = 0;
		int c2Violations = 0;
		int worstX = 0, worstZ = 0, worstA = 0, worstB = 0;
		for (var e : streetPlane.entrySet()) {
			int x = kx(e.getKey());
			int z = kz(e.getKey());
			int p = e.getValue();
			for (int[] d : neigh) {
				Integer np = streetPlane.get(key(x + d[0], z + d[1]));
				if (np == null) {
					continue;
				}
				int delta = Math.abs(p - np);
				if (delta > 1) {
					c2Violations++;
				}
				if (delta > c2Max) {
					c2Max = delta;
					worstX = x;
					worstZ = z;
					worstA = p;
					worstB = np;
				}
			}
		}

		// C2 (actual) — the real walkable GROUND at each street column (heightmap scanned DOWN past tree
		// logs/foliage, NOT the raw MOTION_BLOCKING_NO_LEAVES top, which counts a trunk as +5..+15),
		// FILTERED to sane land values (skip water/void columns, which read the build floor). Confirms the
		// terrain was actually graded to the pieces — measuring GRADE quality, not tree density.
		int floorGuard = SiteGrading.SEA_LEVEL - 16;
		List<Integer> sanes = new ArrayList<>();
		BlockPos.MutableBlockPos gp = new BlockPos.MutableBlockPos();
		for (long k : streetPlane.keySet()) {
			int s = groundHeight(level, gp, kx(k), kz(k)); // ground under any canopy, not the log top
			if (s >= floorGuard) {
				sanes.add(s); // skip water/void columns (build floor)
			}
		}
		int surfMin = Integer.MAX_VALUE, surfMax = Integer.MIN_VALUE, sane = sanes.size();
		int surfMedian = 0, flatPct = 0;
		if (sane > 0) {
			Collections.sort(sanes);
			surfMedian = sanes.get(sane / 2);
			int within = 0;
			for (int s : sanes) {
				surfMin = Math.min(surfMin, s);
				surfMax = Math.max(surfMax, s);
				if (Math.abs(s - surfMedian) <= 1) {
					within++;
				}
			}
			flatPct = 100 * within / sane; // ground under canopy (logs scanned past) → grade-quality, not tree density
		}

		// BURIAL — ground-vs-plane (2026-07-10, the buried-structures blind spot): flat/coplanar
		// checks are satisfied by a village uniformly entombed below flat ground, so measure the
		// signed offset median(ground) − median(plane). Positive = ground sits ABOVE the pieces
		// (buried); negative = pieces float above ground. Street planes when present (villages);
		// RIGID house planes otherwise (outposts and other street-less structures). For the
		// street-less case the ground sample comes from the RIGID boxes' own columns.
		int planeMedian = 0;
		int burial = 0;
		int burialSamples = 0; // gates the verdict — 0 means "no ground signal, cannot judge burial"
		int burialGroundMedian = 0; // the ground median the burial offset was computed FROM (branch-dependent)
		if (sane > 0 && !streetPlane.isEmpty()) {
			List<Integer> planes = new ArrayList<>(streetPlane.values());
			Collections.sort(planes);
			planeMedian = planes.get(planes.size() / 2);
			burial = surfMedian - planeMedian;
			burialSamples = sane;
			burialGroundMedian = surfMedian;
		} else if (!housePlanes.isEmpty()) {
			List<Integer> planes = new ArrayList<>(housePlanes);
			Collections.sort(planes);
			planeMedian = planes.get(planes.size() / 2);
			List<Integer> rigidGround = new ArrayList<>();
			for (BoundingBox box : houseBoxes) {
				for (int x = box.minX(); x <= box.maxX(); x += 3) {
					for (int z = box.minZ(); z <= box.maxZ(); z += 3) {
						int s = groundHeight(level, gp, x, z);
						if (s >= floorGuard) {
							rigidGround.add(s);
						}
					}
				}
			}
			if (!rigidGround.isEmpty()) {
				Collections.sort(rigidGround);
				// ground here includes the structure's own blocks (RIGID columns) — still the right
				// signal: a buried outpost has terrain, not structure, at the top of these columns
				burialGroundMedian = rigidGround.get(rigidGround.size() / 2);
				burial = burialGroundMedian - planeMedian;
				burialSamples = rigidGround.size();
			}
		}

		// C4 — inset, plane-based (structure-blind): a house whose floor sits >1 below the highest
		// adjacent street plane is stepped-down relative to the path network it connects to.
		int insetHouses = 0;
		int worstInset = 0;
		for (int i = 0; i < houseBoxes.size(); i++) {
			BoundingBox box = houseBoxes.get(i);
			int floor = housePlanes.get(i);
			int maxAdjStreet = Integer.MIN_VALUE;
			for (int x = box.minX() - 1; x <= box.maxX() + 1; x++) {
				for (int z = box.minZ() - 1; z <= box.maxZ() + 1; z++) {
					Integer sp = streetPlane.get(key(x, z));
					if (sp != null) {
						maxAdjStreet = Math.max(maxAdjStreet, sp);
					}
				}
			}
			if (maxAdjStreet != Integer.MIN_VALUE && maxAdjStreet - floor > 1) {
				insetHouses++;
				worstInset = Math.max(worstInset, maxAdjStreet - floor);
			}
		}

		String id = String.valueOf(level.registryAccess()
				.registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
				.getKey(start.getStructure()));
		int pieces = start.getPieces().size();
		String summary = String.format(Locale.ROOT,
				"Placement audit: %s @ [%d,%d..%d,%d] near %d,%d — %d pieces (%d houses, %d streets, %d other). "
						+ "house plane Y=[%s..%s] street plane Y=[%s..%s]. "
						+ "C2 path-slope (piece planes): max Δ=%d over %d street cols (%d adjacencies >1) — "
						+ "worst @ (%d,%d) plane %d vs %d. actual street surface Y=[%s..%s] median %d, %d%% within ±1 "
						+ "(%d sane cols). BURIAL: ground median %d vs plane median %d (offset %+d). "
						+ "C4: %d/%d houses step >1 below an adjacent street (worst -%d). %s",
				id, bb.minX(), bb.minZ(), bb.maxX(), bb.maxZ(), wx, wz,
				pieces, rigidCount, matchingCount, otherCount,
				span(houseMinY), span(houseMaxY), span(streetMinY), span(streetMaxY),
				c2Max, streetPlane.size(), c2Violations, worstX, worstZ, worstA, worstB,
				span(surfMin), span(surfMax), surfMedian, flatPct, sane,
				burialGroundMedian, planeMedian, burial,
				insetHouses, houseBoxes.size(), worstInset,
				verdict(c2Max, insetHouses, flatPct, sane, burial, burialSamples));

		writeReport(level, id, bb, wx, wz, pieces, rigidCount, matchingCount, houseMinY, houseMaxY,
				streetMinY, streetMaxY, surfMin, surfMax, surfMedian, flatPct, sane, c2Max, c2Violations,
				streetPlane.size(), worstX, worstZ, worstA, worstB, insetHouses, houseBoxes.size(), worstInset,
				planeMedian, burial, burialSamples, burialGroundMedian);
		return summary;
	}

	/** Graded ground: ≥ this % of street columns within ±1 of the median (trees are the minority above). */
	private static final int FLAT_PCT_MIN = 80;
	/** Max |median(ground) − median(street plane)| before the village counts as buried/floating. */
	private static final int BURIAL_MAX = 2;

	/**
	 * Verdict from placement (coplanar planes — flat-snap drives these to 0 regardless of grading),
	 * the ACTUAL graded surface ({@code flatPct}), AND ground-vs-plane {@code burial} (2026-07-10):
	 * a flat-snapped-but-entombed village has coplanar planes and perfectly "flat" ground ABOVE the
	 * pieces, so flatness alone cannot prove walkable — the ground must also sit AT the planes.
	 */
	private static String verdict(int c2Max, int insetHouses, int flatPct, int sane, int burial,
			int burialSamples) {
		boolean planesOk = c2Max <= 1 && insetHouses == 0;
		boolean groundOk = sane == 0 || flatPct >= FLAT_PCT_MIN;
		// gate on burialSamples, NOT sane: street-less (all-RIGID) structures have sane == 0 but a
		// valid RIGID-column ground signal — without this an entombed outpost would print WALKABLE
		boolean burialOk = burialSamples == 0 || Math.abs(burial) <= BURIAL_MAX;
		if (planesOk && groundOk && burialOk) {
			return "VERDICT: WALKABLE (coplanar pieces + " + flatPct + "% flat ground at the planes).";
		}
		StringBuilder b = new StringBuilder("VERDICT: BROKEN (");
		boolean first = true;
		if (c2Max > 1) {
			b.append("path step ").append(c2Max);
			first = false;
		}
		if (insetHouses > 0) {
			b.append(first ? "" : ", ").append(insetHouses).append(" inset houses");
			first = false;
		}
		if (!groundOk) {
			b.append(first ? "" : ", ").append("ground only ").append(flatPct).append("% flat (ungraded?)");
			first = false;
		}
		if (!burialOk) {
			b.append(first ? "" : ", ").append(burial > 0 ? "BURIED " : "FLOATING ")
					.append(Math.abs(burial)).append(" blocks (ground median vs plane median)");
		}
		return b.append(").").toString();
	}

	private static void writeReport(ServerLevel level, String id, BoundingBox bb, int wx, int wz,
			int pieces, int houses, int streets, int houseMinY, int houseMaxY, int streetMinY, int streetMaxY,
			int surfMin, int surfMax, int surfMedian, int flatPct, int sane, int c2Max, int c2Violations,
			int streetCols, int worstX, int worstZ, int worstA, int worstB, int insetHouses, int houseCount,
			int worstInset, int planeMedian, int burial, int burialSamples, int burialGroundMedian) {
		Path dir = Platform.get().reportsDir();
		Path file = dir.resolve("placement-audit-" + wx + "_" + wz + ".md");
		StringBuilder sb = new StringBuilder();
		sb.append("# Placement audit — ").append(id).append("\n\n");
		sb.append("- Dimension: ").append(level.dimension().location()).append(" · seed ")
				.append(level.getSeed()).append('\n');
		sb.append("- Queried near: ").append(wx).append(',').append(wz).append('\n');
		sb.append("- Structure bbox: [").append(bb.minX()).append(',').append(bb.minZ()).append("..")
				.append(bb.maxX()).append(',').append(bb.maxZ()).append("]\n");
		sb.append("- Pieces: ").append(pieces).append(" (").append(houses).append(" RIGID houses, ")
				.append(streets).append(" terrain_matching streets)\n\n");
		sb.append("## Piece planes (the template spread)\n");
		sb.append("- house plane Y = [").append(span(houseMinY)).append("..").append(span(houseMaxY)).append("]\n");
		sb.append("- street plane Y = [").append(span(streetMinY)).append("..").append(span(streetMaxY)).append("]\n\n");
		sb.append("## C2 — path slope (street piece planes, ").append(streetCols).append(" street columns)\n");
		sb.append("- max adjacent Δ = **").append(c2Max).append("** (villager-walkable needs ≤1)\n");
		sb.append("- adjacencies with Δ>1 = ").append(c2Violations).append('\n');
		sb.append("- worst junction @ world (").append(worstX).append(',').append(worstZ).append("): plane ")
				.append(worstA).append(" vs ").append(worstB).append(" (Δ").append(c2Max).append(")\n");
		sb.append("- actual walkable GROUND (logs/foliage scanned past, ").append(sane)
				.append(" sane land cols) Y = [").append(span(surfMin)).append("..").append(span(surfMax))
				.append("], median ").append(surfMedian).append(", **").append(flatPct)
				.append("% within ±1** of median (the graded-ground flatness signal — canopy excluded)\n\n");
		sb.append("## Burial — ground vs plane (the entombment signal)\n");
		sb.append("- plane median Y = ").append(planeMedian).append(", ground median Y = ")
				.append(burialGroundMedian).append(" → offset **").append(String.format(Locale.ROOT, "%+d", burial))
				.append("** (positive = ground above the pieces = buried; walkable needs |offset| ≤ ")
				.append(BURIAL_MAX).append(")\n\n");
		sb.append("## C4 — house/street step\n");
		sb.append("- ").append(insetHouses).append(" / ").append(houseCount)
				.append(" houses sit >1 below their highest adjacent street plane (worst -").append(worstInset).append(")\n\n");
		sb.append("## Verdict\n").append(verdict(c2Max, insetHouses, flatPct, sane, burial, burialSamples)).append('\n');
		try {
			Files.createDirectories(dir);
			Files.writeString(file, sb.toString());
			ArcheanRise.LOGGER.info("Placement audit report written to {}", file);
		} catch (IOException e) {
			ArcheanRise.LOGGER.warn("Could not write placement audit report: {}", e.toString());
		}
	}

	private static String span(int v) {
		return v == Integer.MAX_VALUE || v == Integer.MIN_VALUE ? "-" : Integer.toString(v);
	}

	/**
	 * The walkable GROUND Y at a column — the MOTION_BLOCKING_NO_LEAVES top scanned DOWN past tree
	 * LOGS/foliage to the first terrain block. That heightmap excludes leaves but INCLUDES logs, so a
	 * spruce/oak over a graded-flat street would otherwise read the trunk top (+5..+15) and falsely fail
	 * flatPct. Measuring the ground UNDER the canopy makes flatPct a GRADE-quality signal (is the terrain
	 * flattened to the plane), not a tree-density signal (a dense taiga village is still walkable on flat
	 * ground). Diagnostic-only — reads the already-generated world; determinism is not affected.
	 */
	private static int groundHeight(ServerLevel level, BlockPos.MutableBlockPos p, int x, int z) {
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
		int limit = SiteGrading.SEA_LEVEL - 20;
		for (int y = top; y >= limit; y--) {
			BlockState state = level.getBlockState(p.set(x, y, z));
			if (state.isAir() || isCanopy(state)) {
				continue;
			}
			return y; // first non-air, non-canopy block = the ground the grade shaped
		}
		return top; // all canopy/air to the guard (shouldn't happen on land) — fall back to the heightmap top
	}

	/** Tree/foliage a MOTION_BLOCKING_NO_LEAVES scan can top out on (logs are NOT excluded by that heightmap). */
	private static boolean isCanopy(BlockState state) {
		return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.SAPLINGS);
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	private static int kx(long k) {
		return (int) (k >> 32);
	}

	private static int kz(long k) {
		return (int) k;
	}
}
