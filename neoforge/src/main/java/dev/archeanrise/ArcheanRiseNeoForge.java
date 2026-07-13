package dev.archeanrise;

import com.mojang.serialization.MapCodec;
import dev.archeanrise.noise.df.ArcheanRiseDensityFunctions;
import dev.archeanrise.platform.NeoForgePlatform;
import dev.archeanrise.worldgen.ore.ArcheanRisePlacementModifiers;
import dev.archeanrise.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * NeoForge entrypoint. Installs the NeoForge {@link Platform}, runs the loader-neutral
 * {@link ArcheanRise#commonInit()}, DEFERS the custom density-function type registration (a bare
 * {@code Registry.register} at construct time throws "Registry is already frozen" on NeoForge), and
 * wires the game-bus events to the shared {@link ArcheanRise} handlers.
 */
@Mod(ArcheanRise.MOD_ID)
public final class ArcheanRiseNeoForge {

	/** The custom DF types, same DATA_CODEC values as the Fabric eager path (single source of truth). */
	private static final DeferredRegister<MapCodec<? extends DensityFunction>> DF_TYPES =
			DeferredRegister.create(BuiltInRegistries.DENSITY_FUNCTION_TYPE, ArcheanRise.MOD_ID);

	/** Custom placement-modifier types (ore Phase-0 scoping), same seam as the DF types. */
	private static final DeferredRegister<PlacementModifierType<?>> PLACEMENT_TYPES =
			DeferredRegister.create(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE, ArcheanRise.MOD_ID);

	static {
		ArcheanRiseDensityFunctions.forEachType((path, codec) -> DF_TYPES.register(path, () -> codec));
		ArcheanRisePlacementModifiers.forEachType((path, type) -> PLACEMENT_TYPES.register(path, () -> type));
	}

	public ArcheanRiseNeoForge(IEventBus modBus) {
		Platform.set(new NeoForgePlatform());
		ArcheanRise.commonInit();
		DF_TYPES.register(modBus); // realized during RegisterEvent, before any datapack/world load
		PLACEMENT_TYPES.register(modBus);

		IEventBus gameBus = NeoForge.EVENT_BUS;
		gameBus.addListener((RegisterCommandsEvent e) -> ArcheanRise.registerCommands(e.getDispatcher()));
		gameBus.addListener((ServerTickEvent.Post e) -> ArcheanRise.onServerTick(e.getServer()));
		gameBus.addListener((ServerAboutToStartEvent e) -> ArcheanRise.onServerStarting(e.getServer()));
		gameBus.addListener((ServerStartedEvent e) -> ArcheanRise.onServerStarted(e.getServer()));
		gameBus.addListener((ServerStoppingEvent e) -> ArcheanRise.onServerStopping(e.getServer()));
		gameBus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
			if (e.getEntity() instanceof ServerPlayer player) {
				ArcheanRise.onPlayerJoin(player, player.getServer());
			}
		});
	}
}
