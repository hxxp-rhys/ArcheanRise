package dev.archeanrise.sitegrading;

import dev.archeanrise.ArcheanRise;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

import java.util.Set;

/**
 * SiteGrading — adaptive structure siting for Archean Rise dimensions (see
 * references/ SiteGrading blueprint + docs/DECISIONS.md). Three steps:
 * (1) site check over the structure footprint, (2) deterministic candidate search within a
 * bounded shift budget, (3) terrain re-grade: pieces snap to a slope-limited design surface,
 * a GradePad density term closes bounded gaps at the noise stage, and a foundation fill
 * closes the residue at features stage.
 *
 * Scope guards (compat contract): only in Archean Rise dimensions (generator noise-settings
 * key namespace), only for EXACT vanilla {@link JigsawStructure} type (excludes YUNG's /
 * Lithostitched / coded types so their own adaptation systems are never doubled), only for
 * WORLD_SURFACE_WG-projected structures with terrain adaptation, and only for allowlisted
 * structure ids (villages + pillager outposts by default, config-extensible).
 */
public final class SiteGrading {
	/** Grade budget G: max blocks the design surface may deviate from its anchor plane. */
	public static final int GRADE_BUDGET = 10;
	/**
	 * Sea level — a scale INVARIANT. Terrain scaling is sea-anchored
	 * (y' = 63 + (y-63)*S; DECISIONS.md 2026-07-05 v0.2: "sea level, and therefore coastlines and
	 * structure anchoring, never move"), so 63 holds at any relief scale. The SiteGrading v2 field
	 * water-clamps its apron/natural target to {@code max(predictorFloor, SEA_LEVEL)} so aprons ramp
	 * to the shoreline, not the deep seabed (deep under-footprint fill is Phase 4). Because it is a
	 * compile-time constant it need NOT enter the field LRU cache key; if any gradable dimension ever
	 * has a different sea level, this MUST be promoted into {@code SiteGradeField.key()}.
	 */
	public static final int SEA_LEVEL = 63;
	/**
	 * Max |Δ| between adjacent PITCH-16 veto-envelope nodes (16 blocks apart) — the LOCAL-natural-
	 * roughness gate: a genuine LOCAL CLIFF through the footprint (the "R1" residual the global relief
	 * veto alone misses), the thing that breaks a village even where its overall relief is modest.
	 *
	 * <p><b>= PITCH = 16 as of the 2026-07-06 vanilla-projection fix (was PITCH/2 = 8).</b> The old 8
	 * was the FLAT-PLATFORM bound: it kept the CUT+FILL field's bilinear water-clamped apron block-
	 * level 1-Lipschitz (diagonal per-block step ≤ nodeΔ/8 ≤ 1) so a flat platform's apron could not be
	 * squeezed by locally-steep natural. But the 8-block flat-platform bound no longer governs the veto in
	 * EITHER mode of {@link DesignSurface#clampProjection}: with cutFill OFF (default) pieces RAMP with the
	 * terrain like vanilla, which tolerates rolling ground fine; with cutFill ON the flat-snap grade regrades
	 * roughness up to the depth cap, so the field's {@code infeasible=0} (not roughness ≤ 8) is the
	 * gradeability gate. So the apron-squeeze (R1) derivation no longer applies; the gate's sole remaining job is to reject a genuine cliff (node
	 * step &gt; PITCH ⇒ local slope &gt; 1, steeper than any walkable jigsaw path). At 16 a rolling-hill
	 * village (the common gentle-terrain case, node steps ≤ ~8) survives and is built vanilla-faithfully;
	 * only true escarpments are cut. The two MandR villages stay rejected regardless — by RELIEF
	 * (44–47 &gt; vetoMaxRelief 32), not roughness. Lower it toward 8 only if the flat-platform CUT+FILL
	 * is re-enabled (then the 1-Lipschitz apron guarantee matters again).
	 */
	public static final int VETO_ROUGHNESS_MAX = 16;
	/** GradePad lateral falloff — hard zero at 12 (STRUCTURE_REFERENCES envelope). */
	public static final int PAD_RADIUS = 12;
	/**
	 * Vanilla {@code TerrainAdjustment.BEARD_THIN} adaptation reach (blocks) — the radius vanilla's
	 * own Beardifier blends a structure's box into surrounding terrain. The SiteGrading v2 CUT+FILL
	 * pass treats every FOREIGN start's {@code box} dilated by {@code BEARD_THIN + siteGradingForeignHaloExtra}
	 * as fully off-limits (the tertiary-safety foreign-yield core, blueprint §3.4/§5): it protects the
	 * neighbour's own beard-blended out-of-box terrain and its entrance-approach columns. Horizontal.
	 */
	public static final int BEARD_THIN = 12;
	/** Max anchor shift; also re-capped per structure vs the 128-block reference envelope. */
	public static final int MAX_SHIFT = 32;

	private static final Set<String> DEFAULT_ALLOWLIST = Set.of(
			"minecraft:village_plains", "minecraft:village_desert", "minecraft:village_savanna",
			"minecraft:village_snowy", "minecraft:village_taiga", "minecraft:pillager_outpost");

	private SiteGrading() {}

	public static boolean enabled() {
		return ArcheanRise.config == null || ArcheanRise.config.siteGradingEnabled;
	}

	/**
	 * The master gate: is this generator ours? EVERY Archean Rise worldgen subsystem hangs off this —
	 * rivers, structure spacing, the ore rebalance, SiteGrading, the foreign-inset earthwork, biome-border
	 * blending, and every structure gate.
	 *
	 * <p><b>Do NOT re-derive this from {@code generatorSettings().unwrapKey()} (v0.3.16).</b> That is what
	 * this used to do, and it was a silent single point of failure: the settings {@code Holder} is a mutable
	 * field, and <b>Lithostitched</b> — required by Terralith, Tectonic, Regions Unexplored, CTOV and ~25
	 * other mods — rebuilds it and writes back a KEYLESS {@code Holder.direct(...)} whenever any loaded mod
	 * ships a {@code lithostitched:add_surface_rule} for the overworld. {@code unwrapKey()} then returns
	 * empty, the check fell to {@code orElse(false)}, and Archean Rise switched itself off entirely while
	 * the terrain still looked perfectly correct. Measured: ~65% of ore in the y −96..−160 band gone, and
	 * the river graph and structure-spacing rescale never built at all.
	 *
	 * <p>The identity is now captured at generator CONSTRUCTION — before any mod can reach the field — and
	 * simply read back here. See {@link dev.archeanrise.duck.ArcheanGeneratorDuck}. A boot-time self-check
	 * in {@link dev.archeanrise.ArcheanRise} makes a lost identity fail LOUDLY rather than silently.
	 */
	public static boolean isArcheanGenerator(ChunkGenerator generator) {
		return generator instanceof dev.archeanrise.duck.ArcheanGeneratorDuck duck
				&& duck.archean_rise$isArcheanGenerator();
	}

	/**
	 * Full allowlist predicate. Exact-type check is deliberate: subclasses (yungsapi,
	 * lithostitched) run their own placement/adaptation and must never get partial treatment.
	 */
	public static boolean isGradable(Structure structure, net.minecraft.core.RegistryAccess registryAccess) {
		if (structure.getClass() != JigsawStructure.class) {
			return false;
		}
		if (structure.terrainAdaptation() == TerrainAdjustment.NONE) {
			return false;
		}
		JigsawStructure jigsaw = (JigsawStructure) structure;
		if (((dev.archeanrise.mixin.JigsawStructureAccessor) (Object) jigsaw)
				.archean_rise$getProjectStartToHeightmap()
				.filter(type -> type == Heightmap.Types.WORLD_SURFACE_WG).isEmpty()) {
			return false;
		}
		ResourceLocation id = registryAccess.registryOrThrow(Registries.STRUCTURE).getKey(structure);
		if (id == null) {
			return false;
		}
		if (DEFAULT_ALLOWLIST.contains(id.toString())) {
			return true;
		}
		return matchesExtras(id);
	}

	/** Single source of truth for extras matching — used by both isGradable and reportExtras. */
	private static boolean matchesExtras(ResourceLocation id) {
		return ArcheanRise.config != null
				&& (ArcheanRise.config.siteGradingExtraIds.containsKey(id.toString())
						|| ArcheanRise.config.siteGradingExtraNamespaces.containsKey(id.getNamespace()));
	}

	/**
	 * SERVER_STARTED report: resolve siteGradingExtraStructures against the structure registry
	 * so admins can see what their entries actually matched — exact-id typos, namespaces with
	 * no registered structures (mod missing), and entries whose structures fail the
	 * eligibility gates all get called out instead of failing silently.
	 */
	public static void reportExtras(net.minecraft.server.MinecraftServer server) {
		// Informational only — a defect here must never take a boot down with it.
		try {
			reportExtrasInner(server);
		} catch (Exception e) {
			ArcheanRise.LOGGER.warn(
					"SiteGrading extras report failed (informational only; grading itself is unaffected): {}",
					e.toString());
		}
	}

	private static void reportExtrasInner(net.minecraft.server.MinecraftServer server) {
		if (ArcheanRise.config == null
				|| (ArcheanRise.config.siteGradingExtraIds.isEmpty()
						&& ArcheanRise.config.siteGradingExtraNamespaces.isEmpty())) {
			return;
		}
		net.minecraft.core.RegistryAccess registryAccess = server.registryAccess();
		net.minecraft.core.Registry<Structure> registry =
				registryAccess.registryOrThrow(Registries.STRUCTURE);
		java.util.List<String> gradable = new java.util.ArrayList<>();
		java.util.List<String> ineligible = new java.util.ArrayList<>();
		java.util.Set<String> namespacesSeen = new java.util.HashSet<>();
		for (var entry : registry.entrySet()) {
			ResourceLocation id = entry.getKey().location();
			namespacesSeen.add(id.getNamespace());
			if (!matchesExtras(id) || DEFAULT_ALLOWLIST.contains(id.toString())) {
				continue;
			}
			(isGradable(entry.getValue(), registryAccess) ? gradable : ineligible).add(id.toString());
		}
		if (!ineligible.isEmpty()) {
			ineligible.sort(String::compareTo);
			ArcheanRise.LOGGER.info("SiteGrading extras: {} matched structure(s) skipped as not "
					+ "eligible (grading needs exact vanilla jigsaw type + WORLD_SURFACE_WG projection "
					+ "+ terrain adaptation): {}", ineligible.size(), String.join(", ", ineligible));
		}
		// warn with the entry as the user typed it (map value), not the normalized key
		for (var e : ArcheanRise.config.siteGradingExtraIds.entrySet()) {
			ResourceLocation loc = ResourceLocation.tryParse(e.getKey());
			if (loc == null || !registry.containsKey(loc)) {
				ArcheanRise.LOGGER.warn("SiteGrading extras: no structure registered as '{}' "
						+ "(typo, or its mod is not installed?)", e.getValue());
			}
		}
		for (var e : ArcheanRise.config.siteGradingExtraNamespaces.entrySet()) {
			if (!namespacesSeen.contains(e.getKey())) {
				ArcheanRise.LOGGER.warn("SiteGrading extras: entry '{}' matched no registered "
						+ "structure namespace (typo, or the mod is not installed?)", e.getValue());
			}
		}
		gradable.sort(String::compareTo);
		ArcheanRise.LOGGER.info("SiteGrading extras resolved: {} additional gradable structure(s){}",
				gradable.size(), gradable.isEmpty() ? "" : ": " + String.join(", ", gradable));
	}

	public static int shiftBudget(int maxDistanceFromCenter) {
		// keep anchor + footprint + pad inside the 128-block STRUCTURE_REFERENCES scan radius
		return Math.max(0, Math.min(MAX_SHIFT, 128 - maxDistanceFromCenter - 16));
	}

	/**
	 * Placement veto (blueprint §2, re-scoped by the 2026-07-06 vanilla-projection fix): reject a site
	 * where even VANILLA per-piece projection + the density-stage GradePad beard cannot produce a
	 * walkable village, measured on the water-clamped predictor floor ({@link SitePlanner#vetoEnvelope},
	 * PITCH-16) over the FOOTPRINT the pieces + beard occupy (anchor ± (maxDistanceFromCenter +
	 * {@link #PAD_RADIUS})). Rejects on three independent measures:
	 * <ul>
	 *   <li><b>relief</b> — footprint water-clamped max−min beyond {@code siteGradingVetoMaxRelief}. This
	 *       is a plain ACCESSIBILITY ceiling: with per-piece projection the village spans this much
	 *       vertical relief, and beyond ~{@code vetoMaxRelief} blocks the jigsaw paths/villager routes
	 *       across the footprint stop being walkable. (It is NOT the old apron-bridgeability bound: the
	 *       CUT+FILL grade is built behind default-off {@code siteGradingCutFill}, but relief stays a plain
	 *       accessibility ceiling — deliberately not re-tied to the apron. See {@link DesignSurface#clampProjection}.)</li>
	 *   <li><b>roughness</b> — max |Δ| between adjacent PITCH-16 nodes beyond {@link #VETO_ROUGHNESS_MAX}
	 *       (= PITCH): a genuine LOCAL CLIFF (node slope &gt; 1) bisecting the footprint, the barrier a
	 *       modest global relief can hide. Rolling ground (node steps ≤ ~8) passes and is built
	 *       vanilla-faithfully.</li>
	 *   <li><b>waterDepth</b> — deepest per-column support (SEA_LEVEL − floor) over the footprint core
	 *       beyond {@code siteGradingWaterVetoDepth}. A crit-6 SUPPORT gate (houses floating over open
	 *       water); orthogonal to terrain accessibility. Default 32 keeps ordinary coastal villages and
	 *       rejects only genuine over-water floaters; the authoritative fix is the Phase-4 foundation
	 *       fill.</li>
	 * </ul>
	 * Not a proof of feasibility (the R3 house-corner squeeze is terrain-independent, untouched here).
	 * The sampler is byte-identical to the field's where they overlap (same global lattice + probeColumn).
	 * Returns false (never veto) when disabled. Pure function of the probed heights — no RNG. Independent
	 * of SitePlanner's advisory {@code gradeable} flag.
	 */
	public static boolean vetoes(int relief, int roughness, int waterDepth) {
		if (ArcheanRise.config == null || !ArcheanRise.config.siteGradingVeto) {
			return false;
		}
		return relief > ArcheanRise.config.siteGradingVetoMaxRelief
				|| roughness > VETO_ROUGHNESS_MAX
				|| waterDepth > ArcheanRise.config.siteGradingWaterVetoDepth;
	}
}
