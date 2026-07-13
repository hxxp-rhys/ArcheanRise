package dev.archeanrise.pregen;

import dev.archeanrise.ArcheanRise;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Dev tool: automatic spawn-area pregeneration. On server start, if enabled, kicks the async
 * pregen engine for {@code autoPregenRadiusBlocks} around world spawn.
 *
 * <p>Progress is checkpointed to a marker file so an interrupted run RESUMES instead of
 * restarting: the marker records the target radius, whether it finished, and the
 * contiguous-done frontier (chunk index). The frontier is persisted periodically, and on clean
 * shutdown — where the world save that follows makes it disk-accurate — via a
 * {@link PregenManager#setProgressHook progress hook}, and the finished state via the
 * completion hook. On the next start, a partial run for the SAME radius resumes from the
 * checkpoint minus a small {@link #RESUME_OVERLAP} band (re-doing already-generated chunks is
 * a cheap disk load, and the overlap covers the in-flight window). Integrity: the frontier is
 * a lower bound on generated chunks, so resuming never corrupts and never loses data; the only
 * effect of an unclean crash between periodic checkpoints is that a thin band of the most
 * recently generated chunks is not pre-generated and instead generates on demand when visited
 * (identical to the never-pregenerated state). The pregen order is center-outward rings, so the
 * index→chunk mapping depends only on the CENTER (not the radius); resume conservatively requires
 * both radius and center to match the checkpoint, regenerating from the start otherwise (a future
 * optimization could resume across radius changes, since the inner rings are radius-independent).
 */
public final class AutoPregen {
	private static final String MARKER = "archean_rise-autopregen.txt";
	/** Chunks re-done below a resumed checkpoint — covers the in-flight window on resume. */
	static final long RESUME_OVERLAP = 1024;

	private AutoPregen() {}

	/**
	 * Parsed marker state. {@code complete}: the target radius finished. {@code index}: resume
	 * frontier. {@code centerX/centerZ}: the spawn chunk the ring mapping was built around —
	 * resume requires it to match, so a spawn relocation ({@code /setworldspawn}) restarts
	 * rather than skipping chunks that map to a different center. {@link #NO_CENTER} = unknown
	 * (legacy marker) → never matches → safe restart.
	 */
	private record Marker(int radius, int centerX, int centerZ, boolean complete, long index) {}

	/** Sentinel center for legacy/centerless markers — never equals a real spawn chunk. */
	private static final int NO_CENTER = Integer.MIN_VALUE;

	public static void onServerStarted(MinecraftServer server) {
		if (ArcheanRise.config == null || !ArcheanRise.config.autoPregenEnabled) {
			return;
		}
		int radiusBlocks = ArcheanRise.config.autoPregenRadiusBlocks;
		Path marker = server.getWorldPath(LevelResource.ROOT).resolve(MARKER);
		Marker m = readMarker(marker);
		if (m != null && m.complete() && m.radius() >= radiusBlocks) {
			ArcheanRise.LOGGER.info("AutoPregen: already completed to radius {} blocks (configured {}) — skipping.",
					m.radius(), radiusBlocks);
			return;
		}
		ServerLevel overworld = server.overworld();
		int centerChunkX = overworld.getSharedSpawnPos().getX() >> 4;
		int centerChunkZ = overworld.getSharedSpawnPos().getZ() >> 4;
		// clamp to the same cap PregenManager.start uses, so total/logs match the real task
		int radiusChunks = Mth.clamp(Math.max(1, radiusBlocks >> 4), 1,
				ArcheanRise.config.pregenMaxRadiusChunks);
		long total = (long) (radiusChunks * 2 + 1) * (radiusChunks * 2 + 1);
		// resume only when the checkpoint is a partial run for the SAME radius AND the same spawn
		// center — conservatively restart on any mismatch (ring order is center-based, so the
		// index→chunk mapping only truly depends on the center; radius match is the safe choice)
		long startIndex = 0;
		if (m != null && !m.complete() && m.radius() == radiusBlocks
				&& m.centerX() == centerChunkX && m.centerZ() == centerChunkZ) {
			startIndex = Mth.clamp(m.index() - RESUME_OVERLAP, 0, total);
		}
		if (PregenManager.start(overworld, centerChunkX, centerChunkZ, radiusChunks, startIndex)) {
			if (startIndex > 0) {
				ArcheanRise.LOGGER.info("AutoPregen: resuming from chunk {}/{} for radius {} blocks — "
						+ "progress checkpointed; marker written on completion.", startIndex, total, radiusBlocks);
			} else {
				ArcheanRise.LOGGER.info("AutoPregen: generating {} blocks ({} chunks) around spawn — "
						+ "progress checkpointed; marker written on completion.", radiusBlocks, radiusChunks);
			}
			PregenManager.setProgressHook(
					frontier -> writeMarker(marker, radiusBlocks, centerChunkX, centerChunkZ, false, frontier));
			PregenManager.setCompletionHook(() -> {
				writeMarker(marker, radiusBlocks, centerChunkX, centerChunkZ, true, total);
				ArcheanRise.LOGGER.info("AutoPregen: complete; marker recorded at radius {} blocks.", radiusBlocks);
			});
		} else {
			ArcheanRise.LOGGER.info("AutoPregen: a pregeneration task is already running — skipped.");
		}
	}

	private static Marker readMarker(Path marker) {
		try {
			if (!Files.exists(marker)) {
				return null;
			}
			String raw = Files.readString(marker).trim();
			if (raw.isEmpty()) {
				return null;
			}
			// backward compat: a bare integer is the old "completed radius" format (no center;
			// complete markers skip by radius alone, so NO_CENTER is fine here)
			try {
				return new Marker(Integer.parseInt(raw), NO_CENTER, NO_CENTER, true, 0);
			} catch (NumberFormatException notLegacy) {
				// current key=value format
			}
			int radius = -1;
			int centerX = NO_CENTER;
			int centerZ = NO_CENTER;
			boolean complete = false;
			long index = 0;
			for (String line : raw.split("\\R")) {
				int eq = line.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String key = line.substring(0, eq).trim();
				String val = line.substring(eq + 1).trim();
				switch (key) {
					case "radius" -> radius = Integer.parseInt(val);
					case "centerX" -> centerX = Integer.parseInt(val);
					case "centerZ" -> centerZ = Integer.parseInt(val);
					case "complete" -> complete = Boolean.parseBoolean(val);
					case "index" -> index = Long.parseLong(val);
					default -> { /* ignore unknown keys */ }
				}
			}
			if (radius < 0) {
				ArcheanRise.LOGGER.warn("AutoPregen: marker missing radius — treating as not started");
				return null;
			}
			return new Marker(radius, centerX, centerZ, complete, Math.max(0, index));
		} catch (IOException | NumberFormatException e) {
			ArcheanRise.LOGGER.warn("AutoPregen: unreadable marker ({}) — treating as not started", e.toString());
			return null;
		}
	}

	private static void writeMarker(Path marker, int radiusBlocks, int centerX, int centerZ,
			boolean complete, long index) {
		String contents = String.format(Locale.ROOT,
				"radius=%d%ncenterX=%d%ncenterZ=%d%ncomplete=%b%nindex=%d%n",
				radiusBlocks, centerX, centerZ, complete, index);
		// atomic write: a crash mid-write must not leave a torn marker (which would discard
		// resume progress). Write a temp file then move it into place atomically.
		Path tmp = marker.resolveSibling(MARKER + ".tmp");
		try {
			Files.writeString(tmp, contents);
			try {
				Files.move(tmp, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException noAtomic) {
				Files.move(tmp, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			ArcheanRise.LOGGER.warn("AutoPregen: could not write marker: {}", e.toString());
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// best effort
			}
		}
	}
}
