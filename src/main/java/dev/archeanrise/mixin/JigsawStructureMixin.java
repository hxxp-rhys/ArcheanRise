package dev.archeanrise.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.archeanrise.ArcheanRise;
import dev.archeanrise.sitegrading.DesignSurfaceHolder;
import dev.archeanrise.sitegrading.SiteGrading;
import dev.archeanrise.sitegrading.SitePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * M1 — SiteGrading steps 1+2: site check + deterministic candidate search, and the
 * DesignSurface hand-off. The returned GenerationStub's pieces-builder consumer is DEFERRED
 * in 1.21.1 (runs after this method returns), so the stub is re-wrapped to arm the
 * DesignSurfaceHolder for the Placer BFS (verifier D1 fix). Consumes no RNG.
 */
@Mixin(JigsawStructure.class)
public abstract class JigsawStructureMixin {

	@WrapOperation(method = "findGenerationPoint",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/levelgen/structure/pools/JigsawPlacement;addPieces(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;Lnet/minecraft/core/Holder;Ljava/util/Optional;ILnet/minecraft/core/BlockPos;ZLjava/util/Optional;ILnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;Lnet/minecraft/world/level/levelgen/structure/pools/DimensionPadding;Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)Ljava/util/Optional;"))
	private Optional<Structure.GenerationStub> archean_rise$siteGrade(
			Structure.GenerationContext context, Holder<StructureTemplatePool> startPool,
			Optional<ResourceLocation> startJigsawName, int maxDepth, BlockPos pos,
			boolean useExpansionHack, Optional<Heightmap.Types> projectStartToHeightmap,
			int maxDistanceFromCenter, PoolAliasLookup aliasLookup, DimensionPadding padding,
			LiquidSettings liquidSettings,
			Operation<Optional<Structure.GenerationStub>> original) {

		if (!SiteGrading.enabled()
				|| !SiteGrading.isArcheanGenerator(context.chunkGenerator())
				|| !SiteGrading.isGradable((Structure) (Object) this, context.registryAccess())) {
			return original.call(context, startPool, startJigsawName, maxDepth, pos,
					useExpansionHack, projectStartToHeightmap, maxDistanceFromCenter,
					aliasLookup, padding, liquidSettings);
		}

		boolean search = ArcheanRise.config == null || ArcheanRise.config.siteGradingCandidateSearch;
		int budget = search ? SiteGrading.shiftBudget(maxDistanceFromCenter) : 0;
		SitePlanner.Plan plan = SitePlanner.plan(context, pos, budget, maxDistanceFromCenter);
		net.minecraft.resources.ResourceLocation id =
				context.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
						.getKey((Structure) (Object) this);
		// Phase-1 VETO: reject an un-gradeable site rather than place a village on broken terrain.
		// Returning empty() from findGenerationPoint means "no structure here" — vanilla simply
		// doesn't place it at this spread position (the proven IVP pattern). Deterministic.
		if (plan.veto()) {
			// veto() is only true when config != null (SiteGrading.vetoes short-circuits on null)
			ArcheanRise.LOGGER.info("SiteGrading: {} @ {} VETOED (relief={} > {} | roughness={} > {} | "
					+ "waterDepth={} > {}) — site un-gradeable, not placed", id, pos.toShortString(),
					plan.relief(), ArcheanRise.config.siteGradingVetoMaxRelief,
					plan.roughness(), SiteGrading.VETO_ROUGHNESS_MAX,
					plan.waterDepth(), ArcheanRise.config.siteGradingWaterVetoDepth);
			return Optional.empty();
		}
		ArcheanRise.LOGGER.info("SiteGrading: {} @ {} -> {} (fit={}, gradeable={}, anchor={}, relief={}, roughness={}, water={})",
				id, pos.toShortString(), plan.anchorPos().toShortString(), plan.fit(), plan.gradeable(),
				plan.surface().anchor(), plan.relief(), plan.roughness(), plan.waterDepth());

		Optional<Structure.GenerationStub> result;
		DesignSurfaceHolder.set(plan.surface());
		try {
			// covers the start-piece heightmap projection inside addPieces itself
			result = original.call(context, startPool, startJigsawName, maxDepth,
					plan.anchorPos(), useExpansionHack, projectStartToHeightmap,
					maxDistanceFromCenter, aliasLookup, padding, liquidSettings);
		} finally {
			DesignSurfaceHolder.clear();
		}

		// re-arm around the deferred Placer BFS (runs later via Structure.generate);
		// the Either's right branch (pre-built pieces) has no BFS to clamp
		final SitePlanner.Plan chosenPlan = plan;
		return result.map(stub -> new Structure.GenerationStub(stub.position(),
				stub.generator().mapLeft(consumer ->
						(java.util.function.Consumer<net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder>) builder -> {
							DesignSurfaceHolder.set(chosenPlan.surface());
							try {
								consumer.accept(builder);
							} finally {
								DesignSurfaceHolder.clear();
							}
						})));
	}
}
