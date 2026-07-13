package dev.archeanrise.rivers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a worldgen density-function JSON tree with the PREVIEW ENGINE's exact numeric
 * semantics ({@code tools/preview/engine.mjs} on deepslate) — the bit-parity reference the river
 * graph is gated against. Built for the R2a rivers parity gate (doc 14 §2), consumed by
 * {@link ServerRiverSampler} for the {@code rise/rivers/routing} and {@code rise/overworld/offset}
 * trees only; the game's terrain keeps evaluating through untouched vanilla machinery.
 *
 * <p><b>Why not the vanilla {@code DensityFunction} instances?</b> Measured (first R2a parity
 * run, seed 424242: 1169 diffs, every one at float-ulp scale): vanilla and deepslate disagree by
 * ~1 float ulp in {@code minecraft:spline} nodes, because
 * <ul>
 *   <li>vanilla casts the spline COORDINATE to float before interpolating
 *       ({@code Spline.Coordinate.apply} → {@code (float) compute(ctx)}); deepslate keeps the
 *       full double and only frounds each arithmetic op;</li>
 *   <li>vanilla parses spline point VALUES with {@code Codec.FLOAT}; deepslate keeps them RAW
 *       doubles (its {@code CubicSpline.fromJson} wraps numeric values unrounded) — and values
 *       like {@code 0.05} or the knee samples are not float-representable, so the rounding is
 *       unrecoverable from a parsed vanilla {@code CubicSpline}.</li>
 * </ul>
 * Every other node type in these trees (add/mul/min/clamp/shifted_noise/shift_a/shift_b/
 * voronoi/ridged_multifractal and the {@code NormalNoise} math itself) is pure double and
 * identical across vanilla, deepslate and this class. Noises are instantiated through
 * {@link RandomState#getOrCreateNoise} — the SAME seeded instances the terrain uses, which is
 * exactly how the preview seeds deepslate ({@code fromHashOf(noiseId)}).
 *
 * <p><b>Marker semantics:</b> deepslate's {@code flat_cache} quantizes the evaluation coordinate
 * to the containing quart ({@code x >> 2 << 2}, y = 0). Callers of this evaluator quantize at the
 * ROOT (see {@link ServerRiverSampler}); every inner marker then sees quart-aligned coordinates,
 * where quantization is the identity — so markers here are pass-throughs, exactly matching the
 * deepslate evaluation of the same tree at the same root coordinate. Coordinates flow untouched
 * through all interior nodes (only leaf noises consume them), so the identity holds tree-wide.
 *
 * <p>Unknown node types fail loudly — this evaluator supports exactly the vocabulary the two
 * emitted trees use, and a generator change that widens it must extend this class (the parity
 * gate would catch silence anyway).
 */
final class PreviewParityEvaluator {

	/** A parsed 2-D node: evaluate at (x, 0, z). All trees here are 2-D by construction. */
	interface Node {
		double at(double x, double z);
	}

	private static final double OCTAVE_DECORRELATE = 1259.0; // keep in sync with RidgedMultifractal

	private final MinecraftServer server;
	private final RandomState randomState;
	private final Map<String, Node> refs = new HashMap<>();
	private final Map<String, NormalNoise> noises = new HashMap<>();

	PreviewParityEvaluator(MinecraftServer server, RandomState randomState) {
		this.server = server;
		this.randomState = randomState;
	}

	/** Resolve a density-function registry id (e.g. {@code archean_rise:rise/rivers/routing}). */
	Node ref(String id) {
		Node existing = refs.get(id);
		if (existing != null) {
			if (existing == CYCLE_MARKER) {
				throw new IllegalStateException("density function reference cycle at: " + id);
			}
			return existing;
		}
		refs.put(id, CYCLE_MARKER);
		Node parsed = parse(loadJson(id));
		refs.put(id, parsed);
		return parsed;
	}

	private static final Node CYCLE_MARKER = (x, z) -> {
		throw new IllegalStateException("evaluated a cycle marker");
	};

	private JsonElement loadJson(String id) {
		int colon = id.indexOf(':');
		String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
		String path = colon < 0 ? id : id.substring(colon + 1);
		ResourceLocation file = ResourceLocation.fromNamespaceAndPath(ns,
				"worldgen/density_function/" + path + ".json");
		try (Reader reader = server.getResourceManager().getResourceOrThrow(file).openAsReader()) {
			return JsonParser.parseReader(reader);
		} catch (IOException e) {
			throw new IllegalStateException("cannot read density function " + id + " (" + file + "): " + e, e);
		}
	}

	private NormalNoise noise(String id) {
		return noises.computeIfAbsent(id, key -> randomState.getOrCreateNoise(
				ResourceKey.create(Registries.NOISE, ResourceLocation.parse(key))));
	}

	// ---- parser (deepslate DensityFunction.fromJson vocabulary subset) ----

	Node parse(JsonElement el) {
		if (el.isJsonPrimitive()) {
			if (el.getAsJsonPrimitive().isString()) {
				return ref(el.getAsString());
			}
			double v = el.getAsDouble();
			return (x, z) -> v;
		}
		JsonObject o = el.getAsJsonObject();
		String type = o.get("type").getAsString();
		String plain = type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type;
		switch (plain) {
			case "flat_cache", "cache_2d", "cache_once", "cache_all_in_cell": {
				// pass-through: root coordinates are quart-aligned by the caller's contract, so
				// deepslate's flat_cache quantization is the identity here (class doc)
				return parse(o.get("argument"));
			}
			case "add", "mul", "min", "max": {
				Node a = parse(o.get("argument1"));
				Node b = parse(o.get("argument2"));
				return switch (plain) {
					case "add" -> (x, z) -> a.at(x, z) + b.at(x, z);
					case "mul" -> (x, z) -> { // deepslate/vanilla shared zero short-circuit
						double v = a.at(x, z);
						return v == 0 ? 0 : v * b.at(x, z);
					};
					case "min" -> (x, z) -> Math.min(a.at(x, z), b.at(x, z));
					default -> (x, z) -> Math.max(a.at(x, z), b.at(x, z));
				};
			}
			case "clamp": {
				Node input = parse(o.get("input"));
				double min = o.get("min").getAsDouble();
				double max = o.get("max").getAsDouble();
				return (x, z) -> Math.max(min, Math.min(max, input.at(x, z)));
			}
			case "spline":
				return parseCubicSpline(o.get("spline"))::compute;
			case "shifted_noise": {
				NormalNoise n = noise(o.get("noise").getAsString());
				Node sx = parse(o.get("shift_x"));
				Node sy = parse(o.get("shift_y"));
				Node sz = parse(o.get("shift_z"));
				double xzScale = o.get("xz_scale").getAsDouble();
				double yScale = o.get("y_scale").getAsDouble();
				return (x, z) -> n.getValue(
						x * xzScale + sx.at(x, z),
						0.0 * yScale + sy.at(x, z),
						z * xzScale + sz.at(x, z));
			}
			case "shift_a": { // sample(x/4, 0, z/4) * 4
				NormalNoise n = noise(o.get("argument").getAsString());
				return (x, z) -> n.getValue(x * 0.25, 0.0, z * 0.25) * 4.0;
			}
			case "shift_b": { // sample(z/4, x/4, 0) * 4
				NormalNoise n = noise(o.get("argument").getAsString());
				return (x, z) -> n.getValue(z * 0.25, x * 0.25, 0.0) * 4.0;
			}
			case "archean_rise:voronoi":
				return parseVoronoi(o);
			case "archean_rise:ridged_multifractal":
				return parseRidged(o);
			case "archean_rise:river_carve":
				// R2b: the carve DF (both modes) is IDENTITY for the graph/natural-surface evaluation.
				// The graph is built on the NATURAL surface (the carve's argument), and only the
				// game's terrain applies the real carve — so treating it as pass-through here breaks
				// the otherwise-circular dependency (graph ← offset ← river_carve ← graph). See doc 14
				// R2b + RiverCarveDensityFunction. offset.json is now river_carve(natural offset), so
				// the sampler that FEEDS the graph must skip the wrapper it would otherwise recurse into.
				return parse(o.get("argument"));
			default:
				throw new IllegalStateException("PreviewParityEvaluator: unsupported density function type "
						+ type + " — extend the evaluator (and re-run tools/measure/river-parity.mjs)");
		}
	}

	// ---- archean_rise:voronoi (exact VoronoiField/engine.mjs VoronoiDF math) ----

	private Node parseVoronoi(JsonObject o) {
		double cellSize = o.get("cell_size").getAsDouble();
		long salt = o.has("salt") ? o.get("salt").getAsLong() : 0L;
		String output = o.get("output").getAsString();
		Node sx = o.has("shift_x") ? parse(o.get("shift_x")) : null;
		Node sz = o.has("shift_z") ? parse(o.get("shift_z")) : null;
		return (px, pz) -> {
			double x = (px + (sx == null ? 0.0 : sx.at(px, pz))) / cellSize;
			double z = (pz + (sz == null ? 0.0 : sz.at(px, pz))) / cellSize;
			long cx = (long) Math.floor(x);
			long cz = (long) Math.floor(z);
			double f1 = Double.POSITIVE_INFINITY;
			double f2 = Double.POSITIVE_INFINITY;
			double id = 0.0;
			for (long dz = -1; dz <= 1; dz++) {
				for (long dx = -1; dx <= 1; dx++) {
					long gx = cx + dx;
					long gz = cz + dz;
					long h = sm64(gx * 0x9e3779b97f4a7c15L ^ gz * 0xc2b2ae3d27d4eb4fL ^ salt);
					double fx = gx + u01(h);
					double fz = gz + u01(sm64(h));
					double d = (fx - x) * (fx - x) + (fz - z) * (fz - z);
					if (d < f1) {
						f2 = f1;
						f1 = d;
						id = u01(sm64(h ^ 0x5bf03635f0a5b1e5L)) * 2.0 - 1.0;
					} else if (d < f2) {
						f2 = d;
					}
				}
			}
			return switch (output) {
				case "f1" -> f1;
				case "edge" -> f2 - f1;
				default -> id;
			};
		};
	}

	private static long sm64(long z) {
		z += 0x9e3779b97f4a7c15L;
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		return z ^ (z >>> 31);
	}

	private static double u01(long h) {
		return (h >>> 11) * 0x1.0p-53;
	}

	// ---- archean_rise:ridged_multifractal (exact RidgedMultifractal/engine.mjs math) ----

	private Node parseRidged(JsonObject o) {
		NormalNoise n = noise(o.get("noise").getAsString());
		int octaves = o.has("octaves") ? o.get("octaves").getAsInt() : 5;
		double lacunarity = o.has("lacunarity") ? o.get("lacunarity").getAsDouble() : 2.0;
		double gain = o.has("gain") ? o.get("gain").getAsDouble() : 0.5;
		double weightGain = o.has("weight_gain") ? o.get("weight_gain").getAsDouble() : 2.0;
		double offset = o.has("offset") ? o.get("offset").getAsDouble() : 1.0;
		double xzScale = o.get("xz_scale").getAsDouble();
		Node sx = o.has("shift_x") ? parse(o.get("shift_x")) : null;
		Node sz = o.has("shift_z") ? parse(o.get("shift_z")) : null;
		return (px, pz) -> {
			double x0 = (px + (sx == null ? 0.0 : sx.at(px, pz))) * xzScale;
			double z0 = (pz + (sz == null ? 0.0 : sz.at(px, pz))) * xzScale;
			double w = 1.0;
			double sum = 0.0;
			double f = 1.0;
			double a = 1.0;
			double norm = 0.0;
			for (int i = 0; i < octaves; i++) {
				double off = i * OCTAVE_DECORRELATE;
				double v = offset - Math.abs(n.getValue(x0 * f + off, 0.0, z0 * f - off));
				v = v > 0.0 ? v * v * w : 0.0;
				w = Math.min(1.0, Math.max(0.0, v * weightGain));
				sum += v * a;
				norm += a;
				f *= lacunarity;
				a *= gain;
			}
			return sum / norm;
		};
	}

	// ---- minecraft:spline with deepslate CubicSpline semantics ----
	// deepslate (node_modules/deepslate/lib/math/CubicSpline.js): locations and derivatives are
	// frounded at parse; numeric point VALUES stay RAW doubles; the coordinate stays a raw
	// double (vanilla casts it to float — the measured divergence); every arithmetic op is
	// computed in double then frounded, which is what the (float) casts below reproduce.

	private interface Spline {
		double compute(double x, double z);
	}

	private Spline parseCubicSpline(JsonElement el) {
		if (el.isJsonPrimitive()) { // deepslate fromJson: number -> Constant(RAW double)
			double v = el.getAsDouble();
			return (x, z) -> v;
		}
		JsonObject o = el.getAsJsonObject();
		Node coordinate = parse(o.get("coordinate"));
		JsonArray points = o.get("points").getAsJsonArray();
		int count = points.size();
		if (count == 0) {
			return (x, z) -> 0;
		}
		float[] locations = new float[count];
		float[] derivatives = new float[count];
		List<Spline> values = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			JsonObject p = points.get(i).getAsJsonObject();
			locations[i] = (float) p.get("location").getAsDouble();        // fround(location)
			values.add(parseCubicSpline(p.get("value")));
			derivatives[i] = p.has("derivative") ? (float) p.get("derivative").getAsDouble() : 0f;
		}
		return (x, z) -> {
			double coordinateValue = coordinate.at(x, z); // RAW double — never cast to float
			int i = binarySearchDs(locations, coordinateValue) - 1;
			int n = locations.length - 1;
			if (i < 0) {
				// fround(values[0](c) + fround(der[0] * fround(coord - loc[0])))
				float d = (float) ((double) derivatives[0] * (double) (float) (coordinateValue - locations[0]));
				return (float) (values.get(0).compute(x, z) + (double) d);
			}
			if (i == n) {
				float d = (float) ((double) derivatives[n] * (double) (float) (coordinateValue - locations[n]));
				return (float) (values.get(n).compute(x, z) + (double) d);
			}
			float loc0 = locations[i];
			float loc1 = locations[i + 1];
			float der0 = derivatives[i];
			float der1 = derivatives[i + 1];
			float den = (float) ((double) loc1 - (double) loc0);            // fround(loc1 - loc0)
			// f = fround(fround(coord - loc0) / den)  — division in DOUBLE, then fround (JS order)
			float f = (float) ((double) (float) (coordinateValue - loc0) / (double) den);
			double val0 = values.get(i).compute(x, z);
			double val1 = values.get(i + 1).compute(x, z);
			float dv = (float) (val1 - val0);                               // fround(val1 - val0)
			float f8 = (float) ((double) (float) ((double) der0 * den) - (double) dv);
			float f9 = (float) ((double) (float) (-(double) der1 * den) + (double) dv);
			float u = (float) (1.0 - (double) f);                           // fround(1 - f)
			float v = (float) ((double) f * (double) u);                    // fround(f * u)
			float t = (float) ((double) v * (double) floatLerp(f, f8, f9)); // fround(v * lerp2)
			return (float) ((double) floatLerp(f, val0, val1) + (double) t); // fround(lerp1 + t)
		};
	}

	/** deepslate floatLerp: fround(b + fround(a * fround(c - b))). */
	private static float floatLerp(double a, double b, double c) {
		float w = (float) (c - b);
		float m = (float) (a * (double) w);
		return (float) (b + (double) m);
	}

	/** deepslate binarySearch(0, len, k -> coordinate < locations[k]) — returns the first true. */
	private static int binarySearchDs(float[] locations, double coordinate) {
		int lo = 0;
		int span = locations.length;
		while (span > 0) {
			int half = span / 2;
			int mid = lo + half;
			if (coordinate < (double) locations[mid]) {
				span = half;
			} else {
				lo = mid + 1;
				span -= half + 1;
			}
		}
		return lo;
	}
}
