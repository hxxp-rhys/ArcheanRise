package dev.archeanrise.worldgen.ore;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.function.BiConsumer;

/**
 * Custom placement-modifier types, mirroring {@link dev.archeanrise.noise.df.ArcheanRiseDensityFunctions}'
 * dual-loader seam: {@link #forEachType} is the single source of truth; Fabric registers eagerly
 * at mod-init, NeoForge feeds the pairs into a {@code DeferredRegister} on the mod event bus.
 */
public final class ArcheanRisePlacementModifiers {
	private ArcheanRisePlacementModifiers() {}

	public static void forEachType(BiConsumer<String, PlacementModifierType<?>> sink) {
		sink.accept("in_archean_generator", InArcheanGenerator.TYPE);
	}

	/** Eager registration into the still-writable vanilla registry — the FABRIC init path only. */
	public static void register() {
		forEachType((path, type) -> Registry.register(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE,
				ResourceLocation.fromNamespaceAndPath(ArcheanRise.MOD_ID, path), type));
	}
}
