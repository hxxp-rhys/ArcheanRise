package dev.archeanrise.noise.df;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.archeanrise.rivers.RiverCarveProvider;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * {@code archean_rise:river_water} — the R2c water-fill masks that drive the vanilla aquifer's
 * "stepped elevated pools" trio (doc 11 §5a; the load-bearing mechanism in
 * {@code docs/research/10-river-mechanics-research.md} §1.2, read from decompiled 1.21.1
 * {@code Aquifer}/{@code NoiseChunk}). Each mode is a per-column read of the SAME river graph +
 * carve the R2b {@code river_carve} DF uses, exposed through {@link RiverCarveProvider}:
 *
 * <ul>
 *   <li><b>{@link Mode#WATER_LEVEL}</b> — the reach water surface Y (L) inside corridor+apron,
 *       {@link RiverCarveProvider#NO_RIVER} outside (a sentinel the router's Y-gates clamp to
 *       "dry"). The graph's L, NOT the aquifer's ≡20-mod-40 grid.</li>
 *   <li><b>{@link Mode#CORRIDOR_MASK}</b> — 1 in the bed/basin, smoothstep-fading over the halo, 0
 *       outside. Scopes the {@code fluid_level_floodedness} in-band (0.62) lift and the
 *       {@code fluid_level_spread} target.</li>
 *   <li><b>{@link Mode#APRON_MASK}</b> — the corridor EXTENDED by the aquifer's 13-sample
 *       preliminary-surface clamp geometry (x −48..+16, z ±16). Scopes the
 *       {@code initial_density_without_jaggedness} prelim raise (so upstream reaches over low
 *       ground are not read DRY — the hard-won Stage-B lesson) and the dry-apron floodedness push.</li>
 *   <li><b>{@link Mode#SPREAD}</b> — wraps the vanilla {@code fluid_level_spread} noise. The aquifer
 *       samples spread at COMPRESSED fluid-cell coords {@code (floorDiv(x,16), floorDiv(y,40),
 *       floorDiv(z,16))} (verified in 1.21.1 {@code Aquifer.computeRandomizedFluidSurfaceLevel}), so
 *       this mode DECODES them and returns {@code (L − (cellY·40 + 20)) / 10} inside the corridor —
 *       making the aquifer level {@code cellBase + quantize(spread·10, 3) ≈ L}. Passes the vanilla
 *       spread through everywhere else. This is the ONE slot that cannot be composed in JSON (the
 *       coordinate decode needs code); the other three are pure 2-D reads composed in the router.</li>
 * </ul>
 *
 * <p><b>Vanilla safety.</b> When there is no live Archean overworld to bind to
 * ({@link RiverCarveProvider#current()} is {@code null}) every mask returns its neutral value
 * (WATER_LEVEL → {@code NO_RIVER}; the masks → 0; SPREAD → {@code argument}), so no aquifer behavior
 * changes. The type only ever appears in the {@code rise} noise settings, so vanilla-preset worlds
 * never instantiate it — this guard is the belt-and-suspenders second line, the same discipline
 * {@code river_carve} proved.
 *
 * <p>Immutable; the graph/caches live behind the shared, thread-safe {@link RiverCarveProvider}.
 * {@code argument} is only meaningful for {@link Mode#SPREAD}; the masks default it to the constant
 * {@code 0} and never read it.
 */
public record RiverWaterDensityFunction(DensityFunction argument, Mode mode) implements DensityFunction {

	/** Which per-column river-water field this instance evaluates. */
	public enum Mode implements StringRepresentable {
		WATER_LEVEL("water_level"),
		CORRIDOR_MASK("corridor_mask"),
		APRON_MASK("apron_mask"),
		SPREAD("spread");

		private final String name;

		Mode(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public static final MapCodec<RiverWaterDensityFunction> DATA_CODEC = RecordCodecBuilder.mapCodec(instance ->
			instance.group(
					// argument is used ONLY by SPREAD (the vanilla-spread fallback); the masks ignore it
					DensityFunction.HOLDER_HELPER_CODEC.optionalFieldOf("argument", DensityFunctions.zero())
							.forGetter(RiverWaterDensityFunction::argument),
					StringRepresentable.fromEnum(Mode::values).fieldOf("mode")
							.forGetter(RiverWaterDensityFunction::mode)
			).apply(instance, RiverWaterDensityFunction::new));
	public static final KeyDispatchDataCodec<RiverWaterDensityFunction> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

	@Override
	public double compute(FunctionContext context) {
		RiverCarveProvider provider = RiverCarveProvider.current();
		if (provider == null) {
			// no live Archean overworld — byte-exact no-op (vanilla safety)
			return switch (mode) {
				case WATER_LEVEL -> RiverCarveProvider.NO_RIVER;
				case CORRIDOR_MASK, APRON_MASK -> 0.0;
				case SPREAD -> argument.compute(context);
			};
		}
		return switch (mode) {
			case WATER_LEVEL -> provider.waterLevelAt(context.blockX(), context.blockZ());
			case CORRIDOR_MASK -> provider.corridorMaskAt(context.blockX(), context.blockZ());
			case APRON_MASK -> provider.apronMaskAt(context.blockX(), context.blockZ());
			case SPREAD -> {
				// COMPRESSED fluid-cell coords: (o, p, q) = (floorDiv(x,16), floorDiv(y,40), floorDiv(z,16)).
				// Decode x/z to the cell centre to read L; cellY = p sets the aquifer cell base.
				int cellX = context.blockX();
				int cellY = context.blockY();
				int cellZ = context.blockZ();
				double level = provider.waterLevelAt(cellX * 16 + 8, cellZ * 16 + 8);
				if (level <= RiverCarveProvider.NO_RIVER / 2.0) {
					yield argument.compute(context); // outside corridor+apron → vanilla spread
				}
				// The aquifer sets u = cellBase + quantize(spread·10, 3), and Mth.quantize FLOORS —
				// so targeting exactly (L−cellBase)/10 lands the surface up to 3 blocks BELOW L, which
				// eats the whole channel depth (bed = L−depth) and leaves the reach ~dry. Bias by +1.5
				// so the floor rounds to the NEAREST 3-grid step: the water surface lands within ±1.5
				// of L, preserving the carved depth. (Top water block = u−1 ≤ L+0.5 < rim L+1 → still
				// contained.) Verified against decompiled Aquifer.computeRandomizedFluidSurfaceLevel.
				yield (level - (cellY * 40.0 + 20.0) + 1.5) / 10.0;
			}
		};
	}

	@Override
	public void fillArray(double[] array, ContextProvider contextProvider) {
		contextProvider.fillAllDirectly(array, this);
	}

	@Override
	public DensityFunction mapAll(Visitor visitor) {
		// MUST descend into argument so the wrapped (SPREAD) noise gets seeded by the RandomState visitor.
		return visitor.apply(new RiverWaterDensityFunction(argument.mapAll(visitor), mode));
	}

	@Override
	public double minValue() {
		return switch (mode) {
			case WATER_LEVEL -> RiverCarveProvider.NO_RIVER;
			case CORRIDOR_MASK, APRON_MASK -> 0.0;
			// SPREAD target (L−cellBase)/10 is O(100); bound generously below the vanilla noise floor
			case SPREAD -> Math.min(argument.minValue(), -1.0e4);
		};
	}

	@Override
	public double maxValue() {
		return switch (mode) {
			case WATER_LEVEL -> 4064.0; // > any in-world reach surface; sentinel handles the low end
			case CORRIDOR_MASK, APRON_MASK -> 1.0;
			case SPREAD -> Math.max(argument.maxValue(), 1.0e4);
		};
	}

	@Override
	public KeyDispatchDataCodec<? extends DensityFunction> codec() {
		return CODEC;
	}
}
