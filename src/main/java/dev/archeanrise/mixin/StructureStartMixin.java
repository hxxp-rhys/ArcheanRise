package dev.archeanrise.mixin;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.sitegrading.SiteGrading;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M4 — foundation-fill safety net: after an allowlisted structure places its blocks in this
 * chunk, close any residual gap between each RIGID piece's ground plane and real ground by
 * filling AIR columns downward (bounded depth, never through liquids, never outside the
 * current chunk, never replacing non-air — so other structures' blocks are untouchable).
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
	private static final int FILL_CAP = SiteGrading.GRADE_BUDGET + 4;

	/**
	 * SiteGrading v2 authoritative cut+fill pass (blueprint §3). Phase 2 builds the deterministic
	 * per-start {@link dev.archeanrise.sitegrading.TargetField} (logs hash / convergence / slope for
	 * the determinism gate); Phase 3 (this) then runs the spill-decoupled, foreign-yielding
	 * {@link dev.archeanrise.sitegrading.SiteCut CUT} over the current chunk's apron columns. FILL,
	 * resurface, and the flat-snap interlock are Phase 4 / 3c.
	 */
	@Inject(method = "placeInChunk", at = @At("TAIL"))
	private void archean_rise$cutFill(WorldGenLevel level, StructureManager structureManager,
			ChunkGenerator generator, RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos,
			CallbackInfo ci) {
		if (!SiteGrading.enabled()
				|| ArcheanRise.config == null || !ArcheanRise.config.siteGradingCutFill
				|| !SiteGrading.isArcheanGenerator(generator)) {
			return;
		}
		StructureStart start = (StructureStart) (Object) this;
		if (!SiteGrading.isGradable(start.getStructure(), level.registryAccess())) {
			return;
		}
		// Build (and cache + log) the deterministic per-start field, then GRADE this chunk's columns to
		// it — cut the hill down, fill the dips/water up, resurface. Writes only the current chunk; the
		// field is identical in every chunk touching the start, so the earthwork is seam-free.
		var field = dev.archeanrise.sitegrading.SiteGradeField.forStart(level, start);
		if (field == null) {
			return; // no rigid/pool pieces — nothing to grade
		}
		var graded = dev.archeanrise.sitegrading.SiteCut.grade(level, start, field, chunkPos);
		if (graded != null && graded.writes() > 0) {
			// DEBUG (per-chunk, potentially many during a cutFill pregen); the per-field INFO summary in
			// SiteGradeField is the one-per-village signal. Enable DEBUG on the Archean Rise logger to trace.
			ArcheanRise.LOGGER.debug("SiteGrade: chunk {} — {} cols graded ({} cut, {} filled), "
					+ "{} foreign col yielded ({} halos)",
					chunkPos, graded.gradedColumns(), graded.blocksCut(), graded.blocksFilled(),
					graded.foreignColumns(), graded.foreignHalos());
		}
	}

	@Inject(method = "placeInChunk", at = @At("TAIL"))
	private void archean_rise$foundationFill(WorldGenLevel level, StructureManager structureManager,
			ChunkGenerator generator, RandomSource random, BoundingBox chunkBox, ChunkPos chunkPos,
			CallbackInfo ci) {
		if (!SiteGrading.enabled()
				|| (ArcheanRise.config != null && !ArcheanRise.config.siteGradingFoundationFill)
				|| !SiteGrading.isArcheanGenerator(generator)) {
			return;
		}
		StructureStart start = (StructureStart) (Object) this;
		if (!SiteGrading.isGradable(start.getStructure(), level.registryAccess())) {
			return;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState dirt = Blocks.DIRT.defaultBlockState();
		for (StructurePiece piece : start.getPieces()) {
			if (!(piece instanceof PoolElementStructurePiece pool)
					|| pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
				continue;
			}
			BoundingBox box = piece.getBoundingBox();
			int plane = box.minY() + pool.getGroundLevelDelta();
			int minX = Math.max(box.minX(), chunkBox.minX());
			int maxX = Math.min(box.maxX(), chunkBox.maxX());
			int minZ = Math.max(box.minZ(), chunkBox.minZ());
			int maxZ = Math.min(box.maxZ(), chunkBox.maxZ());
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					for (int y = plane - 1; y >= plane - FILL_CAP; y--) {
						pos.set(x, y, z);
						BlockState state = level.getBlockState(pos);
						if (state.isAir()) {
							level.setBlock(pos, dirt, 2);
						} else {
							break; // solid ground or liquid: stop (never dam water)
						}
					}
				}
			}
		}
	}
}
