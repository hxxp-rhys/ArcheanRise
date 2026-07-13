package dev.archeanrise;

import dev.archeanrise.noise.df.ArcheanRiseDensityFunctions;
import dev.archeanrise.platform.FabricPlatform;
import dev.archeanrise.platform.Platform;
import dev.archeanrise.worldgen.ore.ArcheanRisePlacementModifiers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.GenerationStep;

import java.util.List;
import java.util.function.Predicate;

/**
 * Fabric entrypoint (declared in fabric.mod.json). Installs the Fabric {@link Platform}, runs the
 * loader-neutral {@link ArcheanRise#commonInit()}, does the EAGER density-function registration
 * (Fabric leaves the vanilla registries writable at mod-init), and wires Fabric's command / tick /
 * lifecycle / join events to the shared {@link ArcheanRise} handlers.
 *
 * <p>This file is EXCLUDED from the {@code :neoforge} build (which supplies its own entrypoint).
 */
public final class ArcheanRiseFabric implements ModInitializer {

	/**
	 * Ore Phase-0 replacement features injected OVERWORLD-WIDE — biome-distribution parity with
	 * vanilla, whose {@code addDefaultOres}/{@code addDefaultUndergroundVariety} put these four in
	 * every overworld biome. Keep in sync with the generator's ORE_PHASE0 table
	 * (tools/generate-worldgen.mjs §5b, which emits the NeoForge biome-modifier mirror of this
	 * split) and OreGate.SUPPRESSED_IDS.
	 */
	private static final String[] ORE_PHASE0_GLOBAL = {
			"ore_redstone", "ore_tuff", "ore_iron_small", "ore_lapis_buried"};

	/**
	 * Vanilla places {@code ore_infested} ONLY via {@code BiomeDefaultFeatures.addInfestedStone},
	 * called from exactly these ten mountain-family biomes (DECISIONS 2026-07-11: derived from the
	 * 1.21.1 jar's shipped {@code data/minecraft/worldgen/biome/*.json} and cross-checked against
	 * the disassembled {@code OverworldBiomes} call sites — NOT windswept_savanna). The replacement
	 * must match exactly, or the world-wide silverfish rate multiplies by the biome count (~13x).
	 * Explicit keys, not a tag, for exact parity with no tag-drift.
	 */
	private static final List<ResourceKey<Biome>> INFESTED_BIOMES = List.of(
			Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_GRAVELLY_HILLS, Biomes.WINDSWEPT_FOREST,
			Biomes.MEADOW, Biomes.CHERRY_GROVE, Biomes.GROVE, Biomes.SNOWY_SLOPES,
			Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS, Biomes.STONY_PEAKS);

	@Override
	public void onInitialize() {
		Platform.set(new FabricPlatform());
		ArcheanRise.commonInit();
		ArcheanRiseDensityFunctions.register(); // eager — valid on Fabric at mod-init
		ArcheanRisePlacementModifiers.register();

		// Ore Phase-0 injection at vanilla's biome distribution (DECISIONS 2026-07-11): four ores
		// overworld-wide; ore_infested only in the mountain family (addInfestedStone); ore_clay
		// only in lush_caves (addLushCavesSpecialOres). Each feature also carries the
		// archean_rise:in_archean_generator modifier so it emits nothing outside Archean worlds.
		for (String name : ORE_PHASE0_GLOBAL) {
			addOre(BiomeSelectors.foundInOverworld(), name);
		}
		addOre(BiomeSelectors.includeByKey(INFESTED_BIOMES), "ore_infested");
		addOre(BiomeSelectors.includeByKey(Biomes.LUSH_CAVES), "ore_clay");

		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> ArcheanRise.registerCommands(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(ArcheanRise::onServerTick);
		ServerLifecycleEvents.SERVER_STARTING.register(ArcheanRise::onServerStarting);
		ServerLifecycleEvents.SERVER_STARTED.register(ArcheanRise::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(ArcheanRise::onServerStopping);
		ServerPlayConnectionEvents.JOIN.register(
				(handler, sender, server) -> ArcheanRise.onPlayerJoin(handler.getPlayer(), server));
	}

	private static void addOre(Predicate<BiomeSelectionContext> selector, String name) {
		BiomeModifications.addFeature(selector, GenerationStep.Decoration.UNDERGROUND_ORES,
				ResourceKey.create(Registries.PLACED_FEATURE,
						ResourceLocation.fromNamespaceAndPath(ArcheanRise.MOD_ID, name)));
	}
}
