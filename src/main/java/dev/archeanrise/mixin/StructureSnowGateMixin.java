package dev.archeanrise.mixin;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.sitegrading.BuriedStructures;
import dev.archeanrise.sitegrading.SiteGrading;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Issue 1 — snowy-biome gate. The user chose to PREVENT (rather than snow-cover) non-snowy add-on
 * structures in snow-covered areas: snowing a mod's bare walls/sloped roofs means writing on another
 * mod's blocks and looks wrong on complex Create builds, and the ground is already snowed by vanilla —
 * so the residual mismatch is the structure's own body. This vetoes an eligible foreign surface
 * structure (the same {@code BEARD_THIN} + {@code !isGradable} set the inset earthwork treats) when the
 * terrain where it would sit is snow-covered ({@code getPrecipitationAt == SNOW} — exactly "the
 * surrounding terrain is snow-covered"), by returning {@code StructureStart.INVALID_START} from the
 * universal {@code Structure.generate} seam (the same "no structure here" result vanilla uses on a
 * failed placement — determinism-neutral; per-structure RNG is isolated).
 *
 * <p>Blunt by design: it cannot tell a snow-appropriate foreign build from a generic one, so it gates
 * ALL eligible foreign surface structures out of snow-covered spots (config kill-switch
 * {@code gateForeignInSnow}). Vanilla villages/outposts ({@code isGradable}) and non-surface structures
 * are untouched.
 */
@Mixin(Structure.class)
public abstract class StructureSnowGateMixin {

	@Inject(method = "generate", at = @At("RETURN"), cancellable = true)
	private void archean_rise$snowGate(RegistryAccess registryAccess, ChunkGenerator chunkGenerator,
			BiomeSource biomeSource, RandomState randomState, StructureTemplateManager templateManager,
			long seed, ChunkPos chunkPos, int references, LevelHeightAccessor heightAccessor,
			Predicate<Holder<Biome>> validBiome, CallbackInfoReturnable<StructureStart> cir) {
		if (ArcheanRise.config == null || !ArcheanRise.config.gateForeignInSnow) {
			return;
		}
		StructureStart start = cir.getReturnValue();
		if (start == null || !start.isValid() || !SiteGrading.isArcheanGenerator(chunkGenerator)) {
			return;
		}
		Structure self = (Structure) (Object) this;
		// Only the foreign surface set the inset earthwork treats — leave vanilla villages, underground,
		// bury/encapsulate/none structures alone.
		if (SiteGrading.isGradable(self, registryAccess)
				|| self.terrainAdaptation() != TerrainAdjustment.BEARD_THIN) {
			return;
		}
		// UNDERGROUND PREFILTER (v0.3.19) — a box whose roof is under sea level is not a "snow-covered site".
		// This gate had NO depth test of any kind, so an absolute-Y buried ruin (philipsruins:lost_soul_city
		// sits at absolute y −40) was judged on the precipitation at its own base and deleted if the region
		// was snowy. The two guards below are COMPLEMENTARY and both are needed: this one covers absolute-Y
		// structures (their boxes are under sea level in any world), the burial gate covers PROJECTED ones
		// (whose absolute Y can be anywhere — create_ltab's roof was at y 199, under a mountain). Together
		// they cover both halves; either alone leaves a hole. Same pairing as ForeignInsetBeard:105.
		if (start.getBoundingBox().maxY() < SiteGrading.SEA_LEVEL) {
			return;
		}

		// BURIAL GATE (v0.3.19) — never veto a structure its author deliberately SANK.
		//
		// This gate had NO depth test whatsoever. A beard_thin cave ruin buried forty blocks under a snowy
		// peak reads the biome at its own base, finds SNOW — the snow on the mountaintop far above it, which
		// it will never see — and is DELETED. The gate exists because a generic add-on build looks wrong
		// standing on snow; a structure entombed in rock is not standing on anything.
		//
		// Depth is a distance below the LOCAL surface, not an absolute Y (the same lesson as create_ltab:
		// in a world whose land reaches y 768, "below sea level" means nothing). Ask exactly the question
		// ForeignInsetBeard and ForeignInsetGrade already ask, via the one shared predicate — a duplicated
		// predicate is one that only ever gets fixed once.
		//
		// This is a WORLD-CONTENT change: structures that were being silently deleted now generate.
		if (BuriedStructures.isBuriedStart(start, chunkGenerator, randomState, heightAccessor,
				registryAccess)) {
			return;
		}

		BoundingBox box = start.getBoundingBox();
		int cx = (box.minX() + box.maxX()) / 2;
		int cz = (box.minZ() + box.maxZ()) / 2;
		int y = box.minY(); // the structure's base ~= the ground surface it sits on
		BlockPos at = new BlockPos(cx, y, cz);
		Holder<Biome> biome = biomeSource.getNoiseBiome(
				QuartPos.fromBlock(cx), QuartPos.fromBlock(y), QuartPos.fromBlock(cz), randomState.sampler());
		if (biome.value().getPrecipitationAt(at) == Biome.Precipitation.SNOW) {
			cir.setReturnValue(StructureStart.INVALID_START); // snow-covered site — do not place this foreign structure
		}
	}
}
