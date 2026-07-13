package dev.archeanrise.rivers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.archeanrise.ArcheanRise;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@code /archeanrise carvedump <minCellX> <minCellZ> <maxCellX> <maxCellZ>} (ops only) — the
 * R2b-2 carve-parity instrument (doc 14 §2 R2b). It realizes the river graph for the region (same
 * {@link RiverGraph} + {@link ServerRiverSampler} as {@link RiverDumpCommand}), builds a
 * whole-region {@link RiverCarve.Carver}, samples the CARVED designed surface at a set of columns
 * (reach cross-sections + lake radials, which exercise BED / HALO / LAKE / OUTSIDE), and writes each
 * column's {@code (x, z, naturalHeight, carvedHeight, zone)} to the run dir as
 * {@code river-carve-dump-<seed>-<region>.json}. The companion gate
 * {@code node tools/measure/river-carve-parity.mjs <dumpFile>} re-realizes the same region with the
 * JS reference ({@code tools/preview/river-carve.mjs}) and must reproduce every carved height + zone
 * BIT-EXACTLY (the natural height is dumped and reused, so the only thing under test is the
 * {@link RiverCarve} port vs {@code river-carve.mjs} — the deepslate/vanilla offset gap is factored
 * out).
 *
 * <p>It ALSO cross-checks, in-process, that the live per-cell {@link RiverCarveProvider} (the
 * chunk-gen path) returns the byte-identical carved height to the whole-region carver at every
 * sample — proving the in-game per-cell carve equals the parity-gated whole-region carve. Runs
 * synchronously on the server thread (the {@code riverdump} precedent).
 */
public final class RiverCarveDumpCommand {
	private RiverCarveDumpCommand() {}

	/** Column cross-section half-extent + step (blocks) — spans bed + full halo + a margin. */
	private static final double CROSS_HALF = RiverCarve.W_MAX / 2 + RiverCarve.VALLEY_HALO + 12;
	private static final double CROSS_STEP = 3;
	/** Cap the reach cross-sections so the dump stays a few thousand columns on dense regions. */
	private static final int MAX_CROSS_REACHES = 150;
	/** Cap the actual-terrain heightmap reads (each loads/generates a chunk). */
	private static final int MAX_TERRAIN_COLS = 48;

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("archeanrise")
				.then(Commands.literal("carvedump")
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

	/** One sampled column: raw coordinate, natural + carved designed height, zone code. */
	private record Col(double x, double z, double naturalH, double carvedH, int zone) {}

	/** The requested cell region; a column counts only if its RIVER_CELL falls inside it. */
	private record Region(int minCX, int minCZ, int maxCX, int maxCZ) {
		boolean contains(double x, double z) {
			int cellX = (int) Math.floor(x / RiverConstants.RIVER_CELL);
			int cellZ = (int) Math.floor(z / RiverConstants.RIVER_CELL);
			return cellX >= minCX && cellX <= maxCX && cellZ >= minCZ && cellZ <= maxCZ;
		}
	}

	private static int run(CommandSourceStack source, int minCX, int minCZ, int maxCX, int maxCZ) {
		if (minCX > maxCX || minCZ > maxCZ) {
			source.sendFailure(Component.literal("carvedump: min cell must be <= max cell on both axes"));
			return 0;
		}
		ServerLevel level = source.getLevel();
		long seed = level.getSeed();
		long t0 = System.nanoTime();
		ServerRiverSampler sampler;
		RiverGraph.Realized realized;
		RiverCarve.Carver carver;
		try {
			sampler = ServerRiverSampler.of(level);
			RiverGraph graph = new RiverGraph(seed, sampler);
			realized = graph.realize(minCX, minCZ, maxCX, maxCZ);
			carver = RiverCarve.buildCarver(realized);
		} catch (IllegalStateException e) {
			source.sendFailure(Component.literal("carvedump: " + e.getMessage()));
			return 0;
		}

		// Sample only columns INSIDE the requested cell region [minCX..maxCX] × [minCZ..maxCZ]: there
		// the whole-region carver (realize with visPad) is provably complete, so it equals the in-game
		// per-cell carver. Cross-section spokes off region-edge reaches would otherwise reach into the
		// visPad cells, where the whole-region realize is deliberately truncated (its edge) but the
		// per-cell carver is still complete — a scoping artifact, not a carve difference.
		Region bounds = new Region(minCX, minCZ, maxCX, maxCZ);
		List<Col> cols = new ArrayList<>();
		sampleReachCrossSections(realized, sampler, carver, bounds, cols);
		sampleLakes(realized, sampler, carver, bounds, cols);

		// in-process cross-check: the live per-cell provider (chunk-gen path) must match the
		// whole-region carver at every sample (proves the in-game carve == the parity-gated carve).
		RiverCarveProvider provider = RiverCarveProvider.current();
		boolean providerChecked = provider != null;
		int providerMismatch = 0;
		if (provider != null) {
			for (Col c : cols) {
				if (provider.carveAt(c.x, c.z, c.naturalH).height != c.carvedH) {
					providerMismatch++;
				}
			}
		}

		// E.4 in-game manifestation: read the ACTUAL generated OCEAN_FLOOR_WG at a sample of bed
		// columns and confirm the terrain tracks the CARVED bed (not the natural surface).
		TerrainCheck tcheck = terrainCheck(level, cols);

		long ms = (System.nanoTime() - t0) / 1_000_000;
		String region = minCX + "_" + minCZ + "_" + maxCX + "_" + maxCZ;
		Path file = level.getServer().getServerDirectory().resolve(
				String.format(Locale.ROOT, "river-carve-dump-%d-%s.json", seed, region));
		try {
			Files.writeString(file, toJson(seed, minCX, minCZ, maxCX, maxCZ, carver, cols));
		} catch (IOException e) {
			source.sendFailure(Component.literal("carvedump: could not write " + file + ": " + e));
			return 0;
		}

		String msg = String.format(Locale.ROOT,
				"River carve dump: %d columns (%d segments, %d lakes) for cells [%d,%d]..[%d,%d] on seed %d "
						+ "in %d ms; per-cell provider cross-check: %s; terrain@bed n=%d "
						+ "|actual-carved| med %.1f, |actual-natural| med %.1f, dips %d/%d -> %s",
				cols.size(), carver.segCount(), carver.lakeCount(), minCX, minCZ, maxCX, maxCZ, seed, ms,
				providerChecked ? (providerMismatch + " mismatch(es)") : "SKIPPED (no live provider)",
				tcheck.n(), tcheck.medDevFromCarved(), tcheck.medDevFromNatural(), tcheck.dips(), tcheck.n(), file);
		ArcheanRise.LOGGER.info(msg);
		source.sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}

	/** Perpendicular cross-sections at reach midpoints (skips junction/lake target nodes). */
	private static void sampleReachCrossSections(RiverGraph.Realized realized, ServerRiverSampler sampler,
			RiverCarve.Carver carver, Region region, List<Col> out) {
		List<double[]> mids = new ArrayList<>(); // mx, mz, nx, nz
		for (RiverGraph.RiverPath p : realized.paths()) {
			List<RiverGraph.PathNode> nodes = p.nodes;
			for (int i = 1; i < nodes.size(); i++) {
				RiverGraph.PathNode a = nodes.get(i - 1);
				RiverGraph.PathNode b = nodes.get(i);
				if (b.junction || b.lake) {
					continue;
				}
				double dirx = b.x - a.x;
				double dirz = b.z - a.z;
				double dl = Math.sqrt(dirx * dirx + dirz * dirz);
				if (dl < 1) {
					continue;
				}
				mids.add(new double[] {(a.x + b.x) / 2, (a.z + b.z) / 2, -dirz / dl, dirx / dl});
			}
		}
		int stride = Math.max(1, mids.size() / MAX_CROSS_REACHES);
		for (int m = 0; m < mids.size(); m += stride) {
			double[] s = mids.get(m);
			for (double step = -CROSS_HALF; step <= CROSS_HALF; step += CROSS_STEP) {
				addCol(sampler, carver, region, s[0] + s[2] * step, s[1] + s[3] * step, out);
			}
		}
	}

	/** Radial spokes across each lake basin (covers LAKE / HALO / OUTSIDE around the basin rim). */
	private static void sampleLakes(RiverGraph.Realized realized, ServerRiverSampler sampler,
			RiverCarve.Carver carver, Region region, List<Col> out) {
		double[][] dirs = RiverConstants.LAKE_TABLE; // 8 frozen unit directions
		for (RiverGraph.Lake lk : realized.lakes()) {
			for (double[] d : dirs) {
				for (double r = 0; r <= RiverCarve.LAKE_R + RiverCarve.VALLEY_HALO + 12; r += CROSS_STEP) {
					addCol(sampler, carver, region, lk.x + d[0] * r, lk.z + d[1] * r, out);
				}
			}
		}
	}

	private static void addCol(ServerRiverSampler sampler, RiverCarve.Carver carver, Region region,
			double x, double z, List<Col> out) {
		if (!region.contains(x, z)) {
			return; // outside the requested cell region — the whole-region carver is incomplete here
		}
		double naturalH = sampler.designedSurfaceAt(x, z);
		RiverCarve.Result r = carver.carveAt(x, z, naturalH);
		out.add(new Col(x, z, naturalH, r.height, r.zone.code()));
	}

	/** In-game terrain manifestation of the carve (E.4): does the ACTUAL generated ground follow the
	 *  carved bed, not the natural surface? {@code medDevFromCarved} small (≈ base_3d texture) and
	 *  {@code medDevFromNatural} large (the incision) + {@code dips} = n confirms real channels. */
	private record TerrainCheck(int n, double medDevFromCarved, double medDevFromNatural, int dips) {}

	private static TerrainCheck terrainCheck(ServerLevel level, List<Col> cols) {
		List<Col> bed = new ArrayList<>();
		for (Col c : cols) {
			if (c.zone == RiverCarve.Zone.BED.code()) {
				bed.add(c);
			}
		}
		if (bed.isEmpty()) {
			return new TerrainCheck(0, Double.NaN, Double.NaN, 0);
		}
		int stride = Math.max(1, bed.size() / MAX_TERRAIN_COLS);
		List<Double> devCarved = new ArrayList<>();
		List<Double> devNatural = new ArrayList<>();
		int dips = 0;
		for (int i = 0; i < bed.size(); i += stride) {
			Col c = bed.get(i);
			int bx = (int) Math.floor(c.x);
			int bz = (int) Math.floor(c.z);
			// getChunk(FULL) generates the chunk if needed; OCEAN_FLOOR_WG = top non-fluid worldgen block
			int actual = level.getChunk(bx >> 4, bz >> 4).getHeight(Heightmap.Types.OCEAN_FLOOR_WG, bx & 15, bz & 15);
			devCarved.add(Math.abs(actual - c.carvedH));
			devNatural.add(Math.abs(actual - c.naturalH));
			if (actual < c.naturalH - 1) {
				dips++; // ground sits below where the un-carved surface would be = an incised channel
			}
		}
		return new TerrainCheck(devCarved.size(), median(devCarved), median(devNatural), dips);
	}

	private static double median(List<Double> v) {
		Collections.sort(v);
		return v.isEmpty() ? Double.NaN : v.get(v.size() / 2);
	}

	// ---- minimal JSON writer (full double precision; Double.toString = shortest round-trip) ----

	private static String toJson(long seed, int minCX, int minCZ, int maxCX, int maxCZ,
			RiverCarve.Carver carver, List<Col> cols) {
		StringBuilder sb = new StringBuilder(1 << 20);
		sb.append("{\"seed\":").append(seed)
				.append(",\"region\":[").append(minCX).append(',').append(minCZ).append(',')
				.append(maxCX).append(',').append(maxCZ).append(']')
				.append(",\"segCount\":").append(carver.segCount())
				.append(",\"lakeCount\":").append(carver.lakeCount())
				.append(",\"cols\":[");
		for (int i = 0; i < cols.size(); i++) {
			Col c = cols.get(i);
			if (i > 0) {
				sb.append(',');
			}
			sb.append('[').append(c.x).append(',').append(c.z).append(',').append(c.naturalH)
					.append(',').append(c.carvedH).append(',').append(c.zone).append(']');
		}
		sb.append("]}");
		return sb.toString();
	}
}
