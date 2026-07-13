package dev.archeanrise.audit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /archeanrise-audit structures [locateRadiusChunks]  — full structure compat audit (paced, report file)
 * /archeanrise-audit hash [radiusChunks]              — golden-seed regression hash (synchronous)
 *
 * Registered as its own root (rather than under /archeanrise) so audit tooling can be
 * permission-gated separately by server admins.
 */
public final class AuditCommand {
	private AuditCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("archeanrise-audit")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("structures")
						.executes(ctx -> startStructures(ctx.getSource(), 0, false))
						.then(Commands.literal("fast")
								.executes(ctx -> startStructures(ctx.getSource(), 0, false)))
						.then(Commands.literal("deep")
								.executes(ctx -> startStructures(ctx.getSource(), 32, true))
								.then(Commands.argument("locateRadiusChunks", IntegerArgumentType.integer(8, 512))
										.executes(ctx -> startStructures(ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "locateRadiusChunks"), true)))))
				.then(Commands.literal("terrain")
						.executes(ctx -> runTerrain(ctx.getSource(), 16, 0, 0))
						.then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 64))
								.executes(ctx -> runTerrain(ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "radiusChunks"), 0, 0))
								.then(Commands.argument("centerBlockX", IntegerArgumentType.integer())
										.then(Commands.argument("centerBlockZ", IntegerArgumentType.integer())
												.executes(ctx -> runTerrain(ctx.getSource(),
														IntegerArgumentType.getInteger(ctx, "radiusChunks"),
														IntegerArgumentType.getInteger(ctx, "centerBlockX") >> 4,
														IntegerArgumentType.getInteger(ctx, "centerBlockZ") >> 4))))))
				.then(Commands.literal("spawnscan")
						.executes(ctx -> runSpawnScan(ctx.getSource(), 1024, 64, 250))
						.then(Commands.argument("radiusBlocks", IntegerArgumentType.integer(128, 8192))
								.then(Commands.argument("mountainHeight", IntegerArgumentType.integer(80, 600))
										.executes(ctx -> runSpawnScan(ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "radiusBlocks"), 64,
												IntegerArgumentType.getInteger(ctx, "mountainHeight"))))))
				.then(Commands.literal("seedhunt")
						.then(Commands.literal("cancel")
								.executes(ctx -> {
									SeedHunt.cancel("command");
									ctx.getSource().sendSuccess(() -> Component.literal(
											"Seed hunt cancelled (partial report written if one was running)."), true);
									return 1;
								}))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 100000))
								.executes(ctx -> startSeedHunt(ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "count"), 1_000_000L, 1000, 250))
								.then(Commands.argument("startSeed", LongArgumentType.longArg())
										.executes(ctx -> startSeedHunt(ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "count"),
												LongArgumentType.getLong(ctx, "startSeed"), 1000, 250))
										.then(Commands.argument("radiusBlocks", IntegerArgumentType.integer(256, 4096))
												.then(Commands.argument("mountainHeight", IntegerArgumentType.integer(80, 600))
														.executes(ctx -> startSeedHunt(ctx.getSource(),
																IntegerArgumentType.getInteger(ctx, "count"),
																LongArgumentType.getLong(ctx, "startSeed"),
																IntegerArgumentType.getInteger(ctx, "radiusBlocks"),
																IntegerArgumentType.getInteger(ctx, "mountainHeight"))))))))
				.then(Commands.literal("placement")
						.executes(ctx -> runPlacement(ctx.getSource(),
								net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()).getX(),
								net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition()).getZ()))
						.then(Commands.argument("x", IntegerArgumentType.integer())
								.then(Commands.argument("z", IntegerArgumentType.integer())
										.executes(ctx -> runPlacement(ctx.getSource(),
												IntegerArgumentType.getInteger(ctx, "x"),
												IntegerArgumentType.getInteger(ctx, "z"))))))
				.then(Commands.literal("hash")
						.executes(ctx -> runHash(ctx.getSource(), 2, 0, 0))
						.then(Commands.argument("radiusChunks", IntegerArgumentType.integer(0, 16))
								.executes(ctx -> runHash(ctx.getSource(),
										IntegerArgumentType.getInteger(ctx, "radiusChunks"), 0, 0))
								.then(Commands.argument("centerBlockX", IntegerArgumentType.integer())
										.then(Commands.argument("centerBlockZ", IntegerArgumentType.integer())
												.executes(ctx -> runHash(ctx.getSource(),
														IntegerArgumentType.getInteger(ctx, "radiusChunks"),
														IntegerArgumentType.getInteger(ctx, "centerBlockX") >> 4,
														IntegerArgumentType.getInteger(ctx, "centerBlockZ") >> 4)))))));
	}

	private static int startStructures(CommandSourceStack source, int radius, boolean locate) {
		if (StructureAudit.start(source.getLevel(), radius, locate)) {
			source.sendSuccess(() -> Component.literal(
					"Structure audit started (" + (locate ? "deep: locate probes, 1/tick — disable the watchdog"
							: "fast: eligibility + Y-anchor scan, 16/tick")
							+ "; report lands in archean-rise-reports/)."), true);
			return 1;
		}
		source.sendFailure(Component.literal("A structure audit is already running."));
		return 0;
	}

	private static int runTerrain(CommandSourceStack source, int radius, int centerChunkX, int centerChunkZ) {
		TerrainAudit.Stats stats = TerrainAudit.run(source.getLevel(), centerChunkX, centerChunkZ, radius);
		source.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
				"Terrain audit: %d columns, surface %d..%d (mean %.1f, stddev %.1f), slope %.3f, "
						+ "aerial columns %d (%.2f%%) — report in archean-rise-reports/",
				stats.columns(), stats.minSurface(), stats.maxSurface(), stats.meanSurface(),
				stats.stddev(), stats.meanSlope(), stats.aerialColumns(),
				stats.columns() == 0 ? 0 : 100.0 * stats.aerialColumns() / stats.columns())), false);
		return 1;
	}

	private static int runSpawnScan(CommandSourceStack source, int radiusBlocks, int pitch, int mountainHeight) {
		String result = SpawnScan.scan(source.getLevel(), radiusBlocks, pitch, mountainHeight);
		source.sendSuccess(() -> Component.literal(result), false);
		return 1;
	}

	private static int startSeedHunt(CommandSourceStack source, int count, long startSeed,
			int radiusBlocks, int mountainHeight) {
		if (SeedHunt.start(source.getLevel(), startSeed, count, radiusBlocks, mountainHeight)) {
			source.sendSuccess(() -> Component.literal(
					"Seed hunt started: " + count + " seeds from " + startSeed + " (1/tick; "
							+ "hits log live, report lands in archean-rise-reports/)."), true);
			return 1;
		}
		source.sendFailure(Component.literal(
				"Seed hunt could not start (already running, or dimension is not noise-based)."));
		return 0;
	}

	private static int runPlacement(CommandSourceStack source, int x, int z) {
		source.sendSuccess(() -> Component.literal(
				"Placement audit near " + x + "," + z + " (loads the village chunks — may pause briefly)..."), true);
		String result = PlacementAudit.audit(source.getLevel(), x, z);
		source.sendSuccess(() -> Component.literal(result), false);
		return 1;
	}

	private static int runHash(CommandSourceStack source, int radius, int centerChunkX, int centerChunkZ) {
		source.sendSuccess(() -> Component.literal(
				"Hashing chunks in radius " + radius + " (synchronous — may pause the server briefly)..."), true);
		String hex = WorldHashAudit.hash(source.getLevel(), centerChunkX, centerChunkZ, radius);
		source.sendSuccess(() -> Component.literal("World hash: " + hex
				+ " (record baseline in docs/TESTING.md)"), false);
		return 1;
	}
}
