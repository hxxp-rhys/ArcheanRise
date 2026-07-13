package dev.archeanrise.sitegrading;

import dev.archeanrise.ArcheanRise;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and caches the {@link TargetField} for a structure start (blueprint §3.1–3.2), the shared
 * input to the authoritative cut+fill pass. The field is a pure function of (seed → serialized
 * pieces + predictor natural surface), so every chunk touching the start builds the identical
 * field (from cache) and edits only its own columns — the cross-chunk determinism invariant.
 */
public final class SiteGradeField {
	private static final int CACHE_CAP = 96;
	/** Access-order LRU, synchronized (worldgen worker threads); builds happen OUTSIDE the lock. */
	private static final Map<Long, TargetField> CACHE = Collections.synchronizedMap(
			new LinkedHashMap<>(128, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, TargetField> eldest) {
					return size() > CACHE_CAP;
				}
			});

	private SiteGradeField() {}

	/**
	 * The field for {@code start}, cached. Returns null when the start has no rigid/pool pieces.
	 * Logs the field's determinism/slope summary once per distinct field (on the cache-miss build).
	 */
	public static TargetField forStart(WorldGenLevel level, StructureStart start) {
		List<TargetField.Piece> pieces = new ArrayList<>();
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (StructurePiece piece : start.getPieces()) {
			if (!(piece instanceof PoolElementStructurePiece pool)) {
				continue;
			}
			boolean rigid = pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID;
			BoundingBox box = piece.getBoundingBox();
			int plane = box.minY() + pool.getGroundLevelDelta();
			pieces.add(new TargetField.Piece(box, plane, rigid));
			minX = Math.min(minX, box.minX());
			minZ = Math.min(minZ, box.minZ());
			maxX = Math.max(maxX, box.maxX());
			maxZ = Math.max(maxZ, box.maxZ());
		}
		if (pieces.isEmpty()) {
			return null;
		}
		long seed = level.getLevel().getSeed();
		int rampMax = ArcheanRise.config == null ? 32 : ArcheanRise.config.siteGradingApronRampMax;
		long key = key(seed, level, rampMax, pieces);
		TargetField cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		int pad = rampMax + 2;
		RandomState randomState = level.getLevel().getChunkSource().randomState();
		DensityFunction predictor = randomState.router().initialDensityWithoutJaggedness();
		int bottom = level.getMinBuildHeight();
		int top = level.getMaxBuildHeight();
		NaturalSurface natural = NaturalSurface.probe(predictor,
				minX - pad - NaturalSurface.PITCH, minZ - pad - NaturalSurface.PITCH,
				maxX + pad + NaturalSurface.PITCH, maxZ + pad + NaturalSurface.PITCH, bottom, top);

		long t0 = System.nanoTime();
		TargetField field = TargetField.build(pieces, natural, rampMax);
		long micros = (System.nanoTime() - t0) / 1000;

		CACHE.put(key, field); // LRU eviction handled by removeEldestEntry
		int envX = maxX - minX + 2 * pad + 1;
		int envZ = maxZ - minZ + 2 * pad + 1;
		ArcheanRise.LOGGER.info("SiteGrade field: {} pieces, env {}x{}, hash={}, converged={}, "
				+ "pathSlope={}, apronSlope={}, pathPin={}, inset={}, infeasible={} [{}] ({}us) — start @ [{},{}..{},{}]",
				pieces.size(), envX, envZ,
				String.format(java.util.Locale.ROOT, "%016x", field.hash()), field.converged(),
				field.maxPathSlope(), field.maxApronSlope(), field.pathPinConflict(),
				field.insetViolations(), field.infeasibleCells(), field.stats(), micros, minX, minZ, maxX, maxZ);
		if (!field.slopeGuaranteed()) {
			ArcheanRise.LOGGER.warn("SiteGrade field slope-1 NOT guaranteed (env {}x{}): {}"
					+ " — enable siteGradingVeto to reject un-gradeable sites if this is a relief/water case",
					envX, envZ, field.diag());
		}
		return field;
	}

	private static long key(long seed, WorldGenLevel level, int rampMax, List<TargetField.Piece> pieces) {
		long h = 0xcbf29ce484222325L ^ seed;
		// dimension + rampMax discriminate fields that share piece geometry but differ in context
		h = (h ^ level.getLevel().dimension().location().hashCode()) * 0x100000001b3L;
		h = (h ^ (rampMax & 0xffffffffL)) * 0x100000001b3L;
		for (TargetField.Piece p : pieces) {
			BoundingBox b = p.box();
			for (int v : new int[] {b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(),
					p.plane(), p.rigid() ? 1 : 0}) {
				h = (h ^ (v & 0xffffffffL)) * 0x100000001b3L;
			}
		}
		return h;
	}

	public static void clearCache() {
		CACHE.clear();
	}
}
