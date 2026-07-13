package dev.archeanrise.noise.df;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.archeanrise.rivers.RiverCarve;
import dev.archeanrise.rivers.RiverCarveProvider;
import dev.archeanrise.rivers.ServerRiverSampler;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * {@code archean_rise:river_carve} — the R2b-2 channel carve as a density function (doc 14 §2 R2b;
 * algorithm of record {@code tools/preview/river-carve.mjs}, ported in {@link RiverCarve}). It wraps
 * an {@code argument} density function and, per column, applies the river carve to it:
 *
 * <ul>
 *   <li><b>{@link Mode#CARVE}</b> (default) — {@code argument} is the NATURAL offset. The DF
 *       converts it to a designed height with the pipeline algebra
 *       ({@code SEA + (offset + G_SEA)·128·Sv}, the {@link ServerRiverSampler} constants), carves it
 *       ({@link RiverCarveProvider#carveAt}), and converts the carved height back to offset units
 *       (the exact inverse). Emitted at {@code rise/overworld/offset} wrapping the whole natural
 *       offset tree, so terrain (depth → sloped_cheese → final_density) reads the carved surface.</li>
 *   <li><b>{@link Mode#VALLEY}</b> — {@code argument} is {@code mountainAmp}; the DF multiplies it by
 *       {@link RiverCarveProvider#valleyFactorAt} so ridge relief does not overhang the channel
 *       (spec §7.3). Applied only to the relief-chain copy of mountainAmp; the routing surface still
 *       reads the natural {@code mountainAmp}, so the graph is unaffected.</li>
 *   <li><b>{@link Mode#SINK}</b> (R2b-4e, spec §7.3) — {@code argument} is the NATURAL offset. Inside
 *       the river corridor+halo the DF grades the natural surface DOWN toward the reach water level
 *       (a mask 1.0 in the bed, smoothstep-fading to 0 over the halo). This is the "full relief
 *       corridor suppression": {@code mountain_amp_valley} suppresses only the mountain MASS, leaving
 *       {@code HILL_RELIEF + y_base} to perch the mountainside 45–58 blocks above the perched water —
 *       a sub-grid vertical slot the 4×8 noise cell bridges back to solid. The sink pulls that
 *       perching relief to the water so the carve becomes a WIDE graded valley. It ONLY lowers (never
 *       raises), so pooled/basin reaches are untouched. Wrapped INSIDE the {@code CARVE} wrapper at
 *       {@code rise/overworld/offset}, so terrain reads {@code carve(sink(natural))}.</li>
 * </ul>
 *
 * <p><b>Vanilla safety.</b> When there is no live Archean overworld to bind to
 * ({@link RiverCarveProvider#current()} is {@code null}) the DF returns {@code argument} unchanged,
 * and in {@code CARVE} mode the OUTSIDE zone returns the ORIGINAL {@code argument} value (never a
 * round-tripped one), so every column away from a river is BYTE-IDENTICAL to the pre-R2b offset.
 * The DF only appears in the Archean noise settings, so vanilla-preset worlds never instantiate it;
 * this guard is the belt-and-suspenders second line.
 *
 * <p><b>Parity note.</b> The natural-surface evaluators — {@code ServerRiverSampler}'s
 * {@code PreviewParityEvaluator} (which BUILDS the graph) and the JS preview engine — treat this
 * type as IDENTITY (pass-through to {@code argument}). That is what breaks the otherwise-circular
 * dependency (graph ← offset ← river_carve ← graph): the graph is realized on the natural surface,
 * and only the game's vanilla-DF terrain evaluation applies the real carve.
 *
 * <p>2-D by construction (wrapped in {@code flat_cache}, so evaluated once per quart column); Y is
 * ignored. Immutable; the graph/caches live behind the shared, thread-safe
 * {@link RiverCarveProvider} — no mutable per-call fields here.
 */
public record RiverCarveDensityFunction(DensityFunction argument, Mode mode) implements DensityFunction {

	private static final double VS = 128.0 * ServerRiverSampler.WORLD_SV;
	/** R2b-4e: the SINK grades the corridor natural surface down to {@code reachW + SINK_BANK} — a few
	 *  blocks of dry floodplain above the water, so the carved channel (not the whole valley floor)
	 *  holds the river. Tunable; a pure look knob (no parity/graph impact — SINK is identity in every
	 *  preview/parity path). */
	private static final double SINK_BANK = 3.0;

	/** Which per-column transform to apply to {@code argument}. */
	public enum Mode implements StringRepresentable {
		CARVE("carve"),
		VALLEY("valley"),
		SINK("sink");

		private final String name;

		Mode(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public static final MapCodec<RiverCarveDensityFunction> DATA_CODEC = RecordCodecBuilder.mapCodec(instance ->
			instance.group(
					DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument")
							.forGetter(RiverCarveDensityFunction::argument),
					StringRepresentable.fromEnum(Mode::values).optionalFieldOf("mode", Mode.CARVE)
							.forGetter(RiverCarveDensityFunction::mode)
			).apply(instance, RiverCarveDensityFunction::new));
	public static final KeyDispatchDataCodec<RiverCarveDensityFunction> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

	@Override
	public double compute(FunctionContext context) {
		double a = argument.compute(context);
		RiverCarveProvider provider = RiverCarveProvider.current();
		if (provider == null) {
			return a; // no live Archean overworld to bind to — byte-exact no-op (vanilla safety)
		}
		int x = context.blockX();
		int z = context.blockZ();
		if (mode == Mode.VALLEY) {
			return a * provider.valleyFactorAt(x, z); // == a away from rivers (factor is exactly 1.0)
		}
		if (mode == Mode.SINK) {
			// R2b-4e full-relief corridor suppression: grade the perching mountainside down toward the
			// reach water level over the corridor+halo, EXACT no-op away from rivers, ONLY lowering.
			// Height-free geometry (stable natural 0.0), exactly like valleyFactorAt's carveAt call.
			RiverCarve.Result r = provider.carveAt(x, z, 0.0);
			if (r.zone == RiverCarve.Zone.OUTSIDE) {
				return a; // EXACT passthrough → byte-identical terrain away from rivers
			}
			double mask;
			if (r.zone == RiverCarve.Zone.BED || r.zone == RiverCarve.Zone.LAKE) {
				mask = 1.0;
			} else {
				double t = (r.d - r.width / 2) / RiverCarve.VALLEY_HALO;
				double f = t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t); // = the bank blend smoothstep
				mask = 1.0 - f; // 1.0 at the bed edge → 0.0 at the halo outer edge
			}
			double targetOffset =
					(r.reachW + SINK_BANK - ServerRiverSampler.SEA_LEVEL) / VS - ServerRiverSampler.G_SEA;
			if (targetOffset >= a) {
				return a; // natural already at/below the valley floor (pooled/basin reach) — never raise
			}
			return a + (targetOffset - a) * mask;
		}
		double naturalHeight = ServerRiverSampler.SEA_LEVEL + (a + ServerRiverSampler.G_SEA) * VS;
		RiverCarve.Result r = provider.carveAt(x, z, naturalHeight);
		if (r.zone == RiverCarve.Zone.OUTSIDE) {
			return a; // EXACT passthrough — no offset→height→offset round-trip drift away from rivers
		}
		return (r.height - ServerRiverSampler.SEA_LEVEL) / VS - ServerRiverSampler.G_SEA;
	}

	@Override
	public void fillArray(double[] array, ContextProvider contextProvider) {
		contextProvider.fillAllDirectly(array, this);
	}

	@Override
	public DensityFunction mapAll(Visitor visitor) {
		// MUST descend into argument so its noises get seeded by the RandomState visitor.
		return visitor.apply(new RiverCarveDensityFunction(argument.mapAll(visitor), mode));
	}

	@Override
	public double minValue() {
		// VALLEY: argument·[0,1] can reach 0. CARVE: the bed only ever LOWERS the surface, and only
		// on land (bed ≥ ~57 blocks ≫ the natural ocean-floor minimum), so argument's own floor holds.
		// SINK: lowers toward the reach water level (≫ the ocean-floor minimum), reaching argument's own
		// floor at OUTSIDE columns where it is the identity — so argument's floor holds too.
		return mode == Mode.VALLEY ? Math.min(0.0, argument.minValue()) : argument.minValue();
	}

	@Override
	public double maxValue() {
		if (mode == Mode.VALLEY) {
			return Math.max(0.0, argument.maxValue()); // argument·[0,1] ≤ max(0, argument.max)
		}
		if (mode == Mode.SINK) {
			return argument.maxValue(); // SINK only LOWERS the surface — never above natural
		}
		// CARVE: the bank levee raises the surface by at most LEVEE_H blocks above natural.
		return argument.maxValue() + RiverCarve.LEVEE_H / VS;
	}

	@Override
	public KeyDispatchDataCodec<? extends DensityFunction> codec() {
		return CODEC;
	}
}
