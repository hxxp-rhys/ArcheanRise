package dev.archeanrise.pregen;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * /archeanrise info
 * /archeanrise pregen start <radiusChunks> [centerBlockX centerBlockZ]
 *     (default center: the command source position)
 * /archeanrise pregen stop
 * /archeanrise pregen status
 */
public final class PregenCommand {
	private PregenCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("archeanrise")
				.then(Commands.literal("info")
						.executes(ctx -> {
							ServerLevel level = ctx.getSource().getLevel();
							ctx.getSource().sendSuccess(() -> Component.literal(String.format(
									"Archean Rise — dimension %s: Y %d to %d (height %d), seed-baked worldgen preset.",
									level.dimension().location(),
									level.getMinBuildHeight(),
									level.getMaxBuildHeight(),
									level.getHeight())), false);
							return 1;
						}))
				.then(Commands.literal("pregen")
						.requires(source -> source.hasPermission(2))
						.then(Commands.literal("start")
								.then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1))
										.executes(ctx -> {
											BlockPos origin = BlockPos.containing(ctx.getSource().getPosition());
											return start(ctx.getSource(),
													IntegerArgumentType.getInteger(ctx, "radiusChunks"),
													origin.getX(), origin.getZ());
										})
										.then(Commands.argument("centerBlockX", IntegerArgumentType.integer())
												.then(Commands.argument("centerBlockZ", IntegerArgumentType.integer())
														.executes(ctx -> start(ctx.getSource(),
																IntegerArgumentType.getInteger(ctx, "radiusChunks"),
																IntegerArgumentType.getInteger(ctx, "centerBlockX"),
																IntegerArgumentType.getInteger(ctx, "centerBlockZ")))))))
						.then(Commands.literal("stop")
								.executes(ctx -> {
									if (PregenManager.isRunning()) {
										PregenManager.cancel("command");
										ctx.getSource().sendSuccess(() -> Component.literal("Pregeneration stopped."), true);
										return 1;
									}
									ctx.getSource().sendFailure(Component.literal("No pregeneration running."));
									return 0;
								}))
						.then(Commands.literal("status")
								.executes(ctx -> {
									ctx.getSource().sendSuccess(() -> Component.literal(PregenManager.status()), false);
									return 1;
								}))));
	}

	private static int start(CommandSourceStack source, int radius, int centerBlockX, int centerBlockZ) {
		boolean started = PregenManager.start(source.getLevel(), centerBlockX >> 4, centerBlockZ >> 4, radius);
		if (started) {
			source.sendSuccess(() -> Component.literal("Pregeneration started. " + PregenManager.status()), true);
			return 1;
		}
		source.sendFailure(Component.literal(
				"A pregeneration task is already running. Use /archeanrise pregen status."));
		return 0;
	}
}
