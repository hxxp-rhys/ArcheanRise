package dev.archeanrise.rivers;

import net.minecraft.server.level.ServerLevel;

/**
 * The in-game {@link RiverGraph.FieldSampler}: evaluates the generator-emitted routing surface
 * ({@code archean_rise:rise/rivers/routing}) and the designed-surface offset pipeline
 * ({@code archean_rise:rise/overworld/offset}) for the live world's seed, BIT-COMPATIBLY with the
 * JS preview engine ({@code tools/preview/engine.mjs}) that the R1 river reference runs on.
 *
 * <p>Both trees are evaluated through {@link PreviewParityEvaluator} — raw datapack JSON with the
 * preview engine's exact numeric semantics — rather than the parsed vanilla
 * {@code DensityFunction} instances. That is a MEASURED deviation from doc 14 §0's "through
 * vanilla DF machinery" expectation: the first R2a parity run (seed 424242, 3×3 cells) showed
 * vanilla's {@code minecraft:spline} differs from deepslate by ~1 float ulp (float-cast
 * coordinate, float-parsed point values — see the evaluator's javadoc), which the byte-identical
 * gate cannot admit. Noise seeding still goes through the level's {@code RandomState}
 * ({@code getOrCreateNoise}) and vanilla {@code NormalNoise} math, so the seed contract is the
 * game's own. The graph this feeds is self-consistent and seed-pure; in-game terrain evaluation
 * is untouched.
 *
 * <p><b>The quart contract</b> (deepslate {@code flat_cache} root quantization —
 * {@code x >> 2 << 2} with ToInt32 truncation, y forced to 0):
 * <ul>
 *   <li>{@link #routingAt} is only ever called at 64-block-aligned coordinates (the memo grid in
 *       {@link RiverGraph}), where quantization is the identity — evaluate directly;</li>
 *   <li>{@link #designedSurfaceAt} receives arbitrary double coordinates (river node positions)
 *       and applies the root quantization itself: {@code (((int) c) >> 2) << 2} — Java's
 *       double→int cast truncates toward zero exactly like ToInt32 for in-world magnitudes.</li>
 * </ul>
 *
 * <p><b>Height algebra:</b> the designed surface is {@code SEA + (offset + G_SEA) · 128 · Sv}
 * (pipeline-core.mjs {@code makeAlgebra}/{@code heightAt}) with the SAME operation order. Keep
 * {@link #G_SEA}/{@link #WORLD_SV}/{@link #SEA_LEVEL} in sync with tools/pipeline-core.mjs.
 */
public final class ServerRiverSampler implements RiverGraph.FieldSampler {
	/** pipeline-core.mjs G_SEA (offset-unit algebra anchor). Public: the {@code river_carve} DF
	 * ({@link dev.archeanrise.noise.df.RiverCarveDensityFunction}) does the SAME offset↔height
	 * conversion and must use the IDENTICAL constants — this class is the single source of truth. */
	public static final double G_SEA = 0.5078125;
	/** pipeline-core.mjs DEFAULT_WORLD.s (Sv vertical envelope). */
	public static final double WORLD_SV = 3.32;
	/** pipeline-core.mjs DEFAULT_TC.SEA_LEVEL. */
	public static final double SEA_LEVEL = 63;

	private static final String ROUTING_ID = "archean_rise:rise/rivers/routing";
	private static final String OFFSET_ID = "archean_rise:rise/overworld/offset";

	private final PreviewParityEvaluator.Node routing;
	private final PreviewParityEvaluator.Node offset;

	private ServerRiverSampler(PreviewParityEvaluator.Node routing, PreviewParityEvaluator.Node offset) {
		this.routing = routing;
		this.offset = offset;
	}

	/**
	 * Build the sampler for a live level, or throw {@link IllegalStateException} with a precise
	 * reason (missing datapack JSON — e.g. a world without the Archean Rise datapack).
	 */
	public static ServerRiverSampler of(ServerLevel level) {
		PreviewParityEvaluator evaluator = new PreviewParityEvaluator(
				level.getServer(), level.getChunkSource().randomState());
		return new ServerRiverSampler(evaluator.ref(ROUTING_ID), evaluator.ref(OFFSET_ID));
	}

	@Override
	public double routingAt(double x, double z) {
		// precondition (RiverGraph memo): x, z are exact multiples of 64 — quart-aligned, so the
		// deepslate flat_cache quantization is the identity and direct evaluation matches it.
		return routing.at(x, z);
	}

	@Override
	public double designedSurfaceAt(double x, double z) {
		// deepslate FlatCache root quantization: ToInt32-truncate, floor to the quart, y = 0
		int qx = (((int) x) >> 2) << 2;
		int qz = (((int) z) >> 2) << 2;
		double o = offset.at(qx, qz);
		return SEA_LEVEL + (o + G_SEA) * 128 * WORLD_SV;
	}
}
