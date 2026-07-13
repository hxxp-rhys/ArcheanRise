package dev.archeanrise.mixin;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.sitegrading.SiteGrading;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Water gate — keep add-on (foreign) SURFACE structures OFF open water so they only spawn on land (the
 * water analogue of {@link StructureSnowGateMixin}). Archean Rise's static terrain decouples biome from
 * elevation (a land biome can sit below sea level) and carves rivers, so a foreign structure placed by
 * its own biome/heightmap rules can land floating on / sunk in water. SiteGrading's water veto
 * ({@code SiteGrading.vetoes}) only protects the vanilla village/outpost allowlist — foreign
 * ({@code !isGradable}) structures bypass it entirely — so this is the only water guard they get.
 *
 * <p>It vetoes an eligible foreign surface structure whose FOOTPRINT sits mostly over water, by returning
 * {@code StructureStart.INVALID_START} from the universal {@code Structure.generate} seam — the same "no
 * structure here" result vanilla itself uses on a failed placement (determinism-neutral: the per-structure
 * placement RNG is isolated, so discarding a built start perturbs no neighbour).
 *
 * <p><b>Scope (broader than the snow gate's BEARD_THIN-only set, made safe by the biome guard):</b>
 * every {@code !isGradable} structure whose bounding box reaches sea level ({@code box.maxY >=}
 * {@link SiteGrading#SEA_LEVEL} — a surface structure, not a mineshaft/stronghold/ocean-monument below
 * it), EXCEPT an INTENTIONALLY-AQUATIC one — a structure in an ocean / deep-ocean / river biome (ocean
 * villages, drifting ships, ocean monuments, ocean ruins) is never gated. {@code !isGradable} means every
 * surface structure EXCEPT the vanilla village/outpost allowlist, so this covers add-on mod structures AND
 * vanilla NON-village surface structures (desert pyramid, jungle temple, igloo, mansion, land ruined
 * portals) alike — each is equally broken floating on water, and it exactly mirrors the snow gate's set.
 * Vanilla villages/outposts ({@code isGradable}, already site-vetoed by SitePlanner) are untouched. Config
 * kill-switch {@code gateForeignInWater}; depth threshold {@code gateForeignInWaterDepth} (keeps
 * beach/swamp/shallow-shore placements).
 */
@Mixin(Structure.class)
public abstract class StructureWaterGateMixin {

	/** Fixed N×N footprint sample grid (size-independent, bounded cost — foreign starts are sparse). */
	private static final int SAMPLES = 5;

	@Inject(method = "generate", at = @At("RETURN"), cancellable = true)
	private void archean_rise$waterGate(RegistryAccess registryAccess, ChunkGenerator chunkGenerator,
			BiomeSource biomeSource, RandomState randomState, StructureTemplateManager templateManager,
			long seed, ChunkPos chunkPos, int references, LevelHeightAccessor heightAccessor,
			Predicate<Holder<Biome>> validBiome, CallbackInfoReturnable<StructureStart> cir) {
		if (ArcheanRise.config == null || !ArcheanRise.config.gateForeignInWater) {
			return;
		}
		StructureStart start = cir.getReturnValue();
		if (start == null || !start.isValid() || !SiteGrading.isArcheanGenerator(chunkGenerator)) {
			return;
		}
		Structure self = (Structure) (Object) this;
		// Foreign only — vanilla villages/outposts (isGradable) already get SitePlanner's water veto.
		if (SiteGrading.isGradable(self, registryAccess)) {
			return;
		}
		BoundingBox box = start.getBoundingBox();
		// Surface structures only: a box entirely below sea level is underground (mineshaft/stronghold) or
		// an intentional underwater build (ocean monument/shipwreck) — never a "floating on water" case.
		if (box.maxY() < SiteGrading.SEA_LEVEL) {
			return;
		}
		int cx = (box.minX() + box.maxX()) / 2;
		int cz = (box.minZ() + box.maxZ()) / 2;
		// Biome guard: an intentionally-aquatic structure (placed by its mod in an ocean/river biome)
		// belongs on water — never gate it. The land-biome-over-water case is what we target.
		Holder<Biome> biome = biomeSource.getNoiseBiome(QuartPos.fromBlock(cx),
				QuartPos.fromBlock(box.minY()), QuartPos.fromBlock(cz), randomState.sampler());
		if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)
				|| biome.is(BiomeTags.IS_RIVER)) {
			return;
		}
		if (!footprintMostlyOverWater(chunkGenerator, randomState, heightAccessor, box,
				ArcheanRise.config.gateForeignInWaterDepth)) {
			return;
		}
		ArcheanRise.LOGGER.info("Water gate: {} @ [{},{}..{},{}] VETOED — foreign surface structure sits "
				+ "over water (depth threshold {} below sea level {}), not placed",
				registryAccess.registryOrThrow(Registries.STRUCTURE).getKey(self),
				box.minX(), box.minZ(), box.maxX(), box.maxZ(),
				ArcheanRise.config.gateForeignInWaterDepth, SiteGrading.SEA_LEVEL);
		cir.setReturnValue(StructureStart.INVALID_START); // over-water site — do not place this foreign structure
	}

	/**
	 * True when at least half of an evenly-sampled {@link #SAMPLES}×{@link #SAMPLES} grid over the
	 * structure's footprint has its solid floor deeper than {@code depthThreshold} below sea level —
	 * i.e. the structure predominantly sits on a water body. Uses {@code getBaseHeight(OCEAN_FLOOR_WG)}
	 * (the top solid, non-fluid block from the real noise column, seed-deterministic), so a land biome
	 * pushed below sea level or a noise-carved river valley reads as water; a coast/beach/swamp whose
	 * floor is within {@code depthThreshold} of sea level does not. Pure function of (seed, position).
	 */
	private static boolean footprintMostlyOverWater(ChunkGenerator generator, RandomState randomState,
			LevelHeightAccessor heightAccessor, BoundingBox box, int depthThreshold) {
		int spanX = box.maxX() - box.minX();
		int spanZ = box.maxZ() - box.minZ();
		int water = 0;
		int total = 0;
		for (int i = 0; i < SAMPLES; i++) {
			int x = box.minX() + spanX * i / (SAMPLES - 1);
			for (int j = 0; j < SAMPLES; j++) {
				int z = box.minZ() + spanZ * j / (SAMPLES - 1);
				int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
						heightAccessor, randomState);
				total++;
				if (SiteGrading.SEA_LEVEL - floor > depthThreshold) {
					water++;
				}
			}
		}
		return total > 0 && water * 2 >= total;
	}
}
