package dev.archeanrise.pregen;

import dev.archeanrise.ArcheanRise;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.function.LongConsumer;

/**
 * Ticket-engine pregenerator (v3, per references/gpu-pregen-blueprint.md §1.3 with the
 * realism-verifier fixes). Each server tick (thread-confined by construction):
 *   1. MSPT governor adjusts the permit budget (halve above ceiling, restore below floor).
 *   2. Completion poll: pending positions whose chunk is now FULL (getChunkNow != null)
 *      release their ticket and permit.
 *   3. Top-up: issue no-timeout "archean_pregen" tickets (radius 0 = FULL border, level 33,
 *      non-ticking) until the permit budget is used.
 * The chunk system generates on its worker pool at its own pace; tickets (not futures) drive
 * scheduling — the v2 getChunkFuture window measured SLOWER than v1 sync (8.8 vs 22 chunks/s)
 * because future-spam fights the system's pacing instead of feeding it targets.
 * Stop/shutdown removes all outstanding tickets (verifier D3 lifecycle rule).
 *
 * Order: chunks are submitted CENTER-OUTWARD in concentric Chebyshev rings (index 0 = the
 * center/spawn chunk, then the ring at distance 1, then distance 2, …, out to the radius), so
 * generation starts at spawn and expands to the edge. Because submission is still strictly in
 * index order, the lowest not-yet-completed index is a contiguous-done frontier — every ring
 * closer to spawn than it is guaranteed generated. A task can start from a resume index
 * (skipping an already-generated inner prefix), and exposes that frontier via
 * {@link #committedFrontier()} + a throttled {@link #setProgressHook} so callers (AutoPregen)
 * can checkpoint progress and resume an interrupted run instead of restarting.
 */
public final class PregenManager {
	private static final TicketType<ChunkPos> PREGEN_TICKET =
			TicketType.create("archean_pregen", Comparator.comparingLong(ChunkPos::toLong));

	private static Task task;
	private static Runnable completionHook;
	private static LongConsumer progressHook;

	/** One-shot hook invoked on the server thread when the current task completes. */
	public static void setCompletionHook(Runnable hook) {
		completionHook = hook;
	}

	/**
	 * Hook invoked (throttled, on the server thread) with the current contiguous-done frontier
	 * index, and once more when the task is cancelled — lets a caller persist resume progress.
	 */
	public static void setProgressHook(LongConsumer hook) {
		progressHook = hook;
	}

	private static final class Task {
		final ServerLevel level;
		final int centerX;
		final int centerZ;
		final int diameter;
		final long total;
		final long startIndex;
		long submitted;
		final java.util.concurrent.atomic.AtomicLong done = new java.util.concurrent.atomic.AtomicLong();
		long lastSaveDone; // done-count at the last incremental drain
		final ArrayDeque<ChunkPos> pending = new ArrayDeque<>();
		int permits;
		final long startMillis = System.currentTimeMillis();
		long lastLogMillis = startMillis;
		volatile boolean paused;

		Task(ServerLevel level, int centerX, int centerZ, int radius, int permits, long startIndex) {
			this.level = level;
			this.centerX = centerX;
			this.centerZ = centerZ;
			this.diameter = radius * 2 + 1;
			this.total = (long) diameter * diameter;
			this.startIndex = Mth.clamp(startIndex, 0, total);
			this.submitted = this.startIndex;
			this.done.set(this.startIndex); // the skipped prefix is already generated
			this.lastSaveDone = this.startIndex;
			this.permits = permits;
		}

		/**
		 * Center-outward mapping: index → chunk, in concentric Chebyshev rings. Index 0 is the
		 * center; ring r (Chebyshev distance r) holds 8r cells at indices [(2r−1)², (2r+1)²),
		 * walked as four 2r-long edges (top→, right↓, bottom←, left↑). Exact inverse of
		 * {@link #indexOf}.
		 */
		ChunkPos posAt(long i) {
			if (i <= 0) {
				return new ChunkPos(centerX, centerZ);
			}
			long r = (long) Math.ceil((Math.sqrt(i + 1) - 1) / 2);
			while ((2 * r + 1) * (2 * r + 1) <= i) r++;              // correct any fp rounding
			while (r > 0 && (2 * r - 1) * (2 * r - 1) > i) r--;
			long j = i - (2 * r - 1) * (2 * r - 1);                  // 0 .. 8r−1
			long side = j / (2 * r);                                 // 0..3
			long k = j % (2 * r);                                    // 0..2r−1
			int dx;
			int dz;
			if (side == 0) { dx = (int) (-r + k); dz = (int) -r; }       // top edge, moving +x
			else if (side == 1) { dx = (int) r; dz = (int) (-r + k); }   // right edge, moving +z
			else if (side == 2) { dx = (int) (r - k); dz = (int) r; }    // bottom edge, moving −x
			else { dx = (int) -r; dz = (int) (r - k); }                  // left edge, moving −z
			return new ChunkPos(centerX + dx, centerZ + dz);
		}

		/** Inverse of {@link #posAt} — the center-outward ring index of a chunk position. */
		long indexOf(ChunkPos pos) {
			long dx = (long) pos.x - centerX;
			long dz = (long) pos.z - centerZ;
			long r = Math.max(Math.abs(dx), Math.abs(dz));
			if (r == 0) {
				return 0;
			}
			long base = (2 * r - 1) * (2 * r - 1);
			long j;
			if (dz == -r && dx >= -r && dx <= r - 1) {          // top edge (side 0)
				j = dx + r;
			} else if (dx == r && dz >= -r && dz <= r - 1) {    // right edge (side 1)
				j = 2 * r + (dz + r);
			} else if (dz == r && dx >= -r + 1 && dx <= r) {    // bottom edge (side 2)
				j = 4 * r + (r - dx);
			} else {                                            // left edge (side 3): dx==−r
				j = 6 * r + (r - dz);
			}
			return base + j;
		}
	}

	private PregenManager() {}

	public static boolean start(ServerLevel level, int centerChunkX, int centerChunkZ, int radiusChunks) {
		return start(level, centerChunkX, centerChunkZ, radiusChunks, 0L);
	}

	/**
	 * Start (or resume) a pregeneration. {@code startIndex} skips an already-generated inner
	 * (center-outward ring) prefix (0 = full run). Clears any hooks from a prior task so a plain run never inherits
	 * AutoPregen's checkpoint/completion callbacks.
	 */
	public static boolean start(ServerLevel level, int centerChunkX, int centerChunkZ, int radiusChunks,
			long startIndex) {
		if (task != null) {
			return false;
		}
		completionHook = null;
		progressHook = null;
		radiusChunks = Mth.clamp(radiusChunks, 1, ArcheanRise.config.pregenMaxRadiusChunks);
		task = new Task(level, centerChunkX, centerChunkZ, radiusChunks,
				ArcheanRise.config.pregenMaxInFlight, startIndex);
		if (task.startIndex > 0) {
			ArcheanRise.LOGGER.info("Pregen started: resuming from chunk {}/{} (radius {} around chunk "
					+ "[{}, {}]) in {} (ticket engine, window {})", task.startIndex, task.total, radiusChunks,
					centerChunkX, centerChunkZ, level.dimension().location(), task.permits);
		} else {
			ArcheanRise.LOGGER.info("Pregen started: {} chunks (radius {} around chunk [{}, {}]) in {} "
					+ "(ticket engine, window {})", task.total, radiusChunks, centerChunkX, centerChunkZ,
					level.dimension().location(), task.permits);
		}
		return true;
	}

	public static void cancel(String reason) {
		Task t = task;
		if (t != null) {
			long frontier = committedFrontier();
			// lifecycle rule: never leave pregen tickets behind
			for (ChunkPos pos : t.pending) {
				t.level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
			}
			ArcheanRise.LOGGER.info("Pregen cancelled ({}) at {}/{} chunks ({} tickets released)",
					reason, t.done.get(), t.total, t.pending.size());
			// final checkpoint: on a clean shutdown the world save that follows persists these
			// FULL chunks, so the frontier is disk-accurate. Fire before clearing the hook.
			LongConsumer hook = progressHook;
			if (hook != null && frontier >= 0) {
				hook.accept(frontier);
			}
			completionHook = null;
			progressHook = null;
			task = null;
		}
	}

	/**
	 * Lowest not-yet-completed ring index of the running task — every index below it is
	 * guaranteed FULL (generation submits in center-outward index order, so the minimum
	 * still-pending index is the contiguous-done boundary — every ring nearer the center than it
	 * is complete). Returns total if nothing is pending, -1 if idle. Errs low
	 * (a FULL-but-not-yet-polled chunk counts as pending), so it never over-reports progress.
	 */
	public static long committedFrontier() {
		Task t = task;
		if (t == null) {
			return -1;
		}
		if (t.pending.isEmpty()) {
			return t.submitted;
		}
		long min = t.submitted;
		for (ChunkPos pos : t.pending) {
			min = Math.min(min, t.indexOf(pos));
		}
		return min;
	}

	public static String status() {
		Task t = task;
		if (t == null) {
			return "No pregeneration running.";
		}
		long done = t.done.get();
		long elapsed = Math.max(1, (System.currentTimeMillis() - t.startMillis) / 1000);
		double rate = (done - t.startIndex) / (double) elapsed; // rate of THIS run, not the skipped prefix
		String eta = rate > 0
				? String.format(java.util.Locale.ROOT, "~%ds remaining", (long) ((t.total - done) / rate))
				: "estimating…";
		String pauseNote = t.paused
				? " [PAUSED: player online on dedicated server — set pregenPauseWhenPlayersOnline=false to run anyway]"
				: "";
		return String.format("Pregen: %d/%d chunks (%.1f%%), %.1f chunks/s, in flight %d, window %d, %s%s",
				done, t.total, 100.0 * done / t.total, rate, t.pending.size(), t.permits,
				eta, pauseNote);
	}

	public static boolean isRunning() {
		return task != null;
	}

	public static void tick(MinecraftServer server) {
		Task t = task;
		if (t == null) {
			return;
		}
		// completion poll: FULL chunks release their ticket
		for (int i = t.pending.size(); i > 0; i--) {
			ChunkPos pos = t.pending.poll();
			if (t.level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
				t.level.getChunkSource().removeRegionTicket(PREGEN_TICKET, pos, 0, pos);
				t.done.incrementAndGet();
			} else {
				t.pending.add(pos); // still generating
			}
		}
		// incremental drain: push generated chunks to the storage thread so the in-memory unsaved
		// set stays bounded — its serialize+compress+write cost is then spread across the run and
		// surfaced to the MSPT governor above (natural backpressure), instead of bursting on the
		// next full/quit save. save(false) is non-forcing: the flush==false path skips
		// flushWorker/processUnloads, so it does not block the tick on a flush and does not evict
		// or mutate chunks — terrain is never affected (fsync itself is governed by
		// sync-chunk-writes, not this call). Each drain serializes the currently-dirty loaded set
		// (~pregenSaveIntervalChunks tall columns) on the server thread — a brief hitch far
		// smaller than the quit-save burst it prevents, after which the MSPT governor backs off.
		int saveInterval = ArcheanRise.config.pregenSaveIntervalChunks;
		if (saveInterval > 0 && t.done.get() - t.lastSaveDone >= saveInterval) {
			t.lastSaveDone = t.done.get();
			t.level.getChunkSource().save(false);
			ArcheanRise.LOGGER.info("Pregen: drained to disk at {}/{} chunks (incremental save)",
					t.done.get(), t.total);
		}
		if (t.done.get() >= t.total) {
			long seconds = Math.max(1, (System.currentTimeMillis() - t.startMillis) / 1000);
			ArcheanRise.LOGGER.info("Pregen complete: {} chunks in {}s ({} chunks/s)",
					t.total, seconds, String.format(java.util.Locale.ROOT, "%.1f", t.total / (double) seconds));
			task = null;
			Runnable hook = completionHook;
			completionHook = null;
			progressHook = null;
			if (hook != null) {
				hook.run();
			}
			return;
		}
		t.paused = ArcheanRise.config.pregenPauseWhenPlayersOnline
				&& !server.isSingleplayer() && server.getPlayerCount() > 0;
		if (t.paused) {
			logMaybe(t);
			return;
		}
		// MSPT governor: don't starve a live server
		double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
		int configured = ArcheanRise.config.pregenMaxInFlight;
		if (mspt > 45.0) {
			t.permits = Math.max(4, t.permits / 2);
		} else if (mspt < 30.0 && t.permits < configured) {
			t.permits = Math.min(configured, t.permits + Math.max(1, configured / 16));
		}
		// top-up: issue tickets; the chunk system schedules generation on its workers.
		// NOTE a "features"-status fast path was implemented and REVERTED 2026-07-05: driving
		// chunks to FEATURES via independent futures bypasses the dependency pyramid's
		// deterministic ordering, and cross-chunk feature spill is order- AND state-dependent
		// (a tree aborts against another tree's blocks) — measured 45/49 chunks diverging from
		// the canonical world, and slower (20.8 vs 54.2 chunks/s). Any future lighting-deferral
		// must use pyramid-respecting ticket levels and re-pass the hash-match gate. See
		// docs/DECISIONS.md.
		while (t.pending.size() < t.permits && t.submitted < t.total && task == t) {
			ChunkPos pos = t.posAt(t.submitted++);
			t.level.getChunkSource().addRegionTicket(PREGEN_TICKET, pos, 0, pos);
			t.pending.add(pos);
		}
		logMaybe(t);
	}

	private static void logMaybe(Task t) {
		long now = System.currentTimeMillis();
		if (now - t.lastLogMillis >= ArcheanRise.config.pregenLogIntervalSeconds * 1000L) {
			t.lastLogMillis = now;
			ArcheanRise.LOGGER.info(status());
			LongConsumer hook = progressHook;
			if (hook != null) {
				hook.accept(committedFrontier()); // periodic resume checkpoint
			}
		}
	}
}
