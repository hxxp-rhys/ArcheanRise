package dev.archeanrise.noise.df;

import com.mojang.serialization.MapCodec;
import dev.archeanrise.ArcheanRise;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.function.BiConsumer;

/**
 * Archean Rise's custom density-function types. These evaluate inside the vanilla noise generator,
 * so every compatibility property (structures, biome injection, Lithium/C2ME) is untouched — they
 * are just new vocabulary for the JSON router.
 *
 * <p>{@link #forEachType} is the single source of truth for the (id, codec) pairs so BOTH loaders
 * register the identical set: Fabric uses the eager {@link #register()} at mod-init (vanilla
 * registries are still writable there); NeoForge freezes the vanilla registries before mod
 * constructors return, so its entrypoint feeds these pairs into a {@code DeferredRegister} on the
 * mod event bus instead (a bare {@code Registry.register} at construct time throws "Registry is
 * already frozen"). Both realize the types long before any datapack density_function JSON is parsed.
 */
public final class ArcheanRiseDensityFunctions {
	private ArcheanRiseDensityFunctions() {}

	/** The canonical (path, codec) pairs. Path is under the {@code archean_rise} namespace.
	 * (The pre-pivot experimental types — ridge_fold, warped_noise, macro_field, box — were
	 * removed in the v0.3.x cleanup; only types the live pipeline references remain. Removing
	 * a type breaks any world whose datapack still references it — none of ours do, the demo
	 * JSONs went with them.) */
	public static void forEachType(BiConsumer<String, MapCodec<? extends DensityFunction>> sink) {
		sink.accept("voronoi", VoronoiField.DATA_CODEC);
		sink.accept("ridged_multifractal", RidgedMultifractal.DATA_CODEC);
		sink.accept("river_carve", RiverCarveDensityFunction.DATA_CODEC); // R2b channel carve (doc 14)
		sink.accept("river_water", RiverWaterDensityFunction.DATA_CODEC); // R2c water masks (doc 11 §5a)
	}

	/** Eager registration into the still-writable vanilla registry — the FABRIC init path only. */
	public static void register() {
		forEachType((path, codec) -> Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
				ResourceLocation.fromNamespaceAndPath(ArcheanRise.MOD_ID, path), codec));
	}
}
