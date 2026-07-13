package dev.archeanrise.mixin;

import dev.archeanrise.rivers.RiverFalls;
import dev.archeanrise.rivers.RiverPools;
import dev.archeanrise.sitegrading.ForeignInsetGrade;
import dev.archeanrise.sitegrading.SiteGrading;
import dev.archeanrise.worldgen.FloatDespeckle;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The shared FEATURES-stage pre-pass for Archean Rise dimensions. {@code applyBiomeDecoration}
 * runs post-carver (all caves final) and BEFORE any structure/feature is placed in this chunk, so at
 * its HEAD the world holds pure terrain — the correct, seed-pure point to:
 * <ul>
 *   <li>{@link FloatDespeckle} — remove small detached terrain-floater artifacts (issue 5),</li>
 *   <li>the foreign-structure exterior grade (issues 2/3/4) — it shapes terrain around foreign
 *       structure boxes before those structures place into it,</li>
 *   <li>{@link dev.archeanrise.rivers.RiverPools} — spec §7.3 pool fill: fill the still-dry, contained
 *       stepped pools up to their reach surface where the R2c aquifer under-fills (R2b-4c), then</li>
 *   <li>{@link dev.archeanrise.rivers.RiverFalls} — spec §7.2 waterfalls/rapids: turn the river
 *       graph's flagged lips into rim-contained plunge pools + retained-source falling columns
 *       (post-carver AND post-aquifer, so it reshapes the finished channel; the falls run AFTER the
 *       pool fill so a filled upper pool feeds the drop).</li>
 * </ul>
 * Only in Archean Rise generators (the Nether/End/non-Archean fail {@link
 * SiteGrading#isArcheanGenerator}); each sub-pass has its own config gate.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorDecorationMixin {

	@Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
	private void archean_rise$featuresPrePass(WorldGenLevel level, ChunkAccess chunk,
			StructureManager structureManager, CallbackInfo ci) {
		if (!SiteGrading.isArcheanGenerator((ChunkGenerator) (Object) this)) {
			return;
		}
		if (FloatDespeckle.enabled()) {
			FloatDespeckle.run(level, chunk);
		}
		if (ForeignInsetGrade.enabled()) {
			ForeignInsetGrade.grade(level, chunk.getPos(), structureManager);
		}
		// spec §7.3 pool fill — fill the still-dry, contained stepped pools up to the reach surface where
		// the aquifer under-fills (R2b-4c). BEFORE the waterfalls, so a filled upper pool feeds the fall.
		if (RiverPools.enabled()) {
			RiverPools.run(level, chunk);
		}
		// spec §7.2 waterfalls & rapids — turn the river graph's lips into contained drops (doc 14 §1.6).
		// Post-carver + post-aquifer, so it reshapes the finished channel; Archean-only; vanilla-safe.
		if (RiverFalls.enabled()) {
			RiverFalls.run(level, chunk);
		}
	}
}
