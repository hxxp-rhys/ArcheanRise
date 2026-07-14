package dev.archeanrise;

import com.mojang.brigadier.CommandDispatcher;
import dev.archeanrise.audit.AuditCommand;
import dev.archeanrise.audit.StructureAudit;
import dev.archeanrise.config.ArcheanRiseConfig;
import dev.archeanrise.pregen.PregenCommand;
import dev.archeanrise.pregen.PregenManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-neutral core of Archean Rise. The Fabric entrypoint ({@code ArcheanRiseFabric}) and the
 * NeoForge entrypoint ({@code ArcheanRiseNeoForge}, in the {@code :neoforge} subproject) both call
 * {@link #commonInit()} and wire their loader's command / tick / lifecycle / join events to the
 * static handlers here.
 *
 * <p><b>Invariant:</b> NO loader import ({@code net.fabricmc.*} / {@code net.neoforged.*}) may
 * appear in this class — it is compiled by BOTH loader builds against the shared source. Loader
 * services go through {@link dev.archeanrise.platform.Platform}; custom-registry writes are done by
 * each entrypoint (eager on Fabric, {@code DeferredRegister} on NeoForge — see
 * {@link dev.archeanrise.noise.df.ArcheanRiseDensityFunctions}).
 */
public final class ArcheanRise {
	public static final String MOD_ID = "archean_rise";
	public static final Logger LOGGER = LoggerFactory.getLogger("Archean Rise");

	public static ArcheanRiseConfig config;

	/** Set at server-started when the loaded world contradicts the configured intent; shown in red to ops on join. */
	private static String joinWarning;

	private ArcheanRise() {}

	/** Loader-neutral init: config and region compression. Call once at startup. */
	public static void commonInit() {
		config = ArcheanRiseConfig.load();
		applyRegionCompression();
		dev.archeanrise.compat.ModCompat.init(); // detect + compose with the recommended perf stack (C2ME/Lithium/…)

		LOGGER.info("Archean Rise initialized — world preset: archean_rise:archean_rise "
				+ "(static Y -256..768, mountain cap 708, seafloor -128)");
	}

	/** Register the mod's commands. Called from the loader's command-registration event. */
	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		PregenCommand.register(dispatcher);
		AuditCommand.register(dispatcher);
		dev.archeanrise.rivers.RiverDumpCommand.register(dispatcher); // R2a parity instrument (doc 14)
		dev.archeanrise.rivers.RiverCarveDumpCommand.register(dispatcher); // R2b carve-parity instrument
	}

	/** End-of-server-tick pump for the mod's async workers. */
	public static void onServerTick(MinecraftServer server) {
		PregenManager.tick(server);
		dev.archeanrise.pregen.PlayerAheadGenerator.tick(server);
		StructureAudit.tick(server);
		dev.archeanrise.audit.SeedHunt.tick(server);
	}

	/** Server starting (BEFORE world load / spawn-chunk gen): capture the server so the
	 * {@code river_carve} DF ({@link dev.archeanrise.rivers.RiverCarveProvider}) can reach the
	 * Archean overworld's river graph during the very first (spawn) chunk generation. */
	public static void onServerStarting(MinecraftServer server) {
		dev.archeanrise.rivers.RiverCarveProvider.onServerStarting(server);
	}

	/** Server fully started: report the world preset, then extras, then auto-pregen — ORDER MATTERS
	 * (reportWorldPreset sets the {@link #joinWarning} the login handler consumes). */
	public static void onServerStarted(MinecraftServer server) {
		reportWorldPreset(server);
		dev.archeanrise.worldgen.ore.OreGate.reset(server); // ore Phase-0 set re-resolves lazily per server
		dev.archeanrise.sitegrading.SiteGrading.reportExtras(server);
		dev.archeanrise.sitegrading.BuriedStructures.reportOverrides(server);
		dev.archeanrise.pregen.AutoPregen.onServerStarted(server);
	}

	/** Server stopping: cancel async work before the world unloads. */
	public static void onServerStopping(MinecraftServer server) {
		PregenManager.cancel("server stopping");
		dev.archeanrise.audit.SeedHunt.cancel("server stopping");
		dev.archeanrise.worldgen.ore.OreGate.reset(server); // drop the registry reference for GC
		dev.archeanrise.rivers.RiverCarveProvider.onServerStopping(server); // drop server + river graphs
		dev.archeanrise.sitegrading.BuriedStructures.clear(); // drop cached generators/classifications for GC
	}

	/**
	 * On join, surface any world/config mismatch IN-GAME to ops / the singleplayer host — the
	 * log-only warning has repeatedly gone unseen while players wondered where the terrain went.
	 */
	public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
		if (joinWarning != null && (player.hasPermissions(2) || server.isSingleplayer())) {
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(joinWarning)
					.withStyle(net.minecraft.ChatFormatting.RED));
		}
	}

	/**
	 * SELF-CHECK (v0.3.16): the world IS an Archean Rise world — so does Archean Rise still own its own
	 * generator? If not, every worldgen subsystem is silently off and the player would never know: the
	 * terrain still generates correctly (the density functions are plain data), but there are no rivers, no
	 * structure re-spacing, no ore rebalance, no site grading and no structure gates.
	 *
	 * <p>This is not hypothetical. Until 0.3.16 the identity was re-derived on every call from the
	 * generator's noise-settings registry key, and <b>Lithostitched</b> — required by Terralith, Tectonic,
	 * Regions Unexplored, CTOV and ~25 other mods — replaces that holder with a KEYLESS one whenever any
	 * loaded mod ships a {@code lithostitched:add_surface_rule} for the overworld. Archean Rise switched
	 * itself off, in complete silence, for months.
	 *
	 * <p>The identity is now captured at generator construction and cannot be stolen. This check exists so
	 * that if some future mod DOES manage to take it — by replacing the generator itself, say — we say so,
	 * loudly, in the log AND in-game to ops, instead of quietly generating a hollow world.
	 */
	private static void checkGeneratorIdentity(net.minecraft.server.level.ServerLevel overworld) {
		net.minecraft.world.level.chunk.ChunkGenerator generator =
				overworld.getChunkSource().getGenerator();
		if (dev.archeanrise.sitegrading.SiteGrading.isArcheanGenerator(generator)) {
			return; // healthy — we own our generator
		}
		// Say EXACTLY what went wrong: the class we were handed, and whether it is even a noise generator.
		// Without this the failure is a shrug; with it, a bug report is actionable in one line.
		String kind = generator instanceof dev.archeanrise.duck.ArcheanGeneratorDuck
				? "it IS a noise generator, but it was not constructed from Archean Rise noise settings"
				: "it is not even a vanilla noise generator — another mod has SUBSTITUTED the generator object";
		LOGGER.error("ARCHEAN RISE IS DISABLED IN THIS WORLD. The world is an Archean Rise world, but Archean "
				+ "Rise does not recognise this dimension's chunk generator: {} ({}). Terrain will still "
				+ "generate (it is data), but RIVERS, STRUCTURE SPACING, THE ORE REBALANCE, SITE GRADING and "
				+ "ALL STRUCTURE GATES ARE OFF. This is a mod conflict, not a corrupt world — remove the "
				+ "offending worldgen mod and regenerate, or report it with your full mod list.",
				generator.getClass().getName(), kind);
		joinWarning = "[Archean Rise] DISABLED — another mod replaced this world's chunk generator. Terrain "
				+ "still generates, but rivers, ore balance, structure spacing and site grading are all OFF. "
				+ "See the server log.";
	}

	/**
	 * Region-file write codec, applied globally before any world/region is created. Its main use
	 * is enabling LZ4 in SINGLEPLAYER (no server.properties there); on a dedicated server
	 * region-file-compression in server.properties is applied later and wins. LZ4 is lossless and
	 * readable by vanilla 1.21.1 — see docs/INSTALLATION.md §6.
	 */
	private static void applyRegionCompression() {
		String codec = config.regionFileCompression;
		if (codec == null || "default".equalsIgnoreCase(codec)) {
			return; // leave vanilla's Deflate default untouched
		}
		try {
			net.minecraft.world.level.chunk.storage.RegionFileVersion.configure(codec);
			int id = net.minecraft.world.level.chunk.storage.RegionFileVersion.getSelected().getId();
			LOGGER.info("Region-file write compression set to '{}' (codec id {}) — new chunks write "
					+ "with it; existing chunks still read. Applies to all dimensions.", codec, id);
		} catch (RuntimeException e) {
			LOGGER.warn("Could not set region-file compression '{}': {} — using vanilla default", codec, e.toString());
		}
	}

	/**
	 * World geometry is baked at world creation; identify the loaded overworld by its
	 * (min_y, height) signature. Since the 0.3.0 pivot there is exactly ONE supported preset —
	 * the static world (Y -256..768). Pre-0.3.0 tier/legacy worlds reference registry entries
	 * this jar no longer ships, so they normally fail to load at all; their signatures are still
	 * recognised here for the case where an old Archean Rise datapack is pinned ahead of the jar
	 * (see limitations/world-height.md).
	 */
	private static void reportWorldPreset(MinecraftServer server) {
		joinWarning = null;
		net.minecraft.server.level.ServerLevel overworld = server.overworld();
		int minY = overworld.dimensionType().minY();
		int height = overworld.dimensionType().height();
		String signature = minY + "/" + height;
		// Pre-0.3.0 signatures: v0.2.26 tiers, pre-v0.2.26 tiers, and the legacy v0.1 preset.
		boolean isOldTierWorld = switch (signature) {
			case "-128/496", "-176/592", "-240/720", "-304/848", "-368/1008", // v0.2.26 tiers 1-5
					"-96/464", "-128/544", "-176/656", "-224/768", "-272/912" // pre-v0.2.26 tiers 1-5
					-> true;
			default -> false;
		};
		if (minY == -256 && height == 1024) {
			// Keep in sync with tools/generate-worldgen.mjs WORLD.
			LOGGER.info("Archean Rise static world active (Y -256 to 768, mountain cap 708, seafloor -128; "
					+ "relief 3.32x, landforms 6.64x, biomes 12x)");
			checkGeneratorIdentity(overworld);
		} else if (isOldTierWorld || (minY == -128 && height == 640)) {
			String kind = isOldTierWorld ? "a pre-0.3.0 TIERED" : "the legacy v0.1";
			LOGGER.warn("This overworld matches {} Archean Rise geometry (Y {} to {}). The tier system was "
					+ "removed in v0.3.0 — this world is NOT supported by this version (an older Archean Rise "
					+ "datapack is most likely pinned ahead of the jar). Keep such worlds on mod version "
					+ "0.2.26. See limitations/world-height.md.", kind, minY, minY + height);
			joinWarning = "[Archean Rise] This world uses pre-0.3.0 Archean Rise geometry (Y " + minY + ".."
					+ (minY + height) + "). The tier system was removed in v0.3.0 — stay on 0.2.26 for this world.";
		} else {
			LOGGER.info("Overworld is not an Archean Rise preset (Y {} to {}); Archean Rise terrain inactive.",
					minY, minY + height);
			joinWarning = "[Archean Rise] This world's overworld is NOT an Archean Rise preset (Y "
					+ minY + ".." + (minY + height) + ") — terrain is inactive. Either the world was "
					+ "created without selecting the Archean Rise world type, or an overworld-overhaul "
					+ "mod (e.g. Expanded Ecosphere/Terralith) replaced it at creation. See "
					+ "limitations/mod-compatibility.md.";
		}
	}
}
