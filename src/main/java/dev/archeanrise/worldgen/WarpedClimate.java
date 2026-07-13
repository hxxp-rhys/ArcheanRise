package dev.archeanrise.worldgen;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Runtime-only wrapper that domain-warps ONE climate axis (temperature or humidity) for the
 * biome-border blend (issue 5). It samples the wrapped {@code base} climate density function at a
 * position displaced by a seeded 2-D warp noise:
 *
 * <pre>value(x,y,z) = base( x + A*warp(x*s, z*s),  y,  z + A*warp(x*s+337, z*s-337) )</pre>
 *
 * Applied to temperature AND humidity with the SAME warp, this fingers/interlocks the biome-selection
 * borders (softening their SHAPE) without touching terrain — temperature/humidity do not feed the
 * terrain density (final_density uses continents/erosion/depth/weirdness), which are left unwarped.
 *
 * <p>NEVER serialized: it is created in code and inserted into the live {@code Climate.Sampler} by
 * {@link BiomeBorderBlend}, so {@link #codec()} throws and {@link #mapAll} is an identity apply — the
 * warp noise is already seeded (from {@code RandomState.getOrCreateNoise}) when this is built. The
 * value range is unchanged (same {@code base}, sampled at a different point), so
 * {@link #minValue()}/{@link #maxValue()} delegate and the engine's range analysis stays valid.
 */
public final class WarpedClimate implements DensityFunction {

	/** Decorrelate the two warp axes by sampling the same noise far apart (standard trick). */
	private static final double DECORRELATE = 337.0;

	private final DensityFunction base;
	private final NormalNoise warp;
	private final double amplitude;
	private final double scale;

	public WarpedClimate(DensityFunction base, NormalNoise warp, double amplitude, double scale) {
		this.base = base;
		this.warp = warp;
		this.amplitude = amplitude;
		this.scale = scale;
	}

	@Override
	public double compute(FunctionContext context) {
		double x = context.blockX();
		double y = context.blockY();
		double z = context.blockZ();
		double dx = amplitude * warp.getValue(x * scale, 0.0, z * scale);
		double dz = amplitude * warp.getValue(x * scale + DECORRELATE, 0.0, z * scale - DECORRELATE);
		return base.compute(new SinglePointContext(
				(int) Math.round(x + dx), (int) Math.round(y), (int) Math.round(z + dz)));
	}

	@Override
	public void fillArray(double[] array, ContextProvider contextProvider) {
		contextProvider.fillAllDirectly(array, this);
	}

	@Override
	public DensityFunction mapAll(Visitor visitor) {
		return visitor.apply(this); // built post-router-construction; base + warp are already resolved/seeded
	}

	@Override
	public double minValue() {
		return base.minValue();
	}

	@Override
	public double maxValue() {
		return base.maxValue();
	}

	@Override
	public KeyDispatchDataCodec<? extends DensityFunction> codec() {
		throw new UnsupportedOperationException(
				"archean_rise:WarpedClimate is a runtime-only biome-border-blend wrapper and is never serialized");
	}
}
