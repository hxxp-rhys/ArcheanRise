package dev.archeanrise.rivers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.archeanrise.ArcheanRise;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * {@code /archeanrise riverdump <minCellX> <minCellZ> <maxCellX> <maxCellZ>} (ops only) — R2a
 * parity instrument (doc 14 §2): realizes the river graph for the given cell region on the
 * server's seed via {@link RiverGraph} + {@link ServerRiverSampler} and writes a node-exact JSON
 * dump into the run directory as {@code river-dump-<seed>-<region>.json}. The companion gate
 * {@code node tools/measure/river-parity.mjs <dumpFile>} re-realizes the same region with the JS
 * reference (tools/preview/rivers-r1.mjs) and must find ZERO numeric differences.
 *
 * <p>Runs synchronously on the server thread (the {@code archeanrise-audit hash} precedent): a
 * region realization is pure math over the routing/offset density functions — a few hundred ms
 * for the gate's 3×3-cell regions — and synchrony lets scripted consoles queue
 * {@code riverdump ... ; stop} safely. Doubles are serialized with {@link Double#toString}
 * (shortest round-trip on Java 19+); cross-language comparison is NUMERIC in the parity tool —
 * never textual — because Java and JS format some values differently (e.g. 1.2E7 vs 12000000).
 */
public final class RiverDumpCommand {
	private RiverDumpCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("archeanrise")
				.then(Commands.literal("riverdump")
						.requires(source -> source.hasPermission(2))
						.then(Commands.argument("minCellX", IntegerArgumentType.integer(-512, 512))
								.then(Commands.argument("minCellZ", IntegerArgumentType.integer(-512, 512))
										.then(Commands.argument("maxCellX", IntegerArgumentType.integer(-512, 512))
												.then(Commands.argument("maxCellZ", IntegerArgumentType.integer(-512, 512))
														.executes(ctx -> run(ctx.getSource(),
																IntegerArgumentType.getInteger(ctx, "minCellX"),
																IntegerArgumentType.getInteger(ctx, "minCellZ"),
																IntegerArgumentType.getInteger(ctx, "maxCellX"),
																IntegerArgumentType.getInteger(ctx, "maxCellZ")))))))));
	}

	private static int run(CommandSourceStack source, int minCX, int minCZ, int maxCX, int maxCZ) {
		if (minCX > maxCX || minCZ > maxCZ) {
			source.sendFailure(Component.literal("riverdump: min cell must be <= max cell on both axes"));
			return 0;
		}
		ServerLevel level = source.getLevel();
		long seed = level.getSeed();
		RiverGraph.Realized realized;
		long t0 = System.nanoTime();
		try {
			RiverGraph graph = new RiverGraph(seed, ServerRiverSampler.of(level));
			realized = graph.realize(minCX, minCZ, maxCX, maxCZ);
		} catch (IllegalStateException e) {
			source.sendFailure(Component.literal("riverdump: " + e.getMessage()));
			return 0;
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;

		String region = minCX + "_" + minCZ + "_" + maxCX + "_" + maxCZ;
		Path file = level.getServer().getServerDirectory().resolve(
				String.format(Locale.ROOT, "river-dump-%d-%s.json", seed, region));
		try {
			Files.writeString(file, toJson(seed, minCX, minCZ, maxCX, maxCZ, realized));
		} catch (IOException e) {
			source.sendFailure(Component.literal("riverdump: could not write " + file + ": " + e));
			return 0;
		}
		String msg = String.format(Locale.ROOT,
				"River dump: %d paths, %d lakes for cells [%d,%d]..[%d,%d] on seed %d in %d ms -> %s",
				realized.paths().size(), realized.lakes().size(), minCX, minCZ, maxCX, maxCZ, seed, ms, file);
		ArcheanRise.LOGGER.info(msg);
		source.sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}

	// ---- minimal JSON writer (no HTML-escaping surprises, full double precision) ----

	private static String toJson(long seed, int minCX, int minCZ, int maxCX, int maxCZ,
			RiverGraph.Realized realized) {
		StringBuilder sb = new StringBuilder(1 << 20);
		sb.append("{\"seed\":").append(seed)
				.append(",\"region\":[").append(minCX).append(',').append(minCZ).append(',')
				.append(maxCX).append(',').append(maxCZ).append("],\"paths\":[");
		boolean firstPath = true;
		for (RiverGraph.RiverPath p : realized.paths()) {
			if (!firstPath) {
				sb.append(',');
			}
			firstPath = false;
			sb.append("{\"source\":{\"cellX\":").append(p.source.cellX())
					.append(",\"cellZ\":").append(p.source.cellZ())
					.append(",\"idx\":").append(p.source.idx())
					.append("},\"terminal\":\"").append(p.terminal).append('"');
			if (p.joinTo == null) {
				sb.append(",\"joinTo\":null");
			} else {
				sb.append(",\"joinTo\":{\"srcKey\":\"").append(p.joinTo.srcKey())
						.append("\",\"segI\":").append(p.joinTo.segI())
						.append(",\"t\":").append(p.joinTo.t()).append('}');
			}
			sb.append(",\"nodes\":[");
			List<RiverGraph.PathNode> nodes = p.nodes;
			for (int i = 0; i < nodes.size(); i++) {
				RiverGraph.PathNode n = nodes.get(i);
				if (i > 0) {
					sb.append(',');
				}
				sb.append('[').append(n.x).append(',').append(n.z).append(',').append(n.ry)
						.append(',').append(n.waterY)
						.append(',').append(n.lip ? 1 : 0)
						.append(',').append(n.junction ? 1 : 0)
						.append(',').append(n.backwater ? 1 : 0)
						.append(',').append(n.lake ? 1 : 0)
						.append(',').append(n.selfLoop ? 1 : 0)
						.append(',').append(n.stepDrop)          // R2b-4a: flat-reach step drop
						.append(',').append(n.reachId).append(']'); // R2b-4a: per-path pool index
			}
			sb.append("]}");
		}
		sb.append("],\"lakes\":[");
		boolean firstLake = true;
		for (RiverGraph.Lake lk : realized.lakes()) {
			if (!firstLake) {
				sb.append(',');
			}
			firstLake = false;
			sb.append("{\"x\":").append(lk.x).append(",\"z\":").append(lk.z)
					.append(",\"level\":").append(lk.level)
					.append(",\"endorheic\":").append(lk.endorheic);
			if (lk.endorheic) {
				sb.append(",\"phantom\":").append(lk.phantom)
						.append(",\"selfLoop\":").append(lk.selfLoop)
						.append(",\"truncated\":").append(lk.truncated);
			}
			sb.append('}');
		}
		sb.append("]}");
		return sb.toString();
	}
}
