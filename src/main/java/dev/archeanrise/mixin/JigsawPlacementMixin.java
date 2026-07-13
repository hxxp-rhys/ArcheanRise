package dev.archeanrise.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.archeanrise.sitegrading.DesignSurface;
import dev.archeanrise.sitegrading.DesignSurfaceHolder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * M2a — clamp the START-piece heightmap projection to the DesignSurface. Passes through
 * untouched whenever no surface is armed (other dims, /place jigsaw, other structures) or
 * the query isn't WORLD_SURFACE_WG.
 */
@Mixin(JigsawPlacement.class)
public abstract class JigsawPlacementMixin {

	@WrapOperation(method = "addPieces",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;getFirstFreeHeight(IILnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;)I"))
	private static int archean_rise$clampStartProjection(ChunkGenerator generator, int x, int z,
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
