package dev.archeanrise.audit;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Terrain quality forensics over ALREADY-GENERATED chunks (never generates new ones).
 * Metrics per column:
 *  - WORLD_SURFACE height → relief statistics (min/mean/max/stddev) and mean neighbor
 *    slope |Δh| (feature-scale indicator).
 *  - Aerial-island detection: any solid segment whose top is above {@code AERIAL_MIN_Y}
 *    with ≥ {@code MIN_GAP} blocks of air directly beneath it — the "floating blocks not
 *    attached to the ground" defect class. Vanilla-like terrain shows a low single-digit
 *    percentage (legit overhangs); broken 3-D-noise scaling shows far more.
 */
public final class TerrainAudit {
	private static final int AERIAL_MIN_Y = 80;
	private static final int MIN_GAP = 6;

	private TerrainAudit() {}

	public record Stats(int columns, int skippedChunks, double meanSurface, int minSurface, int maxSurface,
			double stddev, double meanSlope, int aerialColumns, int aerialSegments, int islandSegments,
			int highestAerialY, java.util.List<String> aerialSamples) {}

	public static Stats run(ServerLevel level, int radiusChunks) {
		return run(level, 0, 0, radiusChunks);
	}

	public static Stats run(ServerLevel level, int centerChunkX, int centerChunkZ, int radiusChunks) {
		long sum = 0;
		long sumSq = 0;
		long slopeSum = 0;
		long slopeCount = 0;
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		int columns = 0;
		int skipped = 0;
		int aerialColumns = 0;
		int aerialSegments = 0;
		int islandSegments = 0;
		int highestAerial = Integer.MIN_VALUE;

		java.util.List<String> samples = new java.util.ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx++) {
			for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz++) {
				// create=true loads saved chunks from disk (create=false only sees in-memory
				// chunks); ungenerated chunks in range are generated — scan radii accordingly.
				ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, true);
				if (chunk == null) {
					skipped++;
					continue;
				}
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						int ws = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
						columns++;
						sum += ws;
						sumSq += (long) ws * ws;
						min = Math.min(min, ws);
						max = Math.max(max, ws);
						if (z > 0) {
							int prev = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1);
							slopeSum += Math.abs(ws - prev);
							slopeCount++;
						}

						// aerial-island scan: walk down from the surface, count solid segments
						// that end with a big air gap while still above AERIAL_MIN_Y
						int y = ws;
						boolean columnFlagged = false;
						while (y > AERIAL_MIN_Y) {
							// top of a solid segment
							while (y > level.getMinBuildHeight() && isAir(chunk, pos, x, y, z)) {
								y--;
							}
							if (y <= AERIAL_MIN_Y) {
								break;
							}
							int segTop = y;
							while (y > level.getMinBuildHeight() && !isAir(chunk, pos, x, y, z)) {
								y--;
							}
							// y is now first air below the segment (or bottom)
							int gap = 0;
							int probe = y;
							while (probe > level.getMinBuildHeight() && isAir(chunk, pos, x, probe, z) && gap <= MIN_GAP) {
								gap++;
								probe--;
							}
							// aerial = a mass hovering HIGH UP: its underside (y+1) must also be
							// elevated, else this is just a mountain over an ordinary deep cave
							if (gap >= MIN_GAP && segTop > AERIAL_MIN_Y && y > AERIAL_MIN_Y - 8) {
								// island vs attached-lip: a cliff cornice touches solid ground
								// horizontally at its mid-height; a true floating island doesn't
								int midY = (segTop + y + 1) / 2;
								int wx = (chunk.getPos().x << 4) + x;
								int wz = (chunk.getPos().z << 4) + z;
								int attached = 0;
								for (int[] n : new int[][] {{6, 0}, {-6, 0}, {0, 6}, {0, -6}}) {
									pos.set(wx + n[0], midY, wz + n[1]);
									if (!level.getBlockState(pos).isAir()) {
										attached++;
									}
								}
								boolean island = attached == 0;
								aerialSegments++;
								if (island) {
									islandSegments++;
								}
								highestAerial = Math.max(highestAerial, segTop);
								columnFlagged = true;
								if (island && samples.size() < 40) {
									pos.set(wx, segTop, wz);
									samples.add(String.format(Locale.ROOT,
											"ISLAND (%d, %d, %d) top=%s thickness=%d gapBelow>=%d",
											wx, segTop, wz,
											chunk.getBlockState(pos).getBlock().getName().getString(),
											segTop - y, gap));
								}
							}
						}
						if (columnFlagged) {
							aerialColumns++;
						}
					}
				}
			}
		}
		double mean = columns == 0 ? 0 : (double) sum / columns;
		double variance = columns == 0 ? 0 : ((double) sumSq / columns) - mean * mean;
		Stats stats = new Stats(columns, skipped, mean, min == Integer.MAX_VALUE ? 0 : min,
				max == Integer.MIN_VALUE ? 0 : max, Math.sqrt(Math.max(0, variance)),
				slopeCount == 0 ? 0 : (double) slopeSum / slopeCount,
				aerialColumns, aerialSegments, islandSegments,
				highestAerial == Integer.MIN_VALUE ? 0 : highestAerial, samples);
		report(level, radiusChunks, stats);
		return stats;
	}

	/** Foliage counts as air so tree canopies don't register as aerial islands. */
	private static boolean isAir(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z) {
		pos.set((chunk.getPos().x << 4) + x, y, (chunk.getPos().z << 4) + z);
		net.minecraft.world.level.block.state.BlockState state = chunk.getBlockState(pos);
		return state.isAir() || state.is(net.minecraft.tags.BlockTags.LEAVES)
				|| state.is(net.minecraft.tags.BlockTags.LOGS);
	}

	private static void report(ServerLevel level, int radius, Stats s) {
		String text = String.format(Locale.ROOT, """
				# Archean Rise terrain audit

				Dimension: %s | dimension Y %d..%d | scan radius %d chunks (existing chunks only, %d absent)

				| metric | value |
				|---|---|
				| columns scanned | %d |
				| surface mean / min / max | %.1f / %d / %d |
				| surface stddev (relief) | %.1f |
				| mean neighbor slope |Δh| | %.3f |
				| aerial-island columns (>=%d air below solid above y=%d) | %d (%.2f%%) |
				| aerial segments total | %d |
				| true ISLAND segments (no horizontal attachment) | %d |
				| highest aerial segment top | %d |
				""",
				level.dimension().location(), level.getMinBuildHeight(), level.getMaxBuildHeight(), radius,
				s.skippedChunks(), s.columns(), s.meanSurface(), s.minSurface(), s.maxSurface(), s.stddev(),
				s.meanSlope(), MIN_GAP, AERIAL_MIN_Y, s.aerialColumns(),
				s.columns() == 0 ? 0 : 100.0 * s.aerialColumns() / s.columns(), s.aerialSegments(),
				s.islandSegments(), s.highestAerialY());
		if (!s.aerialSamples().isEmpty()) {
			text += "\n## Aerial segment samples (foliage excluded)\n\n"
					+ String.join("\n", s.aerialSamples()) + "\n";
		}
		ArcheanRise.LOGGER.info("Terrain audit: {} columns, surface {}..{} (mean {}), slope {}, "
				+ "aerial columns {} ({} segments, {} true ISLANDS)",
				s.columns(), s.minSurface(), s.maxSurface(),
				String.format(Locale.ROOT, "%.1f", s.meanSurface()),
				String.format(Locale.ROOT, "%.3f", s.meanSlope()),
				s.aerialColumns(), s.aerialSegments(), s.islandSegments());
		Path dir = Platform.get().reportsDir();
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("terrain-audit-" + System.currentTimeMillis() + ".md"), text);
		} catch (IOException e) {
			ArcheanRise.LOGGER.error("Could not write terrain audit report: {}", e.toString());
		}
	}
}
