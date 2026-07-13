package dev.archeanrise.mixin;

import dev.archeanrise.worldgen.ore.OreGate;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ore Phase-0 suppression seam: cancels the six diluted vanilla ore features inside Archean
 * generators (their re-anchored AR replacements inject via the biome APIs, scoped by
 * {@code archean_rise:in_archean_generator}). Passes through everywhere else — vanilla-preset
 * worlds and every other feature are untouched. See {@link OreGate}.
 *
 * <p>Target is the PRIVATE {@code placeWithContext} funnel: the decoration pipeline enters via
 * {@code placeWithBiomeCheck}, commands/mods via the public {@code place} — both delegate here.
 * (Measured the hard way: targeting the public {@code place} suppressed NOTHING during chunk
 * decoration, and the census read vanilla + replacement stacked at +28%.)
 */
@Mixin(PlacedFeature.class)
public abstract class PlacedFeatureMixin {

	@Inject(method = "placeWithContext(Lnet/minecraft/world/level/levelgen/placement/PlacementContext;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
			at = @At("HEAD"), cancellable = true)
	private void archean_rise$suppressDilutedOres(PlacementContext context, RandomSource random,
			BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (OreGate.shouldSuppress((PlacedFeature) (Object) this, context.generator(), context.getLevel())) {
			cir.setReturnValue(false);
		}
	}
}
