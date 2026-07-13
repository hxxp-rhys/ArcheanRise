package dev.archeanrise.mixin;

import java.util.function.Function;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.archeanrise.rivers.RiverCarveProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

/**
 * spec §7.3 CARVER_SHIELD (R2c; doc 11 §5a, the R2c scratchpad plan) — cave/canyon carvers must not
 * cut air/lava within {@link RiverCarveProvider#CARVER_SHIELD} blocks of any river/lake bed, bank, or
 * pool below its water surface, or a breached channel wall would let the R2c aquifer water drain out.
 *
 * <p><b>Seam (chosen by reading the decompiled 1.21.1 pipeline, not assumed).</b> The per-block cut
 * happens in the ABSTRACT base {@code WorldCarver.carveBlock} (protected, non-final); {@code CaveWorldCarver}
 * and {@code CanyonWorldCarver} INHERIT it (they do not override), so one HEAD inject here covers both —
 * exactly the carvers overworld rivers meet. {@code NetherWorldCarver} overrides {@code carveBlock}, so
 * the Nether is untouched (no rivers there anyway). Param #6 ({@code pos}) is set in {@code carveEllipsoid}
 * to ABSOLUTE world coordinates ({@code chunkPos.getBlockX(u)}, world Y, {@code chunkPos.getBlockZ(x)}), so
 * the river-proximity query needs no chunk-origin math. Returning {@code false} skips the
 * {@code chunkAccess.setBlockState(...)} cut cleanly (the {@code CarvingMask} bit was already set upstream,
 * but it only records intent — no block is placed), leaving the wall intact.
 *
 * <p><b>Mixin rules.</b> HEAD {@code @Inject(cancellable = true)}, never {@code @Overwrite}; the query is a
 * pure, thread-safe read of the shared {@link RiverCarveProvider} (C2ME carves off-thread) using the SAME
 * per-cell carver the {@code river_carve}/{@code river_water} DFs use. {@link RiverCarveProvider#current()}
 * is {@code null} — a byte-exact no-op — whenever there is no live Archean overworld (vanilla-preset worlds,
 * other dimensions, non-Archean generators), so vanilla carving is untouched there.
 */
@Mixin(WorldCarver.class)
public abstract class WorldCarverMixin {

	@Inject(method = "carveBlock", at = @At("HEAD"), cancellable = true)
	private void archean_rise$riverShield(CarvingContext carvingContext, CarverConfiguration config,
			ChunkAccess chunk, Function<BlockPos, Holder<Biome>> biome, CarvingMask mask,
			BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos posDown, Aquifer aquifer,
			MutableBoolean reachedSurface, CallbackInfoReturnable<Boolean> cir) {
		RiverCarveProvider provider = RiverCarveProvider.current();
		if (provider == null) {
			return; // no live Archean overworld to bind to — vanilla carving untouched (vanilla safety)
		}
		if (provider.isShielded(pos.getX(), pos.getY(), pos.getZ())) {
			cir.setReturnValue(false); // veto: do not cut within the shield of a wetted river/lake wall
		}
	}
}
