package dev.archeanrise.audit;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Golden-seed regression hashing: a deterministic FNV-1a hash over the block states of the
 * chunks in a square radius around the origin. Any unintended change to generation for a fixed
 * seed changes this hash — record the baseline per (seed, mod version) in docs/TESTING.md and
 * re-run after every worldgen change. Hash inputs are registry *names* (not raw ids), so the
 * hash is stable across registry reordering caused by adding/removing unrelated mods.
 */
public final class WorldHashAudit {
	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;

	private WorldHashAudit() {}

	public static String hash(ServerLevel level, int radiusChunks) {
		return hash(level, 0, 0, radiusChunks);
	}

	/** Center the hash away from spawn to hash freshly GENERATED (never-ticked) chunks —
	 *  spawn chunks mutate under live ticking (grass/snow/fluids), which is not a worldgen
	 *  determinism signal. */
	public static String hash(ServerLevel level, int centerChunkX, int centerChunkZ, int radiusChunks) {
		Registry<Block> blocks = level.registryAccess().registryOrThrow(Registries.BLOCK);
		long total = FNV_OFFSET;
		int minY = level.getMinBuildHeight();
		int maxY = level.getMaxBuildHeight();
		StringBuilder perChunk = new StringBuilder();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// Pre-generate a 2-chunk belt beyond the hash window: chunks at the demand-generation
		// frontier receive cross-border feature spill whose arrival order is frontier-dependent
		// (verified on pure vanilla) — only interior chunks are order-independent and hashable.
		for (int cx = centerChunkX - radiusChunks - 2; cx <= centerChunkX + radiusChunks + 2; cx++) {
			for (int cz = centerChunkZ - radiusChunks - 2; cz <= centerChunkZ + radiusChunks + 2; cz++) {
				level.getChunk(cx, cz, ChunkStatus.FULL, true);
			}
		}
		for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx++) {
			for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz++) {
				ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, true);
				long h = FNV_OFFSET; // independent per-chunk hash → forensic diffing
				for (int y = minY; y < maxY; y++) {
					for (int x = 0; x < 16; x++) {
						for (int z = 0; z < 16; z++) {
							pos.set((cx << 4) + x, y, (cz << 4) + z);
							String name = blocks.getKey(chunk.getBlockState(pos).getBlock()).toString();
							for (int i = 0; i < name.length(); i++) {
								h = (h ^ name.charAt(i)) * FNV_PRIME;
							}
						}
					}
				}
				perChunk.append(String.format("chunk %d,%d: %016x%n", cx, cz, h));
				total = (total ^ h) * FNV_PRIME;
			}
		}
		String hex = String.format("%016x", total);
		ArcheanRise.LOGGER.info("World hash (seed {}, radius {} chunks, Y {}..{}): {}",
				level.getSeed(), radiusChunks, minY, maxY, hex);
		java.nio.file.Path dir = dev.archeanrise.platform.Platform.get().reportsDir();
		try {
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Files.writeString(dir.resolve("hash-" + hex + "-" + System.currentTimeMillis() + ".txt"),
					perChunk.toString());
		} catch (java.io.IOException e) {
			ArcheanRise.LOGGER.warn("Could not write per-chunk hash report: {}", e.toString());
		}
		return hex;
	}
}
