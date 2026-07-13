package dev.archeanrise.mixin;

import dev.archeanrise.worldgen.StructureSpacing;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ss structure-spacing scaler — the per-generator seam. {@code ChunkGenerator.createState} builds the
 * per-dimension {@link ChunkGeneratorStructureState} once at world load; on the Archean Rise
 * static-world generator this rescales every {@code random_spread} structure set's spacing/separation
 * by Ss (3.0×) so ALL structures — vanilla AND add-on mods — spread farther apart in the enlarged
 * world. Runs at RETURN, before any positions are generated, replacing the state's
 * {@code possibleStructureSets} with rescaled {@code Holder.direct} copies (see {@link StructureSpacing}).
 *
 * <p>Per-dimension by construction: the Nether/End (and any non-Archean) generator fails the
 * {@link StructureSpacing#appliesTo} gate and is untouched, so {@code /locate}, explorer maps and
 * villager trades stay consistent because they all query this same per-generator state.
 * Concentric-rings (strongholds), frequency-driven sets (mineshafts/buried treasure) and any mod's
 * RandomSpread SUBCLASS are passed through unchanged.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

	@Inject(method = "createState", at = @At("RETURN"))
	private void archean_rise$scaleStructureSpacing(HolderLookup<StructureSet> lookup, RandomState randomState,
			long seed, CallbackInfoReturnable<ChunkGeneratorStructureState> cir) {
		if (!StructureSpacing.enabled()) {
			return;
		}
		if (!StructureSpacing.appliesTo((ChunkGenerator) (Object) this)) {
			return; // not the Archean Rise static-world generator (Nether/End/other mods)
		}
		StructureSpacing.rescale(cir.getReturnValue());
	}
}
