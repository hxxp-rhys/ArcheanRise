package dev.archeanrise.noise.df;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * archean_rise:ridged_multifractal — a TRUE ridged multifractal (docs/research/09 §3.3), unlike
 * vanilla's peaks-and-valleys operator, which folds the already-summed octave noise. Here each
 * octave is folded individually and weighted by the previous octave's value, which is what
 * produces sharp, self-similar, erosion-like crest networks:
 *
 * <pre>
 *   w = 1
 *   for i in 0..octaves-1:
 *     n  = max(0, offset − |noiseᵢ|)   // fold per octave (clamped: a fold never inverts)
 *     n  = n·n·w                        // sharpen (Book of Shaders ridge) + weight
 *     w  = clamp(n·weight_gain, 0, 1)   // rough only where already high — the erosion look
 *     sum += n·gainⁱ
 *   return sum / Σ gainⁱ                // normalised to [0, offset²] exactly
 * </pre>
 *
 * <p>Octave decorrelation: {@code NormalNoise} does not expose its internal octaves, so each
 * conceptual octave is the SAME seeded noise sampled at lacunarity-scaled coordinates plus a
 * large per-octave offset (a standard decorrelation trick). This
 * deviates from doc 09's "independent ImprovedNoise layers" wording with identical math shape;
 * seed-purity and random access come from the holder's {@code RandomState} seeding, exactly like
 * every vanilla noise node.
 *
 * <p>2-D by construction — Y is ignored (the anisotropy ban). All arithmetic is in the
 * exactly-rounded subset plus the underlying noise evaluation.
 */
public record RidgedMultifractal(NoiseHolder noise, int octaves, double lacunarity, double gain,
		double weightGain, double offset, double xzScale,
		DensityFunction shiftX, DensityFunction shiftZ) implements DensityFunction {

	private static final double OCTAVE_DECORRELATE = 1259.0;

	public static final MapCodec<RidgedMultifractal> DATA_CODEC = RecordCodecBuilder.mapCodec(instance ->
			instance.group(
					NoiseHolder.CODEC.fieldOf("noise").forGetter(RidgedMultifractal::noise),
					Codec.intRange(1, 8).optionalFieldOf("octaves", 5).forGetter(RidgedMultifractal::octaves),
					Codec.doubleRange(1.0, 4.0).optionalFieldOf("lacunarity", 2.0).forGetter(RidgedMultifractal::lacunarity),
					Codec.doubleRange(0.1, 1.0).optionalFieldOf("gain", 0.5).forGetter(RidgedMultifractal::gain),
					Codec.doubleRange(0.0, 8.0).optionalFieldOf("weight_gain", 2.0).forGetter(RidgedMultifractal::weightGain),
					Codec.doubleRange(0.1, 4.0).optionalFieldOf("offset", 1.0).forGetter(RidgedMultifractal::offset),
					Codec.doubleRange(0.0, 32.0).fieldOf("xz_scale").forGetter(RidgedMultifractal::xzScale),
					// optional BLOCK-unit horizontal displacement (the shared warp trees); Y never shifts
					DensityFunction.HOLDER_HELPER_CODEC.optionalFieldOf("shift_x",
							net.minecraft.world.level.levelgen.DensityFunctions.zero()).forGetter(RidgedMultifractal::shiftX),
					DensityFunction.HOLDER_HELPER_CODEC.optionalFieldOf("shift_z",
							net.minecraft.world.level.levelgen.DensityFunctions.zero()).forGetter(RidgedMultifractal::shiftZ)
			).apply(instance, RidgedMultifractal::new));
	public static final KeyDispatchDataCodec<RidgedMultifractal> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

	@Override
	public double compute(FunctionContext context) {
		final double x0 = (context.blockX() + shiftX.compute(context)) * xzScale;
		final double z0 = (context.blockZ() + shiftZ.compute(context)) * xzScale;
		double w = 1.0;
		double sum = 0.0;
		double f = 1.0;
		double a = 1.0;
		double norm = 0.0;
		for (int i = 0; i < octaves; i++) {
			final double off = i * OCTAVE_DECORRELATE;
			double n = offset - Math.abs(noise.getValue(x0 * f + off, 0.0, z0 * f - off));
			n = n > 0.0 ? n * n * w : 0.0; // clamped fold: a deep valley contributes 0, never a false crest
			w = Math.min(1.0, Math.max(0.0, n * weightGain));
			sum += n * a;
			norm += a;
			f *= lacunarity;
			a *= gain;
		}
		return sum / norm;
	}

	@Override
	public void fillArray(double[] array, ContextProvider contextProvider) {
		contextProvider.fillAllDirectly(array, this);
	}

	@Override
	public DensityFunction mapAll(Visitor visitor) {
		return visitor.apply(new RidgedMultifractal(visitor.visitNoise(noise), octaves, lacunarity,
				gain, weightGain, offset, xzScale, shiftX.mapAll(visitor), shiftZ.mapAll(visitor)));
	}

	@Override
	public double minValue() {
		return 0.0; // every octave term is clamped ≥ 0
	}

	@Override
	public double maxValue() {
		// Per-octave n ≤ offset² (fold peak, w ≤ 1); the normalised geometric sum therefore
		// cannot exceed offset² — exact, not an estimate.
		return offset * offset;
	}

	@Override
	public KeyDispatchDataCodec<? extends DensityFunction> codec() {
		return CODEC;
	}
}
