package dev.archeanrise.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Lets the Ss structure-spacing scaler replace a {@link ChunkGeneratorStructureState}'s
 * {@code possibleStructureSets} with rescaled copies immediately after it is created (before any
 * structure positions are generated). The field is {@code private final}; {@link Mutable} permits the
 * setter to reassign it. The list type is {@code List<Holder<StructureSet>>}, so {@code Holder.direct}
 * copies (which are Holders, not registry References) are assignable.
 */
@Mixin(ChunkGeneratorStructureState.class)
public interface ChunkGeneratorStructureStateAccessor {
	@Accessor("possibleStructureSets")
	@Mutable
	void archean_rise$setPossibleStructureSets(List<Holder<StructureSet>> sets);
}
