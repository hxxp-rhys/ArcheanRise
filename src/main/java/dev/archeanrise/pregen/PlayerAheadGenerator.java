package dev.archeanrise.pregen;

import dev.archeanrise.ArcheanRise;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Player-ahead terrain generation: keeps chunks GENERATED (not loaded) out to
 * view distance + {@code playerAheadChunks} around every player, so terrain always exists
 * before it scrolls into visible range. Adaptive: under sustained server load (MSPT > 45)
 * the ahead margin falls back to 3 chunks (48 blocks) until load recovers.
 *
 * Mechanics mirror the pregen ticket engine: short-lived tickets pull chunks to FULL on the
 * worker pool; completion is detected via getChunkNow and the ticket released — generated
 * chunks then persist on disk and unload normally. A per-player ring cursor spreads work so
 * each tick issues at most a small budget.
 */
public final class PlayerAheadGenerator {
	private static final TicketType<ChunkPos> AHEAD_TICKET =
			TicketType.create("archean_ahead", Comparator.comparingLong(ChunkPos::toLong));
	private static final int GLOBAL_IN_FLIGHT_CAP = 48;
	private static final int ISSUE_BUDGET_PER_TICK = 24;
	private static final int SEEN_CAP = 65536;

	private record Pending(ServerLevel level, ChunkPos pos) {}

	private static final ArrayDeque<Pending> pending = new ArrayDeque<>();
	/** chunks already verified/issued per level — avoids re-ticketing generated terrain */
	private static final Map<ServerLevel, Set<Long>> seen = new HashMap<>();

	private PlayerAheadGenerator() {}

	public static void tick(MinecraftServer server) {
		if (ArcheanRise.config == null || !ArcheanRise.config.playerAheadEnabled) {
			return;
		}
		// completion poll
		for (int i = pending.size(); i > 0; i--) {
			Pending p = pending.poll();
			if (p.level().getChunkSource().getChunkNow(p.pos().x, p.pos().z) != null) {
				p.level().getChunkSource().removeRegionTicket(AHEAD_TICKET, p.pos(), 0, p.pos());
			} else {
				pending.add(p);
			}
		}
		double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
		int ahead = mspt > 45.0 ? 3 : ArcheanRise.config.playerAheadChunks;
		int viewDistance = server.getPlayerList().getViewDistance();
		int radius = viewDistance + ahead;

		int budget = ISSUE_BUDGET_PER_TICK;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (budget <= 0 || pending.size() >= GLOBAL_IN_FLIGHT_CAP) {
				break;
			}
			ServerLevel level = player.serverLevel();
			Set<Long> levelSeen = seen.computeIfAbsent(level, l -> new HashSet<>());
			if (levelSeen.size() > SEEN_CAP) {
				levelSeen.clear(); // cheap reset; re-verification is a fast getChunkNow-or-ticket
			}
			int pcx = player.blockPosition().getX() >> 4;
			int pcz = player.blockPosition().getZ() >> 4;
			// walk the square border rings from just beyond view distance outward — the zone
			// vanilla isn't already generating
			for (int r = viewDistance; r <= radius && budget > 0; r++) {
				for (int i = -r; i <= r && budget > 0; i++) {
					budget -= visit(level, levelSeen, pcx + i, pcz - r) ? 1 : 0;
					budget -= visit(level, levelSeen, pcx + i, pcz + r) ? 1 : 0;
					budget -= visit(level, levelSeen, pcx - r, pcz + i) ? 1 : 0;
					budget -= visit(level, levelSeen, pcx + r, pcz + i) ? 1 : 0;
				}
			}
		}
	}

	/** @return true if a ticket was issued (budget consumed) */
	private static boolean visit(ServerLevel level, Set<Long> levelSeen, int cx, int cz) {
		long key = ChunkPos.asLong(cx, cz);
		if (!levelSeen.add(key)) {
			return false;
		}
		if (level.getChunkSource().getChunkNow(cx, cz) != null) {
			return false; // already loaded (and therefore generated)
		}
		ChunkPos pos = new ChunkPos(cx, cz);
		level.getChunkSource().addRegionTicket(AHEAD_TICKET, pos, 0, pos);
		pending.add(new Pending(level, pos));
		return true;
	}
}
