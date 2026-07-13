package dev.archeanrise.worldgen.ore;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.sitegrading.SiteGrading;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Suppression half of the ore Phase-0 repair (docs/research/05 §3, DECISIONS 2026-07-09): the six
 * vanilla ore placed features whose {@code above_bottom(0)} anchors dilute on the 1024-block world
 * are REPLACED in Archean dimensions by AR-namespaced re-anchored copies. The copies inject
 * globally (scoped by {@link InArcheanGenerator}); this gate cancels the vanilla ORIGINALS — only
 * inside Archean generators, so vanilla-preset worlds are untouched (the StructureSnowGateMixin
 * scoping pattern).
 *
 * <p>The suppressed set is resolved LAZILY from the placement context's {@code registryAccess}
 * (identity set — the registry hands out singleton instances) and consumed by
 * {@code PlacedFeatureMixin} at the private {@code placeWithContext} funnel. The cache is KEYED on
 * the {@link RegistryAccess} identity it was resolved from: a server's composite registry access is
 * one stable instance for its whole lifetime, so when a different server opens in the same JVM
 * (singleplayer world B after world A) the key mismatch forces re-resolution even for spawn-chunk
 * decoration that runs BEFORE any lifecycle event — no stale cross-server set is ever consumed.
 * The lifecycle {@link #reset} calls (server started + stopping) are hygiene only: they drop the
 * reference so a closed server's registries can be garbage-collected.
 */
public final class OreGate {
	/** The six diluted vanilla features Phase 0 replaces — keep in sync with the generator's ORE_PHASE0 table. */
	private static final List<String> SUPPRESSED_IDS = List.of(
			"minecraft:ore_redstone", "minecraft:ore_tuff", "minecraft:ore_iron_small",
			"minecraft:ore_lapis_buried", "minecraft:ore_infested", "minecraft:ore_clay");

	/** The suppressed set plus the {@link RegistryAccess} it was resolved from (the cache key). */
	private record Resolved(RegistryAccess registryAccess, Set<PlacedFeature> set) {}

	private static volatile Resolved resolved; // null until first resolution

	private OreGate() {}

	/**
	 * Resolve the suppressed set from the live registries. Called LAZILY from the first placement
	 * (via {@link #shouldSuppress}) — NOT from a lifecycle event: spawn chunks decorate during
	 * server start, BEFORE SERVER_STARTED fires, and a lifecycle-resolved set would be empty for
	 * exactly those chunks (stacked vanilla+replacement ores around spawn). A concurrent first
	 * call from two worker threads is benign — both compute the identical set.
	 */
	private static Set<PlacedFeature> resolve(RegistryAccess registryAccess) {
		Set<PlacedFeature> set = Collections.newSetFromMap(new IdentityHashMap<>());
		Registry<PlacedFeature> registry = registryAccess.registryOrThrow(Registries.PLACED_FEATURE);
		for (String id : SUPPRESSED_IDS) {
			ResourceKey<PlacedFeature> key = ResourceKey.create(Registries.PLACED_FEATURE, ResourceLocation.parse(id));
			registry.getOptional(key).ifPresentOrElse(set::add,
					() -> ArcheanRise.LOGGER.warn("Ore gate: {} not found in the placed-feature registry "
							+ "(a datapack removed it?) — its AR replacement will still inject", id));
		}
		return set;
	}

	/**
	 * Drop the cached set. Correctness does not depend on this — {@link #shouldSuppress} re-resolves
	 * whenever the {@link RegistryAccess} identity changes — but clearing at server started/stopping
	 * releases the previous server's registries for GC.
	 */
	public static void reset(MinecraftServer server) {
		resolved = null;
	}

	/** True when {@code feature} is a suppressed vanilla ore AND the generator is Archean. */
	public static boolean shouldSuppress(PlacedFeature feature, ChunkGenerator generator,
			net.minecraft.world.level.WorldGenLevel level) {
		RegistryAccess access = level.registryAccess();
		Resolved r = resolved;
		if (r == null || r.registryAccess() != access) {
			r = new Resolved(access, resolve(access));
			resolved = r;
		}
		return r.set().contains(feature) && SiteGrading.isArcheanGenerator(generator);
	}
}
