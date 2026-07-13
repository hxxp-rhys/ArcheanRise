package dev.archeanrise.sitegrading;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.mixin.JigsawStructureAccessor;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BURIAL GATE — "is this foreign structure a SURFACE structure, or is it deliberately BURIED?"
 *
 * <p><b>The bug this exists to fix.</b> {@link ForeignInsetBeard} / {@link ForeignInsetGrade} are
 * earthwork for foreign SURFACE structures: they cut the hill off a building so it insets cleanly
 * instead of poking through. Until v0.3.14 the only thing stopping them from doing that to an
 * UNDERGROUND structure was an ABSOLUTE test — {@code box.maxY() < SEA_LEVEL (63)}. In vanilla's
 * -64..320 world "below y=63" is a serviceable proxy for "underground". In an Archean Rise world,
 * whose land surface reaches y=768, it is meaningless: a cave ruin buried 40 blocks beneath a y=231
 * mountain has {@code box.maxY() = 199}, sails through the test, and gets excavated as if it were a
 * hillside cottage. Observed: {@code create_ltab:cave_ruins} at (-2756, 200, 3546), where the carve
 * hollowed y192..206 out from under the mountain and left a detached ~12x12x25 island overhead whose
 * horizontal cross-section was exactly the piece's bounding box.
 *
 * <p><b>The rule.</b> Depth is not an absolute Y — it is a distance below the LOCAL surface. So ask
 * the question the way vanilla asked it when it placed the structure:
 *
 * <pre>{@code   surface = getBaseHeight(startPieceBoxCentreX, startPieceBoxCentreZ, itsOwnHeightmap)
 *   burial  = surface - startPieceBox.maxY()
 *   BURIED  <=> burial >= insetForeignBurialMargin   (default 8)}</pre>
 *
 * <p><b>Why this cannot catch a healthy surface structure (the safety proof).</b> Vanilla places a
 * projected jigsaw in {@code JigsawPlacement.addPieces}:
 * {@code k = start_height + getFirstFreeHeight(boxCentreX, boxCentreZ, projectStartToHeightmap)}, then
 * moves the piece so that {@code box.minY() + groundLevelDelta == k}. ({@code getFirstFreeHeight} IS
 * {@code getBaseHeight} — ChunkGenerator.java:611-613.) Writing {@code s} = start_height,
 * {@code g} = groundLevelDelta, {@code h} = start-piece height:
 *
 * <pre>{@code   box.minY = surface + s - g
 *   box.maxY = surface + s - g + (h - 1)
 *   burial   = surface - box.maxY = -s + (g + 1 - h)}</pre>
 *
 * The terrain term cancels: <b>burial is a pure function of the structure's own definition, not of
 * the ground it lands on.</b> The ground plane always lies inside the piece, so {@code g < h}, hence
 * {@code burial <= -s}. Therefore a structure can only be classified BURIED if its author gave it a
 * negative {@code start_height} of at least the margin — i.e. only if the author deliberately sank it.
 * Terrain relief, cliffs, spires and slopes cannot push a surface structure across the threshold.
 * Measured against the full 94-structure census of this modpack: all 55 projected surface structures
 * declare {@code start_height: 0} (burial <= 0); the deepest shallow-inset dungeon declares -2
 * (burial <= 2); {@code create_ltab:cave_ruins} declares -40 (burial = 33, confirmed in-world:
 * 232 - 199 = 33). A margin of 8 sits 4x above the worst healthy case and 25 blocks below the defect.
 *
 * <p><b>Scope guards.</b> The proof above relies on vanilla's projection arithmetic, so the rule only
 * applies where that arithmetic actually ran:
 * <ul>
 *   <li><b>Exact {@link JigsawStructure} type</b> — subclasses (YUNG's API, Lithostitched) run their
 *       own placement and need not honour the identity. Mirrors {@link SiteGrading#isGradable}.</li>
 *   <li><b>{@code project_start_to_heightmap} must be PRESENT</b> — for an absolute-Y structure there is
 *       no projection column, burial becomes terrain-DEPENDENT, and the no-false-positive proof
 *       evaporates. Those keep the legacy behaviour.</li>
 *   <li><b>Measured with the structure's OWN heightmap type</b>, never a hardcoded WORLD_SURFACE_WG.
 *       {@code OCEAN_FLOOR} is not WG-equivalent to the others (it uses {@code blocksMotion()}, which
 *       water fails), so an ocean structure projected onto the seafloor — e.g.
 *       {@code create_ltab:water_pre} — would measure its water depth as "burial" and be
 *       misclassified. Using its own type keeps the identity exact.</li>
 *   <li><b>Start piece only</b>, never per-piece. Only the START piece's box centre carries the
 *       {@code groundPlane == surface + start_height} invariant. A child piece of a legitimate foreign
 *       town sitting on a hill flank can easily have natural surface far above its own roof — measuring
 *       there would skip the inset for exactly the case the inset exists to fix.</li>
 * </ul>
 *
 * <p><b>What it does NOT do.</b> It never moves a structure, never vetoes one, and never touches
 * placement. It only WITHDRAWS Archean Rise's earthwork, handing the structure back to vanilla's own
 * {@code terrain_adaptation} — which is what its author asked for. AR's prime directive (never override
 * a foreign mod's placement, DECISIONS.md) is preserved by construction.
 *
 * <p><b>Re-entrancy.</b> Safe to call from inside the Beardifier wrap: {@code getBaseHeight} routes
 * through {@code NoiseBasedChunkGenerator.iterateNoiseColumn}, which builds a private NoiseChunk with
 * {@code DensityFunctions.BeardifierMarker.INSTANCE} (a constant-0 marker) and never calls
 * {@code Beardifier.forStructuresInChunk}. Verified in the decompiled 1.21.1 source.
 *
 * <p><b>Determinism.</b> {@code getBaseHeight} is a pure function of (seed, x, z) — base noise only, no
 * structures, no carvers, no chunk order. The same start therefore classifies identically from every
 * chunk that touches it and from both the noise and features passes, so beard and grade stay the same
 * set (the invariant {@link ForeignInsetBeard} documents) and no chunk seam can form.
 */
public final class BuriedStructures {

	/** Blocks of rock the start piece's roof must sit below the projected surface to count as buried. */
	private static final int DEFAULT_MARGIN = 8;
	/** Bounded memo — worldgen is multithreaded, and a start is re-classified from every chunk it touches. */
	private static final int CACHE_CAP = 512;

	/** Keyed on the generator too: two dimensions in one JVM must never alias. */
	private record Key(ChunkGenerator generator, Heightmap.Types heightmap,
			int minX, int minZ, int maxX, int maxY, int maxZ) {}

	private static final Map<Key, Boolean> CACHE = new ConcurrentHashMap<>();
	/** Structure ids already reported, so the INFO line is one-shot rather than one-per-chunk. */
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	private BuriedStructures() {}

	public static boolean enabled() {
		return ArcheanRise.config == null || ArcheanRise.config.insetForeignBurialGate;
	}

	private static int margin() {
		return ArcheanRise.config == null ? DEFAULT_MARGIN : ArcheanRise.config.insetForeignBurialMargin;
	}

	/**
	 * True when this foreign start was deliberately BURIED by its author and must therefore be left to
	 * vanilla's own terrain adaptation instead of receiving Archean Rise's surface-inset earthwork.
	 * See the class javadoc for the safety proof. Never moves or vetoes anything.
	 */
	public static boolean isBuriedStart(StructureStart start, ChunkGenerator generator,
			RandomState randomState, LevelHeightAccessor heightAccessor, RegistryAccess registryAccess) {
		if (!enabled()) {
			return false; // kill-switch — restores pre-0.3.14 behaviour exactly
		}
		Structure structure = start.getStructure();
		ResourceLocation id = registryAccess.registryOrThrow(Registries.STRUCTURE).getKey(structure);

		// Operator overrides win over the measurement, in both directions.
		if (id != null && ArcheanRise.config != null) {
			if (matches(id, ArcheanRise.config.insetForeignForceSurfaceIds,
					ArcheanRise.config.insetForeignForceSurfaceNamespaces)) {
				return false;
			}
			if (matches(id, ArcheanRise.config.insetForeignForceBuriedIds,
					ArcheanRise.config.insetForeignForceBuriedNamespaces)) {
				return true;
			}
		}

		// Exact vanilla jigsaw only — the burial identity is vanilla's projection arithmetic inverted, and
		// a subclass (yungsapi, lithostitched) need not honour it.
		if (structure.getClass() != JigsawStructure.class) {
			return false;
		}
		// Not projected => no projection column => burial is terrain-dependent => the proof does not hold.
		Optional<Heightmap.Types> projection = ((JigsawStructureAccessor) (Object) structure)
				.archean_rise$getProjectStartToHeightmap();
		if (projection.isEmpty()) {
			return false;
		}
		List<StructurePiece> pieces = start.getPieces();
		if (pieces.isEmpty()
				|| !(pieces.get(0) instanceof PoolElementStructurePiece pool)
				|| pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
			return false; // only a RIGID start piece is projected to the heightmap
		}

		BoundingBox box = pieces.get(0).getBoundingBox();
		Heightmap.Types heightmap = projection.get();
		Key key = new Key(generator, heightmap, box.minX(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
		Boolean cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		// The SAME column vanilla projected from (JigsawPlacement.addPieces): the start piece's bbox centre,
		// with vanilla's exact integer arithmetic, sampled with the structure's own heightmap type.
		int centreX = (box.maxX() + box.minX()) / 2;
		int centreZ = (box.maxZ() + box.minZ()) / 2;
		int surface = generator.getBaseHeight(centreX, centreZ, heightmap, heightAccessor, randomState);
		int burial = surface - box.maxY();
		boolean buried = burial >= margin();

		if (CACHE.size() >= CACHE_CAP) {
			CACHE.clear(); // bounded; recomputation is a pure function, so clearing is always safe
		}
		CACHE.put(key, buried);

		if (buried && id != null && REPORTED.add(id.toString())) {
			ArcheanRise.LOGGER.info("Burial gate: '{}' is a BURIED structure (start piece roof {} blocks "
					+ "below the projected surface, margin {}) — leaving it to vanilla terrain adaptation "
					+ "instead of the foreign surface inset.", id, burial, margin());
		}
		return buried;
	}

	private static boolean matches(ResourceLocation id, Map<String, String> ids,
			Map<String, String> namespaces) {
		return ids.containsKey(id.toString()) || namespaces.containsKey(id.getNamespace());
	}

	/** Drop cached classifications — called when a server stops so generators are not retained. */
	public static void clear() {
		CACHE.clear();
		REPORTED.clear();
	}

	/**
	 * SERVER_STARTED report: resolve the two override lists against the live structure registry so a typo
	 * or a missing mod is called out instead of failing silently. Mirrors {@link SiteGrading#reportExtras}.
	 */
	public static void reportOverrides(net.minecraft.server.MinecraftServer server) {
		try {
			if (ArcheanRise.config == null) {
				return;
			}
			net.minecraft.core.Registry<Structure> registry =
					server.registryAccess().registryOrThrow(Registries.STRUCTURE);
			Set<String> namespacesSeen = new java.util.HashSet<>();
			for (var entry : registry.entrySet()) {
				namespacesSeen.add(entry.getKey().location().getNamespace());
			}
			warnUnresolved("insetForeignForceSurfaceStructures",
					ArcheanRise.config.insetForeignForceSurfaceIds,
					ArcheanRise.config.insetForeignForceSurfaceNamespaces, registry, namespacesSeen);
			warnUnresolved("insetForeignForceBuriedStructures",
					ArcheanRise.config.insetForeignForceBuriedIds,
					ArcheanRise.config.insetForeignForceBuriedNamespaces, registry, namespacesSeen);
		} catch (Exception e) {
			ArcheanRise.LOGGER.warn(
					"Burial gate override report failed (informational only; the gate itself is unaffected): {}",
					e.toString());
		}
	}

	private static void warnUnresolved(String key, Map<String, String> ids, Map<String, String> namespaces,
			net.minecraft.core.Registry<Structure> registry, Set<String> namespacesSeen) {
		for (var e : ids.entrySet()) {
			ResourceLocation loc = ResourceLocation.tryParse(e.getKey());
			if (loc == null || !registry.containsKey(loc)) {
				ArcheanRise.LOGGER.warn("Config {}: no structure registered as '{}' "
						+ "(typo, or its mod is not installed?)", key, e.getValue());
			}
		}
		for (var e : namespaces.entrySet()) {
			if (!namespacesSeen.contains(e.getKey())) {
				ArcheanRise.LOGGER.warn("Config {}: entry '{}' matched no registered structure namespace "
						+ "(typo, or the mod is not installed?)", key, e.getValue());
			}
		}
		if (!ids.isEmpty() || !namespaces.isEmpty()) {
			ArcheanRise.LOGGER.info("Burial gate override '{}': {} id(s), {} namespace(s) active.",
					key, ids.size(), namespaces.size());
		}
	}
}
