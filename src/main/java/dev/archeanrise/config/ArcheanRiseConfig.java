package dev.archeanrise.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import dev.archeanrise.ArcheanRise;
import dev.archeanrise.platform.Platform;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime (non-seed-baked) settings. Everything that shapes terrain itself lives in the
 * worldgen JSON (data/archean_rise/worldgen/...) because it is baked into the world at
 * creation time; this file only holds values that are safe to change on a running server.
 *
 * The config file on disk allows {@code //} comments (parsed leniently); defaults are
 * written with inline documentation for server admins.
 */
public class ArcheanRiseConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** For values embedded into the commented template — single-line, so indentation stays aligned. */
	private static final Gson COMPACT_GSON = new Gson();
	private static final String FILE_NAME = "archean_rise.json";

	/** Max concurrent chunk-generation futures the pregen keeps in flight. Clamped to 4..256. */
	public int pregenMaxInFlight = 64;

	/** Pause pregeneration while players are online, so it never competes with gameplay. */
	public boolean pregenPauseWhenPlayersOnline = true;
	/** Seconds between pregeneration progress log lines. Clamped to 1..600. */
	public int pregenLogIntervalSeconds = 10;
	/** Hard cap on the pregen radius (in chunks) accepted by the command. Clamped to 16..4096. */
	public int pregenMaxRadiusChunks = 1024;
	/**
	 * Drain generated chunks to disk every N generated during pregen (0 = off / vanilla). Bounds
	 * the in-memory unsaved set so its serialization+write cost is spread across the run instead
	 * of bursting on the next full/quit save (the ~10s "Saving world" stall). Clamped to 0..65536.
	 */
	public int pregenSaveIntervalChunks = 1024;

	/**
	 * Region-file write compression: "default" (leave vanilla alone — Deflate/zlib, level 6),
	 * "lz4" (much faster to compress, ~1.7x larger files, still readable by vanilla 1.21.1 and
	 * external tools), "deflate", or "none". Applied globally at startup via
	 * RegionFileVersion.configure — the main use is enabling LZ4 in SINGLEPLAYER, where there is
	 * no server.properties (a dedicated server uses region-file-compression in server.properties,
	 * which wins there). Lossless and reversible: chunks carry a per-chunk codec byte, so switching
	 * re-writes new chunks in the new codec while old chunks still read.
	 */
	public String regionFileCompression = "default";

	/** Dev tool: pregenerate a radius around spawn automatically at server/world start. */
	public boolean autoPregenEnabled = false;
	/** Auto-pregen radius in BLOCKS around world spawn (runs once per world per radius). */
	public int autoPregenRadiusBlocks = 32;

	/** Generate chunks ahead of each player's view distance so terrain is always present. */
	public boolean playerAheadEnabled = false;
	/** How many chunks beyond view distance to keep generated (adaptive: falls back to 3 = 48 blocks under server load). */
	public int playerAheadChunks = 8;

	/** Master switch for SiteGrading (structure site check + candidate search + re-grade). */
	public boolean siteGradingEnabled = true;
	/**
	 * Reject (veto) village/structure sites that cannot be made navigable within the earthwork
	 * budget, rather than placing them on broken terrain (the proven IVP pattern). OPT-IN
	 * DEFAULT ON (2026-07-06): with vanilla per-piece projection restored, survivors are built on
	 * buildable ground and the footprint-scoped veto (SitePlanner: anchor ± (maxDist + PAD_RADIUS),
	 * not the apron envelope) rejects only sites whose FOOTPRINT relief exceeds {@link
	 * #siteGradingVetoMaxRelief} (a walkable village cannot span more) or that hold a genuine local
	 * cliff — the sites where even vanilla could not place a walkable village. Measured survival:
	 * ~44–50% of candidate sites on gentle 1.2×-relief test terrain (pre-pivot tier 1, seed 424242);
	 * expect fewer on the static world's 3.32× relief, because relief is an ABSOLUTE block-span
	 * ceiling and amplified relief pushes proportionally more footprints past it — steeper
	 * terrain simply has fewer walkable village sites (vanilla-faithful; vanilla's flat-erosion village
	 * biomes avoid such terrain too). On a highland-spawn seed the immediate spawn region can lose all
	 * villages (correct: it is uniformly mountainous). Set false to restore pure-vanilla placement
	 * (villages everywhere, broken on cliffs); or raise {@link #siteGradingVetoMaxRelief} (now up to
	 * 128) to trade accessibility for more villages. Re-tuned to the apron budget when cut+fill lands.
	 */
	public boolean siteGradingVeto = true;
	/** Veto if footprint+apron relief (max-min solid-floor height) exceeds this. Clamped 8..128. */
	public int siteGradingVetoMaxRelief = 32;
	/**
	 * Veto if a village-CORE column (within ±32 blocks of the anchor — {@code SitePlanner.WATER_CORE})
	 * has its solid floor deeper than this below sea level — i.e. houses would float over open water.
	 * Clamped 0..64. DEFAULT 32 as of the 2026-07-06 fix (was 8): 8 mass-vetoed ordinary COASTAL
	 * villages (a center-on-land village whose footprint edge merely dips into a lake/sea reaches
	 * waterDepth ≈ 20–40 at the rim), which vanilla places freely. 32 keeps those (center-on-land
	 * survives; the ±32 core still touches land) and rejects only genuine over-water floaters — the
	 * Sv-vertical-amplification artifact where a land village-biome is pushed below sea level. This is
	 * a crit-6 SUPPORT gate, orthogonal to terrain accessibility (relief/roughness); the authoritative
	 * fix is the Phase-4 foundation fill. Set 0 to disable, lower toward 8 once fill lands.
	 */
	public int siteGradingWaterVetoDepth = 32;
	/** Step 2: deterministic search for a better anchor within the shift budget. */
	public boolean siteGradingCandidateSearch = true;
	/** Step 3a: GradePad density terms (noise-stage terrain conformance around pieces). */
	public boolean siteGradingGradePad = true;
	/** Step 3b: features-stage foundation fill under pieces (air-only, depth-capped). */
	public boolean siteGradingFoundationFill = true;
	/**
	 * Cut-to-inset ADD-ON (foreign) surface structures: give a non-gradable {@code BEARD_THIN}
	 * structure a {@code BEARD_BOX}-style noise-stage carve so it insets into hills/mountains with a
	 * clean interior (no terrain inside the buildings) and a &gt;=2-block skirt, instead of hanging off
	 * a slope or poking through. Composes over the vanilla beard (which supplies the fill-below
	 * support). Self-limiting — a no-op where no terrain intrudes. Only in Archean Rise dimensions;
	 * {@code BURY}/{@code ENCAPSULATE}/{@code NONE}/{@code BEARD_BOX} and gradable (village) structures
	 * are left alone. See {@link dev.archeanrise.sitegrading.ForeignInsetBeard}.
	 */
	public boolean insetForeignStructures = true;

	/**
	 * Issues 2/3/4: post-placement EXTERIOR grade for foreign inset structures — replace the beard's
	 * vertical cut wall with a sloped cut face (3/2..4/1), fill a genuine overhang with a steep declining
	 * bank, and flare a tunnel-mouth where a piece bored through a hill. Default ON; each behaviour is a
	 * no-op where its trigger is absent. Only shapes natural terrain outside/below the piece boxes, never
	 * the structure's own blocks. See {@link dev.archeanrise.sitegrading.ForeignInsetGrade}.
	 */
	public boolean insetForeignGrade = true;
	/** Max horizontal reach (blocks) of the foreign exterior grade beyond a piece box. Clamped 4..48. */
	public int insetForeignGradeReach = 24;
	/** Fill a foreign piece's overhang only when it floats MORE than this many blocks over solid. Clamped 1..32. */
	public int insetForeignOverhangMin = 3;
	/** Tunnel-mouth flare: blocks removed off the solid ceiling/sides before the +1..3 seed jitter. Clamped 0..6. */
	public int insetForeignTunnelBase = 2;

	/**
	 * BURIAL GATE (v0.3.14): stop the foreign surface-inset earthwork from excavating structures their
	 * author deliberately BURIED. Until 0.3.14 the only "is this underground?" guard was the ABSOLUTE test
	 * {@code box.maxY() < SEA_LEVEL (63)} — meaningless in a world whose land reaches y=768, so a cave ruin
	 * sunk 40 blocks under a y=231 mountain was treated as a hillside cottage and had the mountain carved
	 * off it (the {@code create_ltab:cave_ruins} floating island). This replaces it with a SURFACE-RELATIVE
	 * test measured at the exact column vanilla projected the structure from. It never moves or vetoes a
	 * structure — it only withdraws AR's earthwork and hands the structure back to vanilla's own
	 * {@code terrain_adaptation}. Set false to restore pre-0.3.14 behaviour exactly.
	 * See {@link dev.archeanrise.sitegrading.BuriedStructures} for the safety proof.
	 */
	public boolean insetForeignBurialGate = true;
	/**
	 * How many blocks of rock a foreign structure's start piece must sit below the projected surface before
	 * it counts as BURIED (and is therefore left alone). Clamped 1..64. Because burial is a pure function of
	 * the structure's own declared {@code start_height} — the terrain term cancels out of vanilla's
	 * projection arithmetic — terrain relief can never push a SURFACE structure across this threshold.
	 * Default 8: every projected surface structure in a typical pack declares {@code start_height: 0}
	 * (burial &lt;= 0) and shallow-inset dungeons declare -1/-2 (burial &lt;= 2), while a genuine buried ruin
	 * declares -40 (burial 33). Raise it only if a mod ships a deliberately-sunken SURFACE building that you
	 * still want inset (or list that structure in insetForeignForceSurfaceStructures instead).
	 */
	public int insetForeignBurialMargin = 8;
	/**
	 * Override: treat these structures as SURFACE structures (inset them) even if the burial measurement
	 * says buried. Each entry is one structure id ("mymod:fort") or a whole mod ("mymod" / "mymod:*").
	 */
	public java.util.List<String> insetForeignForceSurfaceStructures = new java.util.ArrayList<>();
	/**
	 * Override: treat these structures as BURIED (never inset them) even if the burial measurement says
	 * surface. Same entry grammar. Use for a future mod whose geometry defeats the measurement.
	 */
	public java.util.List<String> insetForeignForceBuriedStructures = new java.util.ArrayList<>();

	/** Parsed from insetForeignForceSurfaceStructures at load: normalized structure id → entry as typed. */
	public transient java.util.Map<String, String> insetForeignForceSurfaceIds = java.util.Map.of();
	/** Parsed from insetForeignForceSurfaceStructures at load: namespace → entry as typed. */
	public transient java.util.Map<String, String> insetForeignForceSurfaceNamespaces = java.util.Map.of();
	/** Parsed from insetForeignForceBuriedStructures at load: normalized structure id → entry as typed. */
	public transient java.util.Map<String, String> insetForeignForceBuriedIds = java.util.Map.of();
	/** Parsed from insetForeignForceBuriedStructures at load: namespace → entry as typed. */
	public transient java.util.Map<String, String> insetForeignForceBuriedNamespaces = java.util.Map.of();

	/**
	 * Issue 1: gate eligible foreign SURFACE structures (the same {@code BEARD_THIN}+{@code !isGradable}
	 * set the inset earthwork treats) out of snow-covered sites — a generic add-on build placed in a
	 * snowy biome reads wrong against snow-covered ground, and snowing its own blocks would be writing on
	 * another mod's structure. Blunt: cannot tell a snow-appropriate foreign build from a generic one, so
	 * it removes ALL eligible foreign surface structures where snow falls. Vanilla villages/outposts and
	 * non-surface structures are untouched. See {@link dev.archeanrise.mixin.StructureSnowGateMixin}.
	 */
	public boolean gateForeignInSnow = true;

	/**
	 * Gate eligible foreign (add-on, {@code !isGradable}) SURFACE structures OFF open water so they only
	 * spawn on land — the water analogue of {@link #gateForeignInSnow}. Archean Rise's static terrain
	 * decouples biome from elevation (a land biome can sit below sea level) and carves rivers, so a foreign
	 * structure placed by its own biome/heightmap rules can land floating on / sunk in water; SiteGrading's
	 * water veto only protects the vanilla village/outpost allowlist, never foreign structures. This
	 * returns {@code StructureStart.INVALID_START} (the "no structure here" result vanilla uses on a failed
	 * placement — determinism-neutral) for a foreign surface structure whose FOOTPRINT sits mostly over
	 * water deeper than {@link #gateForeignInWaterDepth}. INTENTIONALLY-AQUATIC structures are protected by
	 * a biome guard: a structure in an ocean/deep-ocean/river biome (ocean villages, drifting ships, ocean
	 * monuments) is never gated. Vanilla villages/outposts ({@code isGradable}) and underground/underwater
	 * structures (bounding box below sea level — mineshafts, ocean monuments) are untouched.
	 * See {@link dev.archeanrise.mixin.StructureWaterGateMixin}.
	 */
	public boolean gateForeignInWater = true;
	/**
	 * Min water depth (blocks the solid floor sits below sea level 63) under a foreign structure's
	 * footprint before {@link #gateForeignInWater} counts it as "on water". Clamped 0..64. Default 8:
	 * keeps beach/swamp/shallow-shore placements (floor within ~a few blocks of sea level) and gates only
	 * genuine water bodies. Lower toward 0 for stricter land-only; raise to tolerate deeper coastal water.
	 */
	public int gateForeignInWaterDepth = 8;

	/**
	 * SiteGrading v2 authoritative cut+fill terrain-integration pass (blueprint Architecture B).
	 * Experimental / in development — OFF by default. When on, graded structures get a 1-Lipschitz
	 * (slope-≤1) target surface with cut, natural graded fill, apron, and water support.
	 */
	public boolean siteGradingCutFill = false;
	/** Max slope-1 blend length (blocks) beyond the footprints before natural terrain resumes. Clamped 8..128. */
	public int siteGradingApronRampMax = 32;
	/**
	 * Phase-3 CUT sub-switch (within {@link #siteGradingCutFill}): shave the natural APRON collar down
	 * to the field's slope-1 ceiling cone. Default on; set false to build the field with NO block
	 * writes (Phase-2 field-hash-only behaviour) even while {@code siteGradingCutFill} is enabled.
	 */
	public boolean siteGradingCut = true;
	/**
	 * Extra blocks added to the vanilla {@code BEARD_THIN} (12-block) reach when protecting a FOREIGN
	 * structure's box during the CUT+FILL pass (foreign-yield, blueprint §3.4/§5). Guards a
	 * neighbour's terrain-blended out-of-box scatter and entrance-approach columns. Clamped 0..4 — the
	 * CUT's foreign-yield sweep is a 3×3 chunk neighbourhood, complete only while {@code halo =
	 * BEARD_THIN + this ≤ 16}; a larger value cannot be honoured at the FEATURES hook (see validate()).
	 */
	public int siteGradingForeignHaloExtra = 2;
	/**
	 * Extra structures (beyond villages + pillager outposts) to grade. Each entry is either a
	 * single structure id ("mymod:fort") or a whole mod/namespace ("mymod" or "mymod:*").
	 * Parsed into {@link #siteGradingExtraIds}/{@link #siteGradingExtraNamespaces} at load.
	 */
	public java.util.List<String> siteGradingExtraStructures = new java.util.ArrayList<>();

	/** Parsed from siteGradingExtraStructures at load: normalized structure id → entry as typed. */
	public transient java.util.Map<String, String> siteGradingExtraIds = java.util.Map.of();
	/** Parsed from siteGradingExtraStructures at load: namespace → entry as typed. */
	public transient java.util.Map<String, String> siteGradingExtraNamespaces = java.util.Map.of();

	/**
	 * Scale structure spacing/separation by Ss = 3.0× in the Archean Rise world so ALL structures —
	 * vanilla AND add-on mods — spread apart to match the enlarged biomes. Applied per-generator at
	 * {@code ChunkGeneratorStructureState} creation, so the Nether/End and non-Archean generators are
	 * untouched and {@code /locate}/maps/trades stay consistent. Concentric-rings (strongholds) and
	 * frequency-driven sets (mineshafts/buried treasure) are passed through unchanged. Affects a
	 * generator's structure grid at world load; existing structures are not moved.
	 */
	public boolean scaleStructureSpacing = true;

	/**
	 * Biome-border blend strength, 0 (off / normal hard borders) .. 24 (max). Softens the "abrupt
	 * cutoff" by domain-warping ONLY the temperature+humidity climate sampling (so biome borders
	 * finger/interlock into each other instead of meeting on a straight line) — terrain is byte-
	 * identical (continents/erosion/depth/weirdness untouched), and injected biome mods inherit it.
	 * Applied at generation time per Archean generator, scaled by the world's biome size (Sh). LIMITS:
	 * this softens border SHAPE only; the perpendicular snap of foliage/features/mobs and grass/water
	 * COLOUR cannot be blended under MultiNoiseBiomeSource (raise the client "Biome Blend" video setting
	 * for colour). CAVEAT: this feeds generation, so changing it on an EXISTING world cosmetically seams
	 * the generation frontier (NO terrain cliff — only biome layout). See limitations/biome-borders.md.
	 */
	public int biomeBorderBlend = 0;

	/**
	 * Issue-5 float despeckle (default OFF until validated): at the features stage (post-carver,
	 * pre-structure) remove SMALL fully-detached solid clumps — the residual jaggedness/cave floaters that
	 * survive the anisotropy fix — while PRESERVING any large formation, so a future intentional floating
	 * sky island is never removed. A component larger than {@link #floatDespeckleMaxBlocks} (or spanning
	 * far) is kept; only fully-enclosed small clumps at/above {@link #floatDespeckleMinY} are cleared.
	 * Seed-pure (a pure function of the frozen post-carver terrain). See DECISIONS.md / limitations.
	 */
	public boolean floatDespeckleEnabled = false;
	/** Max blocks a detached clump may have and still be removed as an artifact (bigger = kept). Clamped 1..256. */
	public int floatDespeckleMaxBlocks = 16;
	/** Only despeckle at/above this Y (never touch deep rock; the reported floater is at y201). Clamped -64..512. */
	public int floatDespeckleMinY = 63;

	/**
	 * Rivers R2b-4d NATURAL waterfalls &amp; rapids (spec §7.2/§7.4.1). At the features stage (post-carver,
	 * post-aquifer) turn the river graph's flagged lips into natural drops over the R2b-4b carved step
	 * cliff — a retained lip source seated on the actual terrain crest (never floating), a non-spreading
	 * falling curtain down the drop face, and a rim-contained plunge pool at the landing (a filled reach
	 * basin is reused as-is; a self-contained bowl is dug only on dry ground). Rapids (2..3) get a light
	 * short fall over a wetted step. Quiescent by construction (walled source + non-spreading fall +
	 * rim-contained pool) and NON-DESTRUCTIVE to the aquifer's river water (pooled reaches stay
	 * byte-identical falls-ON vs falls-OFF). The live fluid-tick check is R2d. Seed-pure,
	 * Archean-generators-only. Set false to fall back to the plain stepped-pool descent (the off switch).
	 * See {@link dev.archeanrise.rivers.RiverFalls}.
	 */
	public boolean riverFallsEnabled = true;

	/**
	 * Rivers R2b-4c POOL FILL (spec §7.3 "fill water sources up to the reach surface"). At the features
	 * stage (post-carver, post-aquifer, BEFORE {@link dev.archeanrise.rivers.RiverFalls}) fill every
	 * still-dry, PROVABLY-CONTAINED river-pool column of a flat reach up to its reach surface — the
	 * supplement that makes the stepped pools hold water where the R2c aquifer does not flood a perched
	 * basin (the R2d visual read only ~39% of pool length wet). A pool whose rim cannot hold the surface
	 * (the R2b-4a escape-saddle, &lt;5% of pools) is LEFT DRY rather than filled to leak. Quiescent by
	 * construction (level-0 sources in a walled basin over a solid floor) and non-destructive to the
	 * aquifer's water (fills AIR only, never double-places); with the upper pool filled, the R2b-4b spill
	 * crest lets the pool feed the waterfall on its own. The live fluid-tick check is R2d. Seed-pure,
	 * Archean-generators-only. Set false to fall back to the aquifer-only fill (the off switch).
	 * See {@link dev.archeanrise.rivers.RiverPools}.
	 */
	public boolean riverPoolFillEnabled = true;

	public static ArcheanRiseConfig load() {
		Path path = Platform.get().configDir().resolve(FILE_NAME);
		ArcheanRiseConfig config = new ArcheanRiseConfig();
		if (Files.exists(path)) {
			try {
				String raw = Files.readString(path);
				JsonReader reader = new JsonReader(new StringReader(raw));
				reader.setLenient(true); // permits // comments in the file
				ArcheanRiseConfig parsed = GSON.fromJson(reader, ArcheanRiseConfig.class);
				if (parsed != null) {
					config = parsed;
				}
			} catch (IOException | RuntimeException e) {
				ArcheanRise.LOGGER.warn("Could not read {} — using defaults: {}", FILE_NAME, e.toString());
			}
		}
		config.validate();
		// Regenerate on every load: configs from older versions self-upgrade with any newly
		// added keys and current documentation, while user VALUES are preserved.
		write(path, config);
		return config;
	}

	private void validate() {
		pregenMaxInFlight = clamp("pregenMaxInFlight", pregenMaxInFlight, 4, 256);
		autoPregenRadiusBlocks = clamp("autoPregenRadiusBlocks", autoPregenRadiusBlocks, 16, 65536);
		playerAheadChunks = clamp("playerAheadChunks", playerAheadChunks, 3, 32);
		pregenLogIntervalSeconds = clamp("pregenLogIntervalSeconds", pregenLogIntervalSeconds, 1, 600);
		pregenMaxRadiusChunks = clamp("pregenMaxRadiusChunks", pregenMaxRadiusChunks, 16, 4096);
		pregenSaveIntervalChunks = clamp("pregenSaveIntervalChunks", pregenSaveIntervalChunks, 0, 65536);
		String rfc = regionFileCompression == null ? "default"
				: regionFileCompression.trim().toLowerCase(java.util.Locale.ROOT);
		if (!rfc.equals("default") && !rfc.equals("lz4") && !rfc.equals("deflate") && !rfc.equals("none")) {
			ArcheanRise.LOGGER.warn("Config regionFileCompression='{}' unknown "
					+ "(expected default/lz4/deflate/none) — using 'default'", regionFileCompression);
			rfc = "default";
		}
		regionFileCompression = rfc;
		biomeBorderBlend = clamp("biomeBorderBlend", biomeBorderBlend, 0, 24);
		floatDespeckleMaxBlocks = clamp("floatDespeckleMaxBlocks", floatDespeckleMaxBlocks, 1, 256);
		floatDespeckleMinY = clamp("floatDespeckleMinY", floatDespeckleMinY, -64, 512);
		insetForeignGradeReach = clamp("insetForeignGradeReach", insetForeignGradeReach, 4, 48);
		insetForeignOverhangMin = clamp("insetForeignOverhangMin", insetForeignOverhangMin, 1, 32);
		insetForeignTunnelBase = clamp("insetForeignTunnelBase", insetForeignTunnelBase, 0, 6);
		insetForeignBurialMargin = clamp("insetForeignBurialMargin", insetForeignBurialMargin, 1, 64);
		siteGradingVetoMaxRelief = clamp("siteGradingVetoMaxRelief", siteGradingVetoMaxRelief, 8, 128);
		siteGradingWaterVetoDepth = clamp("siteGradingWaterVetoDepth", siteGradingWaterVetoDepth, 0, 64);
		gateForeignInWaterDepth = clamp("gateForeignInWaterDepth", gateForeignInWaterDepth, 0, 64);
		siteGradingApronRampMax = clamp("siteGradingApronRampMax", siteGradingApronRampMax, 8, 128);
		// Clamp to 0..4 (NOT higher): halo = BEARD_THIN(12) + haloExtra must stay ≤ 16 so the CUT's 3×3
		// foreign-yield sweep is complete (a foreign box within halo of a column lies in this chunk or an
		// immediate neighbour). The sweep cannot widen past 3×3 at FEATURES — a distance-2 chunk is not
		// readable at STRUCTURE_REFERENCES — so halo ≤ 16 is a hard hook ceiling, not a free knob.
		siteGradingForeignHaloExtra = clamp("siteGradingForeignHaloExtra", siteGradingForeignHaloExtra, 0, 4);
		// NOTE: the old "cap vetoMaxRelief DOWN to apronRampMax" cross-clamp stays REMOVED. Its premise —
		// a slope-1 apron of length apronRampMax bridges at most apronRampMax blocks of divergence — was a
		// vanilla-projection-era bound. With the 3c flat-snap + CUT/FILL grade now built (behind default-off
		// siteGradingCutFill), the earthwork regrades terrain TO the flat platform up to the depth cap
		// (vetoMaxRelief + apronRampMax), so a village's gradeability is decided by the field's infeasible=0,
		// NOT by relief ≤ apronRampMax. vetoMaxRelief therefore stays a plain accessibility ceiling on how
		// much relief a village may span (independent of apronRampMax); it is DELIBERATELY not re-tied — see
		// DECISIONS.md 2026-07-07 (the flat-snap-grade decision). Its 8..128 range is clamped above.
		if (siteGradingExtraStructures == null) {
			siteGradingExtraStructures = new java.util.ArrayList<>();
		}
		if (insetForeignForceSurfaceStructures == null) {
			insetForeignForceSurfaceStructures = new java.util.ArrayList<>();
		}
		if (insetForeignForceBuriedStructures == null) {
			insetForeignForceBuriedStructures = new java.util.ArrayList<>();
		}
		Parsed extras = parseIdList("siteGradingExtraStructures", siteGradingExtraStructures);
		siteGradingExtraIds = extras.ids();
		siteGradingExtraNamespaces = extras.namespaces();
		Parsed forceSurface =
				parseIdList("insetForeignForceSurfaceStructures", insetForeignForceSurfaceStructures);
		insetForeignForceSurfaceIds = forceSurface.ids();
		insetForeignForceSurfaceNamespaces = forceSurface.namespaces();
		Parsed forceBuried =
				parseIdList("insetForeignForceBuriedStructures", insetForeignForceBuriedStructures);
		insetForeignForceBuriedIds = forceBuried.ids();
		insetForeignForceBuriedNamespaces = forceBuried.namespaces();
	}

	/** Normalized structure-id / whole-mod-namespace sets parsed out of one config list. */
	private record Parsed(java.util.Map<String, String> ids, java.util.Map<String, String> namespaces) {}

	/**
	 * Splits a structure-id config list into exact ids and whole-mod namespaces. The user's entries are
	 * kept verbatim in the list (so the file round-trips); matching uses these normalized sets. Resolution
	 * against the structure registry (typo/eligibility report) happens at server start, when registries
	 * exist — see SiteGrading.reportExtras / BuriedStructures.reportOverrides.
	 */
	private static Parsed parseIdList(String key, java.util.List<String> entries) {
		java.util.Map<String, String> ids = new java.util.HashMap<>();
		java.util.Map<String, String> namespaces = new java.util.HashMap<>();
		for (String raw : entries) {
			String entry = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
			int colon = entry.indexOf(':');
			// the ONLY supported '*' form is the exact "mymod:*" suffix — a lone trailing '*'
			boolean wholeModWildcard = colon > 0 && colon == entry.length() - 2
					&& entry.endsWith(":*") && entry.indexOf('*') == entry.length() - 1;
			if (entry.isEmpty()) {
				ArcheanRise.LOGGER.warn("Config {}: blank entry ignored", key);
			} else if (colon == 0) {
				ArcheanRise.LOGGER.warn("Config {}: entry '{}' has no namespace — ignored", key, raw);
			} else if (!wholeModWildcard && entry.indexOf('*') >= 0) {
				ArcheanRise.LOGGER.warn("Config {}: entry '{}' — partial wildcards are not supported "
						+ "(use 'mymod:fort', 'mymod' or 'mymod:*') — ignored", key, raw);
			} else if (colon == entry.length() - 1) {
				ArcheanRise.LOGGER.warn("Config {}: entry '{}' ends with ':' — use 'mymod' or 'mymod:*' "
						+ "for a whole mod — ignored", key, raw);
			} else if (colon < 0) {
				namespaces.put(entry, raw); // "mymod" — the whole mod
			} else if (wholeModWildcard) {
				namespaces.put(entry.substring(0, colon), raw); // "mymod:*" — the whole mod
			} else {
				ids.put(entry, raw); // "mymod:fort" — one structure
			}
		}
		return new Parsed(java.util.Map.copyOf(ids), java.util.Map.copyOf(namespaces));
	}

	private static int clamp(String name, int value, int min, int max) {
		if (value < min || value > max) {
			int clamped = Math.max(min, Math.min(max, value));
			ArcheanRise.LOGGER.warn("Config {}={} out of range [{}..{}], clamped to {}", name, value, min, max, clamped);
			return clamped;
		}
		return value;
	}

	private static void write(Path path, ArcheanRiseConfig config) {
		String contents = String.format(java.util.Locale.ROOT, """
				// Archean Rise — runtime configuration.
				// This file is REGENERATED at every launch: values are kept, comments are refreshed,
				// and keys added by mod updates appear automatically. Custom comments won't survive.
				// Terrain shape and world height are baked into a world at creation time by the
				// Archean Rise world preset (static Y -256..768) and are NOT configurable here.
				{
				  // Max concurrent chunk generations the pregenerator keeps in flight (async
				  // engine saturates all CPU worker cores). Range 4..256. Higher = faster but
				  // more RAM while running; 64 suits most machines.
				  "pregenMaxInFlight": %d,

				  // If true, pregeneration pauses while any player is online. Recommended: true.
				  "pregenPauseWhenPlayersOnline": %b,

				  // Seconds between pregen progress messages in the server log. Range 1..600.
				  "pregenLogIntervalSeconds": %d,

				  // Largest radius (in chunks) the pregen command will accept. Range 16..4096.
				  // 1024 chunks = a 32768x32768 block area around the chosen center.
				  "pregenMaxRadiusChunks": %d,

				  // Drain generated chunks to disk every N chunks during pregeneration (0 = off).
				  // A fast pregen dirties chunks faster than the game writes them, so the unsaved
				  // set piles up and the next full save (autosave, or the save on quit) has to
				  // serialize + compress + write the whole backlog at once — the long "Saving
				  // world" stall. Draining incrementally spreads that cost across the run and keeps
				  // the quit save small. Range 0..65536; 1024 = ~1 region file. Lower = smoother
				  // but slightly more overhead. Purely a save-timing aid: never changes terrain.
				  "pregenSaveIntervalChunks": %d,

				  // Region-file write compression. "default" leaves vanilla alone (Deflate/zlib);
				  // "lz4" compresses MUCH faster (helps the "Saving world" stall) for ~1.7x larger
				  // files and is still readable by vanilla 1.21.1 and external tools; "deflate" and
				  // "none" also accepted. Lossless & reversible (per-chunk codec byte). Mainly for
				  // SINGLEPLAYER — a dedicated server should use region-file-compression in
				  // server.properties (that wins there). Applies to ALL dimensions at startup.
				  "regionFileCompression": "%s",

				  // DEV TOOL — automatic spawn-area pregeneration: when enabled, the async pregen
				  // engine generates a radius (in BLOCKS) around world spawn at startup, once per
				  // world (re-runs only if the radius is raised; progress in the server log).
				  "autoPregenEnabled": %b,
				  "autoPregenRadiusBlocks": %d,

				  // Generate chunks AHEAD of each player's view distance so terrain always exists
				  // before it becomes visible. Targets view distance + playerAheadChunks; under
				  // sustained server load it adaptively falls back to +3 chunks (48 blocks).
				  "playerAheadEnabled": %b,
				  "playerAheadChunks": %d,

				  // SiteGrading: adaptive structure placement in Archean Rise worlds. Structures
				  // (villages, pillager outposts + siteGradingExtraStructures) get a terrain fit
				  // check, a deterministic nearby-site search, and terrain re-grading so pieces
				  // slot naturally into the land. Sub-switches: candidateSearch (move to a better
				  // spot <=32 blocks), gradePad (reshape terrain around pieces at generation),
				  // foundationFill (close residual gaps under pieces). All seed-deterministic.
				  "siteGradingEnabled": %b,

				  // SiteGrading v2 authoritative CUT+FILL terrain integration (EXPERIMENTAL, default
				  // false, in development). When on, graded structures get a slope<=1 target surface
				  // with terrain cut, natural graded fill, apron, and water-supported foundations.
				  // apronRampMax: max slope-1 blend length (blocks) before natural terrain resumes (8..128).
				  // cut: Phase-3 sub-switch — shave the natural apron down to the slope-1 cone (default true).
				  // foreignHaloExtra: extra blocks (added to the 12-block beard reach) kept fully off-limits
				  //   around EVERY OTHER structure's box, so grading never disturbs a neighbour (0..4).
				  "siteGradingCutFill": %b,
				  "siteGradingApronRampMax": %d,
				  "siteGradingCut": %b,
				  "siteGradingForeignHaloExtra": %d,

				  // VETO (default TRUE): reject village/structure sites where even vanilla per-piece
				  // projection cannot make a walkable village — a footprint too steep to span, or split
				  // by a genuine cliff — instead of placing them on broken ground. Measured over the
				  // FOOTPRINT the pieces + terrain beard occupy (anchor +- (maxDistanceFromCenter +
				  // PAD_RADIUS)), NOT scenery farther out. ~44-50%% of candidate sites survived on the
				  // gentle 1.2x-relief test terrain; expect fewer on the static world's 3.32x relief
				  // (relief is an absolute ceiling). Set false for pure-vanilla placement (villages
				  // everywhere, some broken on cliffs).
				  // vetoMaxRelief: reject if the footprint's water-clamped floor spans more than this
				  //   (max-min) — a walkable village cannot span more vertical relief. Range 8..128;
				  //   raise it to trade accessibility for more villages on steep terrain.
				  // waterVetoDepth: reject if a footprint-core column overhangs water deeper than this
				  //   (a support/floating-house concern). Default 32 keeps coastal villages; lower for
				  //   stricter. 0..64.
				  // (A local-roughness gate is applied automatically so locally-steep sites can't slip
				  //   through; it is a fixed derived threshold, not configurable.)
				  "siteGradingVeto": %b,
				  "siteGradingVetoMaxRelief": %d,
				  "siteGradingWaterVetoDepth": %d,
				  "siteGradingCandidateSearch": %b,
				  "siteGradingGradePad": %b,
				  "siteGradingFoundationFill": %b,

				  // Cut-to-inset ADD-ON (foreign) surface structures: a non-gradable BEARD_THIN
				  // structure gets a BEARD_BOX-style noise-stage carve so it insets into hills/mountains
				  // with a clean interior (no terrain inside the buildings) + a >=2-block skirt, instead
				  // of hanging off a slope. Self-limiting (no-op where no terrain intrudes); the vanilla
				  // beard still supplies fill support. BURY/ENCAPSULATE/NONE/BEARD_BOX + village
				  // structures are left alone. Set false to leave add-on structures fully vanilla.
				  "insetForeignStructures": %b,

				  // Issues 2/3/4: post-placement exterior grade for foreign inset structures — a sloped cut
				  // face (3/2..4/1) instead of a vertical wall, a steep declining fill under a genuine
				  // overhang, and a flared tunnel mouth where a piece bored through a hill. Default true;
				  // each is a no-op where its trigger is absent, and it only shapes natural terrain around
				  // the structure (never the structure's own blocks). gradeReach = max horizontal reach
				  // beyond a box (4..48); overhangMin = fill only when a piece floats more than this (1..32);
				  // tunnelBase = blocks off the tunnel ceiling/sides before the +1..3 seed jitter (0..6).
				  "insetForeignGrade": %b,
				  "insetForeignGradeReach": %d,
				  "insetForeignOverhangMin": %d,
				  "insetForeignTunnelBase": %d,

				  // BURIAL GATE (0.3.14): keep the two inset passes above OFF structures their author
				  // deliberately BURIED (cave ruins, bunkers, crypts). Depth is not an absolute Y — in an
				  // Archean Rise world the land reaches y=768, so "underground" only means "far below the
				  // LOCAL surface". A structure is treated as BURIED when its start piece's roof sits at
				  // least burialMargin blocks below the surface at the very column vanilla projected it
				  // from. Such a structure is handed back to vanilla's own terrain_adaptation untouched;
				  // it is never moved, never vetoed, and its placement never changes. Surface structures
				  // are mathematically incapable of tripping this (their declared start_height is 0, so
                  // their burial is <= 0), so the inset earthwork keeps working exactly as before.
				  // Without this, a ruin buried 40 blocks under a mountain got the mountain carved off it
				  // and left a floating island overhead. Set burialGate false to restore 0.3.13 behaviour.
				  // burialMargin: blocks of cover required to count as buried (1..64).
				  // forceSurface/forceBuried: override the measurement per structure ("mymod:fort") or per
				  //   mod ("mymod" / "mymod:*"). Both normally empty — the measurement needs no help.
				  "insetForeignBurialGate": %b,
				  "insetForeignBurialMargin": %d,
				  "insetForeignForceSurfaceStructures": %s,
				  "insetForeignForceBuriedStructures": %s,

				  // Issue 1: gate eligible foreign SURFACE structures (the inset set) out of snow-covered
				  // sites — a generic add-on build reads wrong on snowy ground, and snowing its own blocks
				  // would write on another mod's structure. Blunt: removes ALL eligible foreign surface
				  // structures where snow falls; vanilla villages/outposts + non-surface structures untouched.
				  "gateForeignInSnow": %b,

				  // Gate eligible foreign SURFACE structures OFF open water so add-on structures only spawn
				  // on land (the water analogue of gateForeignInSnow). AR's static terrain can push a land
				  // biome below sea level and carves rivers, so a foreign structure placed by its own rules
				  // can float on / sink in water; the vanilla-only site veto never protects it. Removes a
				  // foreign surface structure whose footprint sits MOSTLY over water deeper than
				  // gateForeignInWaterDepth. INTENTIONALLY-AQUATIC structures are safe: anything in an
				  // ocean/deep-ocean/river biome (ocean villages, drifting ships, ocean monuments) is never
				  // gated, nor are underground/underwater structures (box below sea level) or vanilla
				  // villages/outposts. waterDepth = blocks the solid floor sits below sea level (63) before a
				  // column counts as water (0..64; 8 keeps beach/swamp/shallow shores, gates real water).
				  "gateForeignInWater": %b,
				  "gateForeignInWaterDepth": %d,
				  // Extra structures to grade. Each entry is either one structure id
				  // ("mymod:fort") or an ENTIRE MOD ("mymod" or "mymod:*" — every structure that
				  // mod registers). Only exact vanilla-type jigsaw structures with
				  // WORLD_SURFACE_WG projection and terrain adaptation are ever affected —
				  // ineligible structures a mod entry sweeps in are skipped safely. What each
				  // entry resolved to is reported in the log at server start (catches typos).
				  "siteGradingExtraStructures": %s,

				  // Scale structure spacing/separation by Ss = 3.0x in the Archean Rise world so ALL
				  // structures — vanilla and add-on mods — spread apart to match the enlarged biomes
				  // (12x). Per-generator, so the Nether/End are untouched and
				  // /locate + explorer maps + villager trades stay consistent; concentric-rings
				  // (strongholds) and frequency-driven sets (mineshafts/buried treasure) are never
				  // scaled. Applies at world/generator load; already-placed structures are not moved.
				  "scaleStructureSpacing": %b,

				  // Biome-border blend, 0 (off, hard borders) .. 24 (max). Domain-warps ONLY the
				  // temperature+humidity biome sampling so borders finger/interlock instead of forming
				  // straight lines; terrain is byte-identical and injected biome mods inherit it. Softens
				  // border SHAPE only — foliage/features/mobs and grass/water COLOUR still snap (raise the
				  // client "Biome Blend" video setting for colour). Applies to NEW chunks; changing it on a
				  // live world cosmetically seams the generation frontier (no terrain cliff). Needs in-game
				  // tuning; leave 0 unless you want wavier borders. See limitations/biome-borders.md.
				  "biomeBorderBlend": %d,

				  // Issue-5 float despeckle (default false until validated). At the features
				  // stage (post-carver, pre-structure) remove SMALL fully-detached solid clumps (the
				  // residual jaggedness/cave floaters) while KEEPING any large formation — so a future
				  // intentional floating sky island is never removed. maxBlocks = biggest clump treated
				  // as an artifact (larger is kept); minY = never touch below this Y. Seed-pure.
				  "floatDespeckleEnabled": %b,
				  "floatDespeckleMaxBlocks": %d,
				  "floatDespeckleMinY": %d,

				  // Rivers R2 NATURAL waterfalls & rapids (spec 7.2): at the features stage, turn the river
				  // graph's flagged lips into natural drops over the carved step cliff — a retained lip
				  // source on the terrain crest, a non-spreading falling curtain, and a rim-contained plunge
				  // pool at the landing (a filled reach basin is reused; a bowl is dug only on dry ground).
				  // Quiescent by construction; never overwrites the aquifer's river water. Set false to
				  // fall back to the plain stepped-pool river descent.
				  "riverFallsEnabled": %b,

				  // Rivers R2 POOL FILL (spec 7.3): at the features stage (before the waterfalls) fill every
				  // still-dry, provably-CONTAINED river-pool column up to its reach surface — the supplement
				  // that makes the stepped pools hold water where the aquifer under-fills a perched basin. A
				  // pool whose rim cannot hold the surface is left DRY (never filled to leak). Quiescent by
				  // construction; never double-places over the aquifer's water. Set false for aquifer-only fill.
				  "riverPoolFillEnabled": %b
				}
				""", config.pregenMaxInFlight, config.pregenPauseWhenPlayersOnline,
				config.pregenLogIntervalSeconds, config.pregenMaxRadiusChunks,
				config.pregenSaveIntervalChunks, config.regionFileCompression,
				config.autoPregenEnabled, config.autoPregenRadiusBlocks,
				config.playerAheadEnabled, config.playerAheadChunks,
				config.siteGradingEnabled, config.siteGradingCutFill, config.siteGradingApronRampMax,
				config.siteGradingCut, config.siteGradingForeignHaloExtra,
				config.siteGradingVeto,
				config.siteGradingVetoMaxRelief, config.siteGradingWaterVetoDepth,
				config.siteGradingCandidateSearch,
				config.siteGradingGradePad, config.siteGradingFoundationFill,
				config.insetForeignStructures,
				config.insetForeignGrade, config.insetForeignGradeReach, config.insetForeignOverhangMin,
				config.insetForeignTunnelBase,
				config.insetForeignBurialGate, config.insetForeignBurialMargin,
				COMPACT_GSON.toJson(config.insetForeignForceSurfaceStructures),
				COMPACT_GSON.toJson(config.insetForeignForceBuriedStructures),
				config.gateForeignInSnow,
				config.gateForeignInWater, config.gateForeignInWaterDepth,
				COMPACT_GSON.toJson(config.siteGradingExtraStructures), config.scaleStructureSpacing,
				config.biomeBorderBlend,
				config.floatDespeckleEnabled, config.floatDespeckleMaxBlocks, config.floatDespeckleMinY,
				config.riverFallsEnabled, config.riverPoolFillEnabled);
		try {
			Files.createDirectories(path.getParent());
			if (!contents.equals(Files.exists(path) ? Files.readString(path) : null)) {
				Files.writeString(path, contents);
				ArcheanRise.LOGGER.info("Config written/upgraded: {}", path);
			}
		} catch (IOException e) {
			ArcheanRise.LOGGER.warn("Could not write config: {}", e.toString());
		}
	}
}
