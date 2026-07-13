package dev.archeanrise.noise.df;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * archean_rise:voronoi — 2-D cellular (Worley) field over integer cells of {@code cell_size}
 * blocks (docs/research/09 §3.1). Feature points come from splitmix64 of the cell coordinates
 * and {@code salt} (the hash family FloatDespeckle/ForeignInsetGrade use) — no
 * {@code java.util.Random}, no world state. Distances are SQUARED and never square-rooted, so
 * every operation stays in the exactly-rounded IEEE-754 subset. Ties resolve by scan order over
 * the fixed 3×3 neighbourhood — a total order, fully deterministic.
 *
 * <p>Outputs ({@code output}): {@code f1} = squared distance to the nearest feature point,
 * normalised by cell_size² (range [0, 4.5]); {@code edge} = normalised F2−F1 on the squared
 * distances — a "border proximity" field, 0 on cell borders, rising into cell interiors (the
 * crest/crack network when inverted; range [0, 4.5]); {@code cell_id} = per-cell uniform in
 * [−1, 1] (the plate-uplift source).
 *
 * <p>The field is Y-independent (2-D — the anisotropy ban) and seed-independent in cell space:
 * per-world variety comes from seeded upstream inputs (doc 09 §4.1) — but NOTE the folding
 * constraint: a shift field whose gradient exceeds ~0.12·cell_size makes the sampling map
 * non-injective and collapses the cellular structure (measured 2026-07-10), so the live
 * pipeline samples the province Voronoi UNWARPED. {@code salt} decorrelates multiple voronoi
 * layers within one world.
 */
public record VoronoiField(double cellSize, int salt, Output output,
		DensityFunction shiftX, DensityFunction shiftZ) implements DensityFunction {

	public enum Output implements StringRepresentable {
		F1("f1"), EDGE("edge"), CELL_ID("cell_id");

		private final String name;

		Output(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public static final MapCodec<VoronoiField> DATA_CODEC = RecordCodecBuilder.mapCodec(instance ->
			instance.group(
					Codec.doubleRange(1.0, 65536.0).fieldOf("cell_size").forGetter(VoronoiField::cellSize),
					Codec.INT.optionalFieldOf("salt", 0).forGetter(VoronoiField::salt),
					StringRepresentable.fromEnum(Output::values).fieldOf("output").forGetter(VoronoiField::output),
					// optional BLOCK-unit horizontal displacement (the shared warp trees) — the field
					// is sampled at (x+shift_x, z+shift_z); Y never shifts (anisotropy ban)
					DensityFunction.HOLDER_HELPER_CODEC.optionalFieldOf("shift_x",
							net.minecraft.world.level.levelgen.DensityFunctions.zero()).forGetter(VoronoiField::shiftX),
					DensityFunction.HOLDER_HELPER_CODEC.optionalFieldOf("shift_z",
							net.minecraft.world.level.levelgen.DensityFunctions.zero()).forGetter(VoronoiField::shiftZ)
			).apply(instance, VoronoiField::new));
	public static final KeyDispatchDataCodec<VoronoiField> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

	/** splitmix64 — same construction as FloatDespeckle's positional hash (kept self-contained;
	 * both must never change, or worlds change). */
	private static long splitmix64(long z) {
		z += 0x9e3779b97f4a7c15L;
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		return z ^ (z >>> 31);
	}

	/** Uniform double in [0,1) from the top 53 bits — exact int→double conversion. */
	private static double u01(long h) {
		return (h >>> 11) * 0x1.0p-53;
	}

	@Override
	public double compute(FunctionContext context) {
		final double x = (context.blockX() + shiftX.compute(context)) / cellSize;
		final double z = (context.blockZ() + shiftZ.compute(context)) / cellSize;
		final long cx = (long) Math.floor(x);
		final long cz = (long) Math.floor(z);
		double f1 = Double.POSITIVE_INFINITY;
		double f2 = Double.POSITIVE_INFINITY;
		double id = 0.0;
		for (long dz = -1; dz <= 1; dz++) {
			for (long dx = -1; dx <= 1; dx++) {
				final long gx = cx + dx;
				final long gz = cz + dz;
				final long h = splitmix64(gx * 0x9e3779b97f4a7c15L ^ gz * 0xc2b2ae3d27d4eb4fL ^ (long) salt);
				final double px = gx + u01(h);
				final double pz = gz + u01(splitmix64(h));
				final double d = (px - x) * (px - x) + (pz - z) * (pz - z); // squared, cell units
				if (d < f1) {
					f2 = f1;
					f1 = d;
					id = u01(splitmix64(h ^ 0x5bf03635f0a5b1e5L)) * 2.0 - 1.0;
				} else if (d < f2) {
					f2 = d;
				}
			}
		}
		return switch (output) {
			case F1 -> f1;
			case EDGE -> f2 - f1;
			case CELL_ID -> id;
		};
	}

	@Override
	public void fillArray(double[] array, ContextProvider contextProvider) {
		contextProvider.fillAllDirectly(array, this);
	}

	@Override
	public DensityFunction mapAll(Visitor visitor) {
		return visitor.apply(new VoronoiField(cellSize, salt, output,
				shiftX.mapAll(visitor), shiftZ.mapAll(visitor)));
	}

	@Override
	public double minValue() {
		return output == Output.CELL_ID ? -1.0 : 0.0;
	}

	@Override
	public double maxValue() {
		// Squared-distance bound in cell units: the nearest feature point of the 3×3 scan is at
		// most ~1.5·√2 cells away → d ≤ 4.5. EDGE = f2−f1 ≤ f2 ≤ 4.5. CELL_ID ∈ [−1,1].
		return output == Output.CELL_ID ? 1.0 : 4.5;
	}

	@Override
	public KeyDispatchDataCodec<? extends DensityFunction> codec() {
		return CODEC;
	}
}
