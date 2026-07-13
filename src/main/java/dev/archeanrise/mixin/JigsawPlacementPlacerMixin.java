package dev.archeanrise.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.archeanrise.sitegrading.DesignSurface;
import dev.archeanrise.sitegrading.DesignSurfaceHolder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * M2b — clamp every CHILD-piece heightmap projection (streets, houses) inside the Placer
 * BFS to the DesignSurface armed by the decorated GenerationStub (M1). Wraps both
 * getFirstFreeHeight call sites in tryPlacingChildren; passes through when unarmed.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class JigsawPlacementPlacerMixin {

	@WrapOperation(method = "tryPlacingChildren",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getFirstFreeHeight(IILnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;)I"))
	private int archean_rise$clampChildProjection(ChunkGenerator generator, int x, int z,
			Heightmap.Types type, LevelHeightAccessor height, RandomState randomState,
			Operation<Integer> original) {
		int vanilla = original.call(generator, x, z, type, height, randomState);
		DesignSurface surface = DesignSurfaceHolder.get();
		if (surface == null || type != Heightmap.Types.WORLD_SURFACE_WG) {
			return vanilla;
		}
		return surface.clampProjection(x, z, vanilla);
	}
}
