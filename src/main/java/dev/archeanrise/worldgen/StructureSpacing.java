package dev.archeanrise.worldgen;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.mixin.ChunkGeneratorStructureStateAccessor;
import dev.archeanrise.mixin.StructurePlacementAccessor;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ss structure-spacing scaler (issue 3 / real-scale-design.md §4, §5.5). At {@code ChunkGenerator.
 * createState} RETURN, on the Archean Rise static-world generator, rescale every {@code random_spread}
 * structure set's spacing/separation by Ss so ALL structures — vanilla AND add-on mods — spread
 * farther apart in the enlarged world (biomes are 12× vanilla; vanilla-density villages would read
 * as suburbs). This is the one scale-aware treatment add-on structures receive (SiteGrading excludes
 * them by structure class; this operates on the structure-SET placement generically, so any mod's
 * {@code random_spread} set is covered).
 *
 * <p><b>Scope / safety.</b> Per-generator = per-dimension: the Nether/End (and non-Archean) generators
 * fail {@link #appliesTo}, so they keep vanilla spacing, and {@code /locate} + explorer maps + villager
 * trades stay consistent because they all read this same per-generator state. Immutable {@code
 * Holder.direct} copies are built — the shared registry entries are never mutated (thread-safe;
 * matches terrain-generation.md §8.2). EXACT-class {@code RandomSpreadStructurePlacement} only, so:
 * <ul>
 *   <li>concentric-rings placements (strongholds) are passed through — spacing must not move;</li>
 *   <li>a mod's RandomSpread <i>subclass</i> (e.g. an enhanced-spread type) is passed through, never
 *       sliced down to a vanilla copy;</li>
 *   <li>frequency-driven sets with {@code spacing<=1} (mineshafts, buried treasure) are passed through
 *       so a spacing of 1 is never inflated.</li>
 * </ul>
 * Exclusion zones are remapped (two-pass) so an outpost that avoids villages keeps avoiding them at
 * their <i>scaled</i> positions.
 */
public final class StructureSpacing {

	/**
	 * Structure-spacing factor Ss (real-scale-design.md §4/§7.2). 3.00 is the design doc's UX
	 * ceiling — village-to-village stays a ~5-minute sprint worst case — and matches the Sh=12
	 * biome ceiling the static world holds (docs/DECISIONS.md 2026-07-09 pivot). Single source of
	 * truth for the runtime scaler.
	 */
	private static final double SS = 3.00;

	private StructureSpacing() {}

	public static boolean enabled() {
		return ArcheanRise.config == null || ArcheanRise.config.scaleStructureSpacing;
	}

	/**
	 * True only for the Archean Rise static-world generator (noise-settings key
	 * {@code archean_rise:rise}); false for the Nether/End, non-Archean generators, and anything
	 * unrecognised (including a pinned pre-0.3.0 tier datapack, which is unsupported).
	 */
	public static boolean appliesTo(ChunkGenerator generator) {
		if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
			return false;
		}
		return noise.generatorSettings().unwrapKey()
				.map(key -> key.location().getNamespace().equals(ArcheanRise.MOD_ID)
						&& key.location().getPath().equals("rise"))
				.orElse(false);
	}

	/** Rescale the state's structure sets in place (idempotent per state; runs once at createState). */
	public static void rescale(ChunkGeneratorStructureState state) {
		double ss = SS;
		List<Holder<StructureSet>> original = state.possibleStructureSets();

		// Pass 1 — scale spacing/separation only (exclusion zones untouched here). Identity-map each
		// ORIGINAL set value to (a) its scaled set and (b) the Holder to use as an exclusion target
		// (the scaled spacing is exactly what the exclusion range check reads).
		Map<StructureSet, StructureSet> scaledByOriginal = new IdentityHashMap<>();
		Map<StructureSet, Holder<StructureSet>> holderByOriginal = new IdentityHashMap<>();
		for (Holder<StructureSet> h : original) {
			StructureSet scaled = scaleSpacing(h.value(), ss);
			scaledByOriginal.put(h.value(), scaled);
			holderByOriginal.put(h.value(), scaled == h.value() ? h : Holder.direct(scaled));
		}

		// Pass 2 — remap each scaled set's exclusion zone to the scaled OTHER set, then assemble the
		// final holder list. changed stays false only if nothing scaled (then leave the state alone).
		List<Holder<StructureSet>> result = new ArrayList<>(original.size());
		boolean changed = false;
		for (Holder<StructureSet> h : original) {
			StructureSet scaled = scaledByOriginal.get(h.value());
			StructureSet remapped = remapExclusion(scaled, holderByOriginal);
			if (remapped == h.value()) {
				result.add(h); // fully unchanged
			} else if (remapped == scaled) {
				result.add(holderByOriginal.get(h.value())); // spacing changed, no exclusion remap
				changed = true;
			} else {
				result.add(Holder.direct(remapped)); // spacing AND exclusion changed
				changed = true;
			}
		}
		if (changed) {
			((ChunkGeneratorStructureStateAccessor) state)
					.archean_rise$setPossibleStructureSets(List.copyOf(result));
			ArcheanRise.LOGGER.info("Structure spacing: Ss {}x rescaled {} of {} structure set(s)",
					ss, result.size() - countUnchanged(original, scaledByOriginal), original.size());
		}
	}

	private static int countUnchanged(List<Holder<StructureSet>> original,
			Map<StructureSet, StructureSet> scaledByOriginal) {
		int n = 0;
		for (Holder<StructureSet> h : original) {
			if (scaledByOriginal.get(h.value()) == h.value()) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Return {@code set} with spacing/separation ×Ss, or the SAME instance when it must not scale:
	 * not exact {@code RandomSpreadStructurePlacement}, a {@code spacing<=1} frequency set, or no growth.
	 */
	private static StructureSet scaleSpacing(StructureSet set, double ss) {
		StructurePlacement p = set.placement();
		if (p.getClass() != RandomSpreadStructurePlacement.class) {
			return set; // concentric rings, or a mod's RandomSpread subclass — never slice/move
		}
		RandomSpreadStructurePlacement r = (RandomSpreadStructurePlacement) p;
		if (r.spacing() <= 1) {
			return set; // frequency-driven (mineshaft / buried treasure) — do not inflate spacing 1
		}
		int spacing = (int) Math.round(r.spacing() * ss);
		if (spacing <= r.spacing()) {
			return set; // rounding produced no growth
		}
		int separation = Math.min(Math.max(0, (int) Math.round(r.separation() * ss)), spacing - 1);
		StructurePlacementAccessor base = (StructurePlacementAccessor) p;
		RandomSpreadStructurePlacement scaled = new RandomSpreadStructurePlacement(
				base.archean_rise$locateOffset(), base.archean_rise$frequencyReductionMethod(),
				base.archean_rise$frequency(), base.archean_rise$salt(), base.archean_rise$exclusionZone(),
				spacing, separation, r.spreadType());
		return new StructureSet(set.structures(), scaled);
	}

	/**
	 * If {@code set}'s exclusion zone references another set that was scaled, rebuild the placement so
	 * the zone points at the scaled holder (else the buffer would be measured against the OTHER set's
	 * old, unscaled positions). {@code chunkCount} is left as-authored (a fixed N-chunk buffer around
	 * each — now rarer — structure stays correct; not scaled, so it stays within the vanilla 1..16
	 * range). Returns the same instance when there is nothing to remap.
	 */
	private static StructureSet remapExclusion(StructureSet set,
			Map<StructureSet, Holder<StructureSet>> holderByOriginal) {
		StructurePlacement p = set.placement();
		if (!(p instanceof RandomSpreadStructurePlacement r)) {
			return set;
		}
		StructurePlacementAccessor base = (StructurePlacementAccessor) p;
		Optional<StructurePlacement.ExclusionZone> ezo = base.archean_rise$exclusionZone();
		if (ezo.isEmpty()) {
			return set;
		}
		StructurePlacement.ExclusionZone ez = ezo.get();
		Holder<StructureSet> scaledOther = holderByOriginal.get(ez.otherSet().value());
		if (scaledOther == null || scaledOther == ez.otherSet()) {
			return set; // other set absent here, or itself unscaled — nothing to remap
		}
		StructurePlacement.ExclusionZone newZone =
				new StructurePlacement.ExclusionZone(scaledOther, ez.chunkCount());
		RandomSpreadStructurePlacement rebuilt = new RandomSpreadStructurePlacement(
				base.archean_rise$locateOffset(), base.archean_rise$frequencyReductionMethod(),
				base.archean_rise$frequency(), base.archean_rise$salt(), Optional.of(newZone),
				r.spacing(), r.separation(), r.spreadType());
		return new StructureSet(set.structures(), rebuilt);
	}
}
