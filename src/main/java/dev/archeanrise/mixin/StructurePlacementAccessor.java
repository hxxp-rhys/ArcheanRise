package dev.archeanrise.mixin;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/**
 * Exposes {@link StructurePlacement}'s protected base fields so the Ss structure-spacing scaler
 * ({@link dev.archeanrise.worldgen.StructureSpacing}) can copy them verbatim into a rescaled
 * {@code RandomSpreadStructurePlacement}. Cross-loader (a mixin accessor works identically on Fabric
 * and NeoForge — no per-loader access widener / access transformer needed). Read-only.
 */
@Mixin(StructurePlacement.class)
public interface StructurePlacementAccessor {
	@Accessor("locateOffset")
	Vec3i archean_rise$locateOffset();

	@Accessor("frequencyReductionMethod")
	StructurePlacement.FrequencyReductionMethod archean_rise$frequencyReductionMethod();

	@Accessor("frequency")
	float archean_rise$frequency();

	@Accessor("salt")
	int archean_rise$salt();

	@Accessor("exclusionZone")
	Optional<StructurePlacement.ExclusionZone> archean_rise$exclusionZone();
}
