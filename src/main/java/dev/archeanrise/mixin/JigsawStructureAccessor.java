package dev.archeanrise.mixin;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(JigsawStructure.class)
public interface JigsawStructureAccessor {
	@Accessor("projectStartToHeightmap")
	Optional<Heightmap.Types> archean_rise$getProjectStartToHeightmap();

	@Accessor("maxDistanceFromCenter")
	int archean_rise$getMaxDistanceFromCenter();
}
