package dev.archeanrise.audit;

import dev.archeanrise.ArcheanRise;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Seed hunt: sweeps sequential seeds with {@link SpawnScan#scanSeed} (one per tick — each
 * probe builds a full RandomState, so pacing keeps a live server responsive) looking for
 * "spawn NEAR a mountain range but not ON it". Criteria, using the coarse no-chunkgen
 * predictor (peaks read ~10-25% low vs. real jaggedness):
 *   - spawn on dry, non-mountain ground: 64 <= spawn h <= MAX_SPAWN_H
 *   - not at the foot of / on the range: nearest mountain column farther than MIN_DIST blocks
 *   - within reach: nearest mountain column inside the scan radius (the command's radius arg)
 *   - a RANGE, not a lone peak: >= MIN_RANGE_COLUMNS probe columns at/above mountainHeight
 *   - playable surroundings: water <= MAX_WATER_PCT of probes
 * Results land in archean-rise-reports/; qualifying seeds also log live. Dev/audit tool.
 */
public final class SeedHunt {
	static final int MAX_SPAWN_H = 180;
	static final int MIN_DIST = 200;
	static final int MIN_RANGE_COLUMNS = 12;
	static final double MAX_WATER_PCT = 25.0;
	private static final int PITCH = 48;

	private static Task task;

	private static final class Task {
		final ServerLevel level;
		final long startSeed;
		final int count;
		final int radiusBlocks;
		final int mountainHeight;
		int index;
		final List<SpawnScan.SeedResult> qualifying = new ArrayList<>();
		final long startMillis = System.currentTimeMillis();
		long lastLogMillis = startMillis;

		Task(ServerLevel level, long startSeed, int count, int radiusBlocks, int mountainHeight) {
			this.level = level;
			this.startSeed = startSeed;
			this.count = count;
			this.radiusBlocks = radiusBlocks;
			this.mountainHeight = mountainHeight;
		}
	}

	private SeedHunt() {}

	public static boolean start(ServerLevel level, long startSeed, int count, int radiusBlocks,
			int mountainHeight) {
		if (task != null) {
			return false;
		}
		if (startSeed > Long.MAX_VALUE - count) {
			ArcheanRise.LOGGER.warn("Seed hunt: startSeed {} + count {} would overflow — pick a "
					+ "smaller start", startSeed, count);
			return false;
		}
		// self-check BEFORE publishing the task, so a failing probe can never wedge the tool
		SpawnScan.SeedResult self;
		try {
			self = SpawnScan.scanSeed(level, level.getSeed(), radiusBlocks, PITCH, mountainHeight);
		} catch (Exception e) {
			ArcheanRise.LOGGER.warn("Seed hunt: self-check probe failed — not starting: {}", e.toString());
			return false;
		}
		if (self == null) {
			return false;
		}
		double selfDist = Math.sqrt(Math.pow(self.spawnX() - level.getSharedSpawnPos().getX(), 2)
				+ Math.pow(self.spawnZ() - level.getSharedSpawnPos().getZ(), 2));
		ArcheanRise.LOGGER.info("Seed hunt started: {} seeds from {} (radius {}, mountain h>={}, "
				+ "criteria: spawn 64..{}, nearest {}..{}, columns>={}, water<={}%). Self-check: "
				+ "climate spawn ({},{}) vs actual world spawn ({},{}) d={} blocks",
				count, startSeed, radiusBlocks, mountainHeight, maxSpawnHeight(mountainHeight), MIN_DIST,
				radiusBlocks, MIN_RANGE_COLUMNS, (int) MAX_WATER_PCT, self.spawnX(), self.spawnZ(),
				level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getZ(), (int) selfDist);
		task = new Task(level, startSeed, count, radiusBlocks, mountainHeight);
		return true;
	}

	/** Cancels a running hunt, writing the partial report so accumulated hits are never lost. */
	public static void cancel(String reason) {
		Task t = task;
		if (t != null) {
			ArcheanRise.LOGGER.info("Seed hunt cancelled ({}) at {}/{} seeds — writing partial report",
					reason, t.index, t.count);
			finish(t);
			task = null;
		}
	}

	public static void tick(MinecraftServer server) {
		Task t = task;
		if (t == null) {
			return;
		}
		if (t.level.getServer() != server) {
			// a different server instance is ticking: the hunt's world was closed mid-run
			// (singleplayer world switch). SERVER_STOPPING already wrote the partial report;
			// never touch the stale level — just drop the task.
			ArcheanRise.LOGGER.warn("Seed hunt: its world was closed — hunt dropped at {}/{} seeds",
					t.index, t.count);
			task = null;
			return;
		}
		if (t.index < t.count) {
			SpawnScan.SeedResult r;
			try {
				r = SpawnScan.scanSeed(t.level, t.startSeed + t.index++,
						t.radiusBlocks, PITCH, t.mountainHeight);
			} catch (Exception e) {
				ArcheanRise.LOGGER.warn("Seed hunt: probe failed at seed {} — stopping with partial "
						+ "report: {}", t.startSeed + t.index - 1, e.toString());
				finish(t);
				task = null;
				return;
			}
			if (r == null) {
				finish(t); // abort path keeps the accumulated hits
				task = null;
				return;
			}
			if (qualifies(r, t.radiusBlocks, t.mountainHeight)) {
				t.qualifying.add(r);
				ArcheanRise.LOGGER.info("Seed hunt HIT: {}", describe(r, t.mountainHeight));
			}
			long now = System.currentTimeMillis();
			if (now - t.lastLogMillis >= 5000) {
				t.lastLogMillis = now;
				ArcheanRise.LOGGER.info("Seed hunt: {}/{} seeds scanned, {} qualifying",
						t.index, t.count, t.qualifying.size());
			}
			return;
		}
		finish(t);
		task = null;
	}

	/** Spawn must sit clearly below mountain class even when the user picks a low threshold. */
	private static int maxSpawnHeight(int mountainHeight) {
		return Math.min(MAX_SPAWN_H, mountainHeight - 1);
	}

	private static boolean qualifies(SpawnScan.SeedResult r, int maxDist, int mountainHeight) {
		SpawnScan.GridStats g = r.grid();
		// the probe grid is a SQUARE (corners reach radius*sqrt(2)), so the "within reach"
		// bound must be enforced here, not implied by the scan radius
		return g.spawnHeight() >= 64 && g.spawnHeight() <= maxSpawnHeight(mountainHeight)
				&& g.nearestDist() > MIN_DIST
				&& g.nearestDist() <= maxDist
				&& g.waterPct() <= MAX_WATER_PCT
				&& g.rangeColumns() >= MIN_RANGE_COLUMNS;
	}

	private static String describe(SpawnScan.SeedResult r, int mountainHeight) {
		SpawnScan.GridStats g = r.grid();
		return String.format(Locale.ROOT,
				"seed %d: spawn (%d,%d) h=%d, water %.0f%%, nearest h>=%d @(%d,%d) d=%.0f, "
						+ "range columns %d, max h=%d",
				r.seed(), r.spawnX(), r.spawnZ(), g.spawnHeight(), g.waterPct(), mountainHeight,
				g.nmX(), g.nmZ(), g.nearestDist(), g.rangeColumns(), g.maxH());
	}

	private static void finish(Task t) {
		long seconds = Math.max(1, (System.currentTimeMillis() - t.startMillis) / 1000);
		// rank: biggest range mass first, then least water — "impressive massif, playable spawn"
		t.qualifying.sort(Comparator
				.comparingInt((SpawnScan.SeedResult r) -> -r.grid().rangeColumns())
				.thenComparingDouble(r -> r.grid().waterPct()));
		StringBuilder report = new StringBuilder();
		report.append(String.format(Locale.ROOT,
				"Seed hunt: %d of %d seeds processed from %d, radius %d, mountain h>=%d, pitch %d — "
						+ "%d qualifying (%ds)%n"
						+ "criteria: 64<=spawnH<=%d, %d<nearest<=%d, columns>=%d, water<=%.0f%%%n%n",
				t.index, t.count, t.startSeed, t.radiusBlocks, t.mountainHeight, PITCH,
				t.qualifying.size(), seconds, maxSpawnHeight(t.mountainHeight), MIN_DIST,
				t.radiusBlocks, MIN_RANGE_COLUMNS, MAX_WATER_PCT));
		for (SpawnScan.SeedResult r : t.qualifying) {
			report.append(describe(r, t.mountainHeight)).append(System.lineSeparator());
		}
		try {
			Path dir = dev.archeanrise.platform.Platform.get().reportsDir();
			Files.createDirectories(dir);
			Path file = dir.resolve(String.format(Locale.ROOT, "seed-hunt-%d-%d-%d.txt",
					t.startSeed, t.count, System.currentTimeMillis()));
			Files.writeString(file, report.toString());
			ArcheanRise.LOGGER.info("Seed hunt complete: {} qualifying of {} processed in {}s — report: {}",
					t.qualifying.size(), t.index, seconds, file);
		} catch (IOException e) {
			ArcheanRise.LOGGER.warn("Seed hunt: could not write report: {}", e.toString());
			ArcheanRise.LOGGER.info("Seed hunt results:\n{}", report);
		}
		int top = Math.min(10, t.qualifying.size());
		for (int i = 0; i < top; i++) {
			ArcheanRise.LOGGER.info("Seed hunt top {}: {}", i + 1, describe(t.qualifying.get(i), t.mountainHeight));
		}
	}
}
