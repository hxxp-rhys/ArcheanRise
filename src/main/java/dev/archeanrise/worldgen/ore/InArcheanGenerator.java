package dev.archeanrise.worldgen.ore;

import com.mojang.serialization.MapCodec;
import dev.archeanrise.sitegrading.SiteGrading;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

import java.util.stream.Stream;

/**
 * {@code archean_rise:in_archean_generator} — a placement modifier that emits ZERO positions
 * unless the generating dimension uses an Archean Rise generator. This is the scoping seam of
 * the ore Phase-0 repair (docs/research/05 §3): the replacement ore features are injected
 * GLOBALLY via the loaders' biome-modification APIs (which cannot scope by generator), and this
 * modifier confines their effect to Archean worlds — a vanilla-preset world with the mod
 * installed reproduced pure-vanilla ore rates in the acceptance census (64k-block ore counts
 * within 8 blocks of the no-mod control; see DECISIONS 2026-07-11).
 */
public final class InArcheanGenerator extends PlacementModifier {
	public static final InArcheanGenerator INSTANCE = new InArcheanGenerator();
	public static final MapCodec<InArcheanGenerator> CODEC = MapCodec.unit(INSTANCE);
	public static final PlacementModifierType<InArcheanGenerator> TYPE = () -> CODEC;

	private InArcheanGenerator() {}

	@Override
	public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
		return SiteGrading.isArcheanGenerator(context.generator()) ? Stream.of(pos) : Stream.empty();
	}

	@Override
	public PlacementModifierType<?> type() {
		return TYPE;
	}
}
