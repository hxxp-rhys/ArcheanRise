package dev.archeanrise.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.archeanrise.ArcheanRise;
import dev.archeanrise.duck.ArcheanGeneratorDuck;
import dev.archeanrise.sitegrading.GradePad;
import dev.archeanrise.sitegrading.SiteGrading;
import dev.archeanrise.worldgen.BiomeBorderBlend;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * M3 — GradePad: augment the beardifier with bounded ground-conforming density around
 * allowlisted structures, in Archean Rise dimensions only. GradePad builds its OWN piece
 * list (independent startsForStructure query — never touches the vanilla Beardifier's
 * iterators) and composes additively; the wrapped INVOKE's callers accept the
 * BeardifierOrMarker interface, so returning the composite is type-safe at the call site.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin implements ArcheanGeneratorDuck {

	/**
	 * Archean Rise's generator identity, decided ONCE at construction and never re-derived.
	 *
	 * <p>Vanilla assigns {@code this.settings} in the constructor. Mods that rebuild a generator's
	 * {@code NoiseGeneratorSettings} do it much later — Lithostitched does it at {@code ServerAboutToStart},
	 * writing back a KEYLESS {@code Holder.direct(...)} whose {@code unwrapKey()} is empty. Deriving our
	 * identity from that holder at CALL time therefore lost it, silently disabling every AR subsystem
	 * (see {@link ArcheanGeneratorDuck}). Capturing it HERE, before anyone can touch the field, cannot be
	 * taken away — by Lithostitched or by any future mod that swaps the holder.
	 */
	@Unique
	private boolean archean_rise$isArchean;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void archean_rise$captureIdentity(BiomeSource biomeSource,
			Holder<NoiseGeneratorSettings> settings, CallbackInfo ci) {
		this.archean_rise$isArchean = settings.unwrapKey()
				.map(key -> key.location().getNamespace().equals(ArcheanRise.MOD_ID))
				.orElse(false);
	}

	@Override
	public boolean archean_rise$isArcheanGenerator() {
		return this.archean_rise$isArchean;
	}

	@WrapOperation(method = "createNoiseChunk",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/levelgen/Beardifier;forStructuresInChunk(Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/levelgen/Beardifier;"))
	private Beardifier archean_rise$gradePad(StructureManager structureManager, ChunkPos chunkPos,
			Operation<Beardifier> original,
			@Local(argsOnly = true) ChunkAccess chunk,
			@Local(argsOnly = true) RandomState randomState) {
		Beardifier vanilla = original.call(structureManager, chunkPos);
		if (!SiteGrading.isArcheanGenerator((ChunkGenerator) (Object) this)) {
			return vanilla;
		}
		// Compose additively over vanilla: GradePad conforms terrain to gradable village pieces; the
		// foreign-inset beard carves a clean pocket for NON-gradable add-on surface structures. The two
		// piece sets are disjoint (isGradable vs !isGradable), so they never touch the same piece.
		//
		// generator + randomState + chunk are threaded through for the burial gate (BuriedStructures): it
		// samples getBaseHeight at the start piece's projection column to tell a buried structure from a
		// surface one. Both are real params of createNoiseChunk(ChunkAccess, StructureManager, Blender,
		// RandomState), and ChunkAccess IS a LevelHeightAccessor. Sampling here cannot recurse: getBaseHeight
		// routes through iterateNoiseColumn, which builds its own NoiseChunk with BeardifierMarker.INSTANCE
		// (constant 0) and never calls Beardifier.forStructuresInChunk.
		Beardifier wrapped = GradePad.wrapAsBeardifier(vanilla, structureManager, chunkPos);
		return dev.archeanrise.sitegrading.ForeignInsetBeard.wrap(wrapped, structureManager, chunkPos,
				(ChunkGenerator) (Object) this, randomState, chunk);
	}

	/**
	 * Biome-border blend (issue 5) — domain-warp the temperature+humidity climate sampler that writes
	 * this chunk's biomes, so borders finger/interlock instead of forming straight lines. Wraps the
	 * {@code cachedClimateSampler} used in {@code doCreateBiomes}; everything downstream (surface rules,
	 * features, mob spawns) reads these written biomes, so it all follows the warp coherently. Identity
	 * (off) unless config {@code biomeBorderBlend > 0} on an Archean Rise generator; terrain is
	 * untouched (only the two climate axes that do not feed terrain density are warped).
	 */
	@WrapOperation(method = "doCreateBiomes",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;cachedClimateSampler(Lnet/minecraft/world/level/levelgen/NoiseRouter;Ljava/util/List;)Lnet/minecraft/world/level/biome/Climate$Sampler;"))
	private Climate.Sampler archean_rise$blendBiomeBorders(NoiseChunk noiseChunk, NoiseRouter router,
			List<Climate.ParameterPoint> spawnTarget, Operation<Climate.Sampler> original,
			@Local(argsOnly = true) RandomState randomState) {
		Climate.Sampler sampler = original.call(noiseChunk, router, spawnTarget);
		int knob = BiomeBorderBlend.knob();
		if (knob <= 0 || !SiteGrading.isArcheanGenerator((ChunkGenerator) (Object) this)) {
			return sampler;
		}
		return BiomeBorderBlend.warp(sampler, randomState, knob);
	}
}
