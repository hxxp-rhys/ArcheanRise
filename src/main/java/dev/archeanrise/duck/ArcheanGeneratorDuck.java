package dev.archeanrise.duck;

/**
 * "Is this chunk generator Archean Rise's?" — answered from state captured at CONSTRUCTION, so that no
 * other mod can take the answer away from us afterwards.
 *
 * <p><b>Why this exists (v0.3.16).</b> Every Archean Rise worldgen subsystem is gated on
 * {@link dev.archeanrise.sitegrading.SiteGrading#isArcheanGenerator}. Until 0.3.16 that asked the
 * generator for its noise-settings registry key:
 *
 * <pre>{@code   noise.generatorSettings().unwrapKey()
 *        .map(key -> key.location().getNamespace().equals(ArcheanRise.MOD_ID))
 *        .orElse(false);}</pre>
 *
 * That is fragile, because the settings {@code Holder} is a mutable field. <b>Lithostitched</b>
 * (a top-10 worldgen library, required by Terralith, Tectonic, Regions Unexplored, CTOV and ~25 others)
 * rebuilds a generator's {@code NoiseGeneratorSettings} whenever ANY loaded mod ships a
 * {@code lithostitched:add_surface_rule} for the overworld, and writes it back as a <b>keyless</b>
 * holder:
 *
 * <pre>{@code   ((NoiseBasedChunkGeneratorAccessor) generator).setSettings(Holder.direct(rebuilt));}</pre>
 *
 * {@code Holder.Direct.unwrapKey()} returns {@link java.util.Optional#empty()}, so the old check fell to
 * {@code orElse(false)} and <b>every Archean Rise subsystem silently switched off</b> — rivers, structure
 * spacing, the ore rebalance, SiteGrading, the foreign-inset earthwork, biome-border blending and every
 * structure gate — while the terrain still LOOKED correct (the density functions are data, and are not
 * touched). Measured live: with one functionally-inert {@code add_surface_rule} present, ore in the
 * y −96..−160 band fell by ~65% and the {@code river_carve} / {@code Structure spacing} log lines
 * vanished entirely.
 *
 * <p><b>The fix.</b> Vanilla sets {@code settings} in the {@code NoiseBasedChunkGenerator} constructor,
 * and Lithostitched's swap happens much later (at {@code ServerAboutToStart}). So the identity is decided
 * once, at construction, while the holder is still the registry {@code Holder.Reference} carrying our key
 * — and nothing anyone does to the field afterwards can erase it. This also hardens us against ANY other
 * mod that replaces the settings holder, not just Lithostitched.
 *
 * @see dev.archeanrise.ArcheanRise#reportWorldPreset the boot-time self-check that makes a lost identity
 *      fail LOUDLY instead of silently
 */
public interface ArcheanGeneratorDuck {

	/** True iff this generator was constructed with noise settings registered under the Archean Rise namespace. */
	boolean archean_rise$isArcheanGenerator();
}
