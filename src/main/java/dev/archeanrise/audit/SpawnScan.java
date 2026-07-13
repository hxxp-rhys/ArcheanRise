package dev.archeanrise.audit;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Locale;

/**
 * Seed-scouting probe: samples the preliminary-surface predictor on a coarse grid around
 * spawn WITHOUT generating chunks — answers "is spawn near (but not on) mountains?" in
 * milliseconds. Predictor omits jaggedness, so real peaks read ~10-25% higher; thresholds
 * are calibrated for classification, not exact height.
 *
 * Two modes: {@link #scan} probes the LOADED world at its real spawn; {@link #scanSeed}
 * probes ANY seed from a running server by building a fresh RandomState and reproducing
 * vanilla's climate-based spawn choice (Climate.Sampler.findSpawnPosition — the same search
 * setInitialSpawn starts from; the booted game then adjusts locally, typically <128 blocks).
 */
public final class SpawnScan {
	private static final double THRESHOLD = 0.390625;

	private SpawnScan() {}

	/** Probe-grid statistics around a spawn point. */
	public record GridStats(int spawnHeight, double waterPct, int minH, int maxH, int maxX, int maxZ,
			double nearestDist, int nmX, int nmZ, int rangeColumns) {}

	/** One seed's scout result ({@code rangeColumns} = probe columns at/above mountainHeight). */
	public record SeedResult(long seed, int spawnX, int spawnZ, GridStats grid) {}

	public static String scan(ServerLevel level, int radiusBlocks, int pitch, int mountainHeight) {
		DensityFunction predictor = level.getChunkSource().randomState().router()
				.initialDensityWithoutJaggedness();
		int spawnX = level.getSharedSpawnPos().getX();
		int spawnZ = level.getSharedSpawnPos().getZ();
		GridStats g = probeGrid(predictor, spawnX, spawnZ, radiusBlocks, pitch, mountainHeight,
				level.getMinBuildHeight(), level.getMaxBuildHeight());
		String result = String.format(Locale.ROOT,
				"SpawnScan seed %d: spawn (%d,%d) h=%d | water %.0f%% | min h=%d | max h=%d @(%d,%d) d=%.0f | "
						+ "nearest h>=%d: %s | range columns %d",
				level.getSeed(), spawnX, spawnZ, g.spawnHeight(),
				g.waterPct(), g.minH(), g.maxH(), g.maxX(), g.maxZ(),
				Math.sqrt((double) (g.maxX() - spawnX) * (g.maxX() - spawnX)
						+ (double) (g.maxZ() - spawnZ) * (g.maxZ() - spawnZ)),
				mountainHeight,
				g.nearestDist() == Double.MAX_VALUE ? "none in range"
						: String.format(Locale.ROOT, "@(%d,%d) d=%.0f", g.nmX(), g.nmZ(), g.nearestDist()),
				g.rangeColumns());
		ArcheanRise.LOGGER.info(result);
		return result;
	}

	/**
	 * Scout an arbitrary seed using the loaded world's generator settings. Returns null (with a
	 * log line) if the loaded dimension is not noise-based.
	 */
	public static SeedResult scanSeed(ServerLevel level, long seed, int radiusBlocks, int pitch,
			int mountainHeight) {
		if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
			ArcheanRise.LOGGER.warn("SpawnScan: dimension generator is not noise-based — cannot scout seeds");
			return null;
		}
		RandomState randomState = RandomState.create(generator.generatorSettings().value(),
				level.registryAccess().registryOrThrow(Registries.NOISE).asLookup(), seed);
		BlockPos spawn = randomState.sampler().findSpawnPosition();
		GridStats g = probeGrid(randomState.router().initialDensityWithoutJaggedness(),
				spawn.getX(), spawn.getZ(), radiusBlocks, pitch, mountainHeight,
				level.getMinBuildHeight(), level.getMaxBuildHeight());
		return new SeedResult(seed, spawn.getX(), spawn.getZ(), g);
	}

	/**
	 * Coarse square grid: corners reach radius*sqrt(2) — distance criteria must be enforced by
	 * the caller. The spawn column is probed separately ({@code spawnHeight}); grid stats can
	 * miss the true nearest range edge by up to ~pitch*sqrt(2)/2 blocks. Classifier, not survey.
	 */
	private static GridStats probeGrid(DensityFunction predictor, int spawnX, int spawnZ,
			int radiusBlocks, int pitch, int mountainHeight, int bottom, int top) {
		int spawnHeight = probe(predictor, spawnX, spawnZ, bottom, top);
		int maxH = Integer.MIN_VALUE;
		int minH = Integer.MAX_VALUE;
		int maxX = 0;
		int maxZ = 0;
		double nearestMountainDist = Double.MAX_VALUE;
		int nmX = 0;
		int nmZ = 0;
		int rangeColumns = 0;
		int water = 0;
		int count = 0;
		for (int x = spawnX - radiusBlocks; x <= spawnX + radiusBlocks; x += pitch) {
			for (int z = spawnZ - radiusBlocks; z <= spawnZ + radiusBlocks; z += pitch) {
				int h = probe(predictor, x, z, bottom, top);
				count++;
				if (h < 63) {
					water++;
				}
				if (h > maxH) {
					maxH = h;
					maxX = x;
					maxZ = z;
				}
				minH = Math.min(minH, h);
				if (h >= mountainHeight) {
					rangeColumns++;
					double d = Math.sqrt((double) (x - spawnX) * (x - spawnX)
							+ (double) (z - spawnZ) * (z - spawnZ));
					if (d < nearestMountainDist) {
						nearestMountainDist = d;
						nmX = x;
						nmZ = z;
					}
				}
			}
		}
		return new GridStats(spawnHeight, 100.0 * water / count, minH, maxH, maxX, maxZ,
				nearestMountainDist, nmX, nmZ, rangeColumns);
	}

	private static int probe(DensityFunction predictor, int x, int z, int bottom, int top) {
		for (int y = top; y >= bottom; y -= 8) {
			if (predictor.compute(new DensityFunction.SinglePointContext(x, y, z)) > THRESHOLD) {
				return y;
			}
		}
		return bottom;
	}
}
