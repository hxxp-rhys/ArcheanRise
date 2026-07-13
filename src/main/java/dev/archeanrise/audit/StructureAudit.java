package dev.archeanrise.audit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import dev.archeanrise.ArcheanRise;
import dev.archeanrise.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The structure compatibility audit — turns "should work" into a measured verdict.
 *
 * For EVERY structure registered by ANY loaded mod or datapack it checks:
 *  1. Biome eligibility: does the structure's biome HolderSet intersect the biomes our
 *     generator's biome source can actually produce? Empty intersection = can never spawn.
 *  2. Real placement: {@code ChunkGenerator#findNearestMapStructure} (the /locate machinery,
 *     placement + biome checks, no chunk generation) within a radius around world spawn.
 *  3. Absolute-Y anchors: re-serializes the structure to JSON and scans for {"absolute": y}
 *     height anchors — surface-relative structures are safe at any world height; absolute-Y
 *     ones are flagged with the anchor values so terrain collisions can be judged.
 *
 * Paced at one structure per server tick (locate can be slow); intended for a dev/audit
 * server, not live gameplay. Writes a markdown report and returns a chat/log summary.
 */
public final class StructureAudit {
	public record Result(ResourceLocation id, String sourceMod, boolean createIntegration,
			boolean biomeEligible, String otherDimension, Boolean located, List<Integer> absoluteYs) {

		String verdict() {
			if (!biomeEligible) {
				return otherDimension != null
						? "OTHER-DIMENSION(" + otherDimension + ")"
						: "INCOMPATIBLE(no-eligible-biome)";
			}
			if (located != null && !located) return "NOT-LOCATED(radius)";
			return "COMPATIBLE";
		}
	}

	private static final class Task {
		final ServerLevel level;
		final List<Holder.Reference<Structure>> structures;
		final int radiusChunks;
		final boolean locate;
		final List<Result> results = new ArrayList<>();
		final long startMillis = System.currentTimeMillis();
		int index;

		Task(ServerLevel level, List<Holder.Reference<Structure>> structures, int radiusChunks, boolean locate) {
			this.level = level;
			this.structures = structures;
			this.radiusChunks = radiusChunks;
			this.locate = locate;
		}
	}

	private static Task task;

	private StructureAudit() {}

	public static boolean isRunning() {
		return task != null;
	}

	/**
	 * @param locate run the /locate-machinery placement probe per structure. Thorough but can
	 *               take unbounded seconds per structure on huge modded registries — use fast
	 *               mode (false) for fleet audits and deep mode (true) on shortlists, with the
	 *               server watchdog disabled ({@code max-tick-time=-1}).
	 */
	public static boolean start(ServerLevel level, int radiusChunks, boolean locate) {
		if (task != null) {
			return false;
		}
		Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
		List<Holder.Reference<Structure>> all = registry.holders().toList();
		task = new Task(level, all, radiusChunks, locate);
		ArcheanRise.LOGGER.info("Structure audit started: {} structures, mode {} — dev/audit servers only.",
				all.size(), locate ? "deep (locate radius " + radiusChunks + ")" : "fast (no locate)");
		return true;
	}

	public static void tick(MinecraftServer server) {
		if (task == null) {
			return;
		}
		int budget = task.locate ? 1 : 16;
		for (int i = 0; i < budget && task != null; i++) {
			if (task.index >= task.structures.size()) {
				finish();
				return;
			}
			Holder.Reference<Structure> holder = task.structures.get(task.index++);
			task.results.add(auditOne(task.level, holder, task.radiusChunks, task.locate));
			if (task.index % 100 == 0) {
				ArcheanRise.LOGGER.info("Structure audit: {}/{}", task.index, task.structures.size());
			}
		}
	}

	private static Result auditOne(ServerLevel level, Holder.Reference<Structure> holder, int radiusChunks,
			boolean locate) {
		ResourceLocation id = holder.key().location();
		Structure structure = holder.value();

		Set<Holder<Biome>> possible = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
		boolean eligible = structure.biomes().stream().anyMatch(possible::contains);

		String otherDimension = null;
		if (!eligible) {
			for (ServerLevel other : level.getServer().getAllLevels()) {
				if (other == level) {
					continue;
				}
				Set<Holder<Biome>> otherBiomes = other.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
				if (structure.biomes().stream().anyMatch(otherBiomes::contains)) {
					otherDimension = other.dimension().location().toString();
					break;
				}
			}
		}

		Boolean located = null;
		if (eligible && locate) {
			try {
				Pair<BlockPos, Holder<Structure>> hit = level.getChunkSource().getGenerator()
						.findNearestMapStructure(level, HolderSet.direct(holder), level.getSharedSpawnPos(),
								radiusChunks, false);
				located = hit != null;
			} catch (Exception e) {
				ArcheanRise.LOGGER.warn("Structure audit: locate failed for {}: {}", id, e.toString());
			}
		}

		List<Integer> absoluteYs = new ArrayList<>();
		try {
			RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
			Structure.DIRECT_CODEC.encodeStart(ops, structure).result()
					.ifPresent(json -> collectAbsoluteYs(json, absoluteYs));
		} catch (Exception e) {
			ArcheanRise.LOGGER.debug("Structure audit: could not serialize {}: {}", id, e.toString());
		}

		return new Result(id, sourceMod(id.getNamespace()), dependsOnCreate(id.getNamespace()),
				eligible, otherDimension, located, absoluteYs);
	}

	/** Recursively collect {"absolute": <int>} height anchors from serialized structure JSON. */
	private static void collectAbsoluteYs(JsonElement element, List<Integer> out) {
		if (element.isJsonObject()) {
			JsonObject obj = element.getAsJsonObject();
			JsonElement abs = obj.get("absolute");
			if (abs != null && abs.isJsonPrimitive() && abs.getAsJsonPrimitive().isNumber()) {
				out.add(abs.getAsInt());
			}
			obj.entrySet().forEach(e -> collectAbsoluteYs(e.getValue(), out));
		} else if (element.isJsonArray()) {
			element.getAsJsonArray().forEach(e -> collectAbsoluteYs(e, out));
		}
	}

	private static String sourceMod(String namespace) {
		if (namespace.equals("minecraft")) {
			return "minecraft";
		}
		return Platform.get().mod(namespace)
				.map(m -> m.id() + " " + m.version())
				.orElse(namespace + " (datapack or non-matching mod id)");
	}

	private static boolean dependsOnCreate(String namespace) {
		if (namespace.startsWith("create")) {
			return true;
		}
		return Platform.get().mod(namespace)
				.map(m -> m.dependencyModIds().contains("create"))
				.orElse(false);
	}

	private static void finish() {
		Task done = task;
		task = null;
		long seconds = Math.max(1, (System.currentTimeMillis() - done.startMillis) / 1000);
		long compatible = done.results.stream().filter(r -> r.verdict().equals("COMPATIBLE")).count();
		long noBiome = done.results.stream().filter(r -> !r.biomeEligible()).count();
		long notLocated = done.results.stream().filter(r -> r.verdict().startsWith("NOT-LOCATED")).count();
		ArcheanRise.LOGGER.info("Structure audit complete in {}s: {} structures — {} compatible, "
				+ "{} no eligible biome, {} not located within radius (rare/large-spacing structures "
				+ "may need a bigger radius rather than being incompatible).",
				seconds, done.results.size(), compatible, noBiome, notLocated);
		writeReport(done);
	}

	private static void writeReport(Task done) {
		Path dir = Platform.get().reportsDir();
		Path file = dir.resolve("structure-audit-" + System.currentTimeMillis() + ".md");
		StringBuilder sb = new StringBuilder();
		sb.append("# Archean Rise structure audit\n\n");
		sb.append("Dimension: ").append(done.level.dimension().location())
				.append(" | seed-locate radius: ").append(done.radiusChunks).append(" chunks | ")
				.append(done.results.size()).append(" structures\n\n");
		sb.append("| structure | source mod | create? | biome-eligible | located | absolute-Y anchors | verdict |\n");
		sb.append("|---|---|---|---|---|---|---|\n");
		for (Result r : done.results) {
			sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s |%n",
					r.id(), r.sourceMod(), r.createIntegration() ? "yes" : "no",
					r.biomeEligible() ? "yes" : "NO",
					r.located() == null ? "skipped" : (r.located() ? "yes" : "no"),
					r.absoluteYs().isEmpty() ? "-" : r.absoluteYs().toString(),
					r.verdict()));
		}
		try {
			Files.createDirectories(dir);
			Files.writeString(file, sb.toString());
			ArcheanRise.LOGGER.info("Structure audit report written to {}", file);
		} catch (IOException e) {
			ArcheanRise.LOGGER.error("Could not write structure audit report: {}", e.toString());
		}
	}
}
