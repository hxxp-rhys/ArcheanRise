package dev.archeanrise.worldgen;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Biome-border blend (issue 5) — WARP method. Returns a copy of a {@link Climate.Sampler} whose
 * temperature and humidity axes are domain-warped ({@link WarpedClimate}) so biome-selection borders
 * finger/interlock instead of forming straight lines, softening the "abrupt cutoff". The other four
 * climate axes ({@code continentalness}/{@code erosion}/{@code depth}/{@code weirdness}) and the
 * {@code spawnTarget} pass through UNCHANGED, so terrain stays byte-identical and coastlines/rivers do
 * not decouple from the biome layout.
 *
 * <p>Strength is the config knob {@code biomeBorderBlend} (0..24, 0 = identity passthrough). Amplitude
 * and wavelength scale with the world's biome size ({@code Sh}) so the visual fingering stays a
 * biome-fraction. All magnitude constants are {@code [JUDGMENT]} starting points that want in-game
 * tuning (the knob is the user-facing dial); they are deliberately kept below the domain-warp folding
 * limit.
 */
public final class BiomeBorderBlend {

	/** Seeded 2-D warp noise (data/archean_rise/worldgen/noise/biome_border_warp.json). */
	public static final ResourceKey<NormalNoise.NoiseParameters> BORDER_WARP = ResourceKey.create(
			Registries.NOISE, ResourceLocation.fromNamespaceAndPath(ArcheanRise.MOD_ID, "biome_border_warp"));

	/** Static-world biome-size factor Sh — keep in sync with generate-worldgen.mjs / ArcheanRise. */
	private static final double SH = 12.0;
	/**
	 * Warp WORLD wavelength (blocks) per unit Sh. Must be a biome-FRACTION so the warp varies LOCALLY
	 * (borders finger/interlock) instead of near-uniformly offsetting the whole biome pattern relative
	 * to the coastlines (the "24 breaks / 16 partly works" symptom of a too-coarse warp). [JUDGMENT]
	 */
	private static final double WAVELENGTH_PER_SH = 150.0;
	/**
	 * Max displacement as a FRACTION of the warp wavelength at knob 24 — the border wiggle depth. Kept
	 * below the ~0.16 injective/folding limit (× the noise's slope) so the full 0..24 range stays
	 * finger-not-fold at any biome scale (the gradient is scale-independent by construction). [JUDGMENT]
	 */
	private static final double MAX_DISPLACE_FRACTION = 0.12;
	/** Dominant wavelength (blocks) of the biome_border_warp noise (firstOctave -8). */
	private static final double NOISE_WAVELENGTH = 256.0;

	private BiomeBorderBlend() {}

	/** Clamped config knob (0..24), or 0 when config is unavailable. */
	public static int knob() {
		int k = ArcheanRise.config == null ? 0 : ArcheanRise.config.biomeBorderBlend;
		return Math.max(0, Math.min(24, k));
	}

	/**
	 * Warp {@code base}'s temperature+humidity for the Archean Rise generator at the given knob, or
	 * return {@code base} unchanged when the blend is off (knob 0). The warp noise is fetched from
	 * {@code randomState} (seeded, cached), so repeated calls for the same (randomState, knob)
	 * produce the identical, seam-free warp everywhere it is applied.
	 */
	public static Climate.Sampler warp(Climate.Sampler base, RandomState randomState, int knob) {
		if (knob <= 0) {
			return base;
		}
		double wavelength = WAVELENGTH_PER_SH * SH;                            // biome-fraction world wavelength
		double scale = NOISE_WAVELENGTH / wavelength;                          // sample the warp noise at this coord scale
		double amplitude = (knob / 24.0) * MAX_DISPLACE_FRACTION * wavelength; // border wiggle depth (blocks)
		NormalNoise warpNoise = randomState.getOrCreateNoise(BORDER_WARP);
		return new Climate.Sampler(
				new WarpedClimate(base.temperature(), warpNoise, amplitude, scale),
				new WarpedClimate(base.humidity(), warpNoise, amplitude, scale),
				base.continentalness(), base.erosion(), base.depth(), base.weirdness(), base.spawnTarget());
	}
}
