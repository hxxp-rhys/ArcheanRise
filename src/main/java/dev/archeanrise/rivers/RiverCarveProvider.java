package dev.archeanrise.rivers;

import dev.archeanrise.ArcheanRise;
import dev.archeanrise.sitegrading.SiteGrading;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chunk-gen bridge from the {@code river_carve} density function
 * ({@link dev.archeanrise.noise.df.RiverCarveDensityFunction}) to the {@link RiverGraph} +
 * {@link RiverCarve} carve — solving the "how does a per-column DF reach the world's river graph"
 * question (doc 14 §0/§1) WITHOUT a mixin.
 *
 * <p><b>Binding.</b> {@code river_carve} exists ONLY in the Archean overworld's noise settings
 * ({@code archean_rise:rise}), so a computing DF is always generating THAT overworld. The DF
 * therefore reaches its world through the running server's {@link MinecraftServer#overworld()}
 * ({@link #current()}), captured at {@code SERVER_STARTING} (before spawn-chunk gen) and cleared at
 * {@code SERVER_STOPPING}. The graph is built lazily, once per seed, EXACTLY as the R2a
 * {@code /archeanrise riverdump} command builds it ({@code new RiverGraph(seed,
 * ServerRiverSampler.of(overworld))}) — so the in-game graph is the R2a-parity-proven graph.
 *
 * <p><b>Vanilla safety (blast radius).</b> {@link #current()} returns {@code null} — making the DF
 * a byte-exact no-op — whenever there is no server, no overworld, or the overworld is not an Archean
 * generator (the {@link SiteGrading#isArcheanGenerator} guard the ores use). Combined with the DF
 * only ever appearing in the Archean noise settings, vanilla-preset worlds and other dimensions
 * never carve.
 *
 * <p><b>Per-cell carvers &amp; correctness.</b> The R2a {@link RiverGraph#realize} is coarse-locked,
 * so a per-column query cannot realize per-call. Instead each {@link RiverConstants#RIVER_CELL}
 * region is realized ONCE and turned into a {@link RiverCarve.Carver}, cached in a small LRU; every
 * column in that cell answers from the cached carver. This is CORRECT because
 * {@code realize(cellX,cellZ,cellX,cellZ)} returns every path/lake sourced within
 * {@link RiverConstants#VIS_PAD} cells, and any carve feature within its maximum influence
 * ({@code W_MAX/2 + VALLEY_HALO = 76} for reaches, {@code LAKE_R = 48} for lakes) of a column in the
 * cell is sourced within {@code 76 + TRACE_REACH = 6100 &lt; VIS_PAD·RIVER_CELL = 8192} of it — so
 * the per-cell carver contains every feature that can carve any of the cell's columns. Features from
 * farther cells only ever sit beyond the halo (zone OUTSIDE → the fixed natural result), so a
 * per-cell carver gives byte-identical {@code carveAt} results to a whole-region carver. That is the
 * bridge from the whole-region carve-parity gate to the in-game per-cell carve.
 *
 * <p><b>Threading.</b> The provider registry and the carver cache are concurrency-safe
 * (ConcurrentHashMap + synchronized LRU); {@link RiverCarve.Carver} is immutable after build and
 * {@link RiverGraph#realize} is internally synchronized. No mutable per-call state is held on the
 * shared DF.
 */
public final class RiverCarveProvider {

	/** Residual mountain amplitude at the channel centre (valley-suppression floor, Step D first cut). */
	public static final double VALLEY_FLOOR = 0.15;

	private static final int CARVER_CACHE_CAP = 64;

	/** The live server, captured at SERVER_STARTING (before spawn-chunk gen), cleared at STOPPING. */
	private static volatile MinecraftServer server;
	/** One provider per world seed (the Archean overworld is the only river_carve consumer). */
	private static final ConcurrentHashMap<Long, RiverCarveProvider> PROVIDERS = new ConcurrentHashMap<>();

	/** null graph ⇒ inert provider (build failed); {@link #current()} maps it back to a no-op. */
	private final RiverGraph graph;
	/** Access-order LRU of per-cell carvers; builds happen OUTSIDE the lock (SiteGradeField pattern). */
	private final Map<Long, RiverCarve.Carver> carverCache = Collections.synchronizedMap(
			new LinkedHashMap<>(96, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, RiverCarve.Carver> eldest) {
					return size() > CARVER_CACHE_CAP;
				}
			});
	/** Access-order LRU of per-cell waterfall-lip lists (the R2b-3 {@code RiverFalls} pass consumer);
	 *  one immutable list per {@link RiverConstants#RIVER_CELL} cell, extracted from that cell's
	 *  realized graph. Same cache discipline + cap as {@link #carverCache}. */
	private final Map<Long, List<Lip>> lipCache = Collections.synchronizedMap(
			new LinkedHashMap<>(96, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, List<Lip>> eldest) {
					return size() > CARVER_CACHE_CAP;
				}
			});

	private RiverCarveProvider(RiverGraph graph) {
		this.graph = graph;
	}

	// ---- lifecycle (wired on both loaders through ArcheanRise) ----

	public static void onServerStarting(MinecraftServer s) {
		server = s;
		PROVIDERS.clear();
	}

	public static void onServerStopping(MinecraftServer s) {
		server = null;
		PROVIDERS.clear();
	}

	/**
	 * The provider for the live Archean overworld, or {@code null} when the carve must be a byte-exact
	 * no-op (no server yet, no overworld, the overworld is not an Archean generator, or the graph
	 * could not be built — e.g. a world without the Archean datapack).
	 */
	public static RiverCarveProvider current() {
		MinecraftServer s = server;
		if (s == null) {
			return null;
		}
		ServerLevel overworld = s.overworld();
		if (overworld == null
				|| !SiteGrading.isArcheanGenerator(overworld.getChunkSource().getGenerator())) {
			return null;
		}
		RiverCarveProvider p = PROVIDERS.computeIfAbsent(overworld.getSeed(), k -> build(overworld));
		return p.graph == null ? null : p;
	}

	private static RiverCarveProvider build(ServerLevel overworld) {
		try {
			RiverGraph graph = new RiverGraph(overworld.getSeed(), ServerRiverSampler.of(overworld));
			ArcheanRise.LOGGER.info("river_carve: built the river graph for the overworld (seed {})",
					overworld.getSeed());
			return new RiverCarveProvider(graph);
		} catch (RuntimeException e) {
			ArcheanRise.LOGGER.warn("river_carve: could not build the river graph (carve inert this "
					+ "session — terrain generates uncarved): {}", e.toString());
			return new RiverCarveProvider(null);
		}
	}

	// ---- queries (called per quart-column by the DF) ----

	/** The carved designed HEIGHT + zone at (x, z) given the natural designed height there. */
	public RiverCarve.Result carveAt(double x, double z, double naturalH) {
		return carverForColumn(x, z).carveAt(x, z, naturalH);
	}

	/**
	 * Valley-suppression factor for {@code mountainAmp} (spec §7.3, Step D first cut): exactly
	 * {@code 1.0} outside the corridor+halo — so mountain amplitude, and therefore the designed
	 * surface, is byte-unchanged away from rivers — ramping to {@link #VALLEY_FLOOR} into the
	 * channel/basin along the SAME smoothstep the bank blend uses. Zone classification is
	 * height-free, so a stable natural of {@code 0.0} is passed (only the rare lake↔river override
	 * tiebreak reads it; a soft first-cut suppression is insensitive to it).
	 */
	public double valleyFactorAt(double x, double z) {
		RiverCarve.Result r = carverForColumn(x, z).carveAt(x, z, 0.0);
		if (r.zone == RiverCarve.Zone.OUTSIDE) {
			return 1.0; // EXACT 1.0 → mountain_amp * 1.0 == mountain_amp (byte-identical outside rivers)
		}
		if (r.zone == RiverCarve.Zone.BED || r.zone == RiverCarve.Zone.LAKE) {
			return VALLEY_FLOOR;
		}
		double half = r.width / 2;
		double t = (r.d - half) / RiverCarve.VALLEY_HALO;
		double f = t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t); // = the bank blend smoothstep
		return VALLEY_FLOOR + (1.0 - VALLEY_FLOOR) * f;
	}

	// ---- R2c water masks + carver shield (doc 11 §5a aquifer trio; the R2c scratchpad plan) ----
	// The three masks below feed the aquifer trio in the noise router (floodedness/spread override +
	// prelim raise); the JSON composition and the mechanism proof live in tools/generate-worldgen.mjs
	// and DECISIONS "RIVERS R2c". All are 2-D (the carve is Y-independent) and read the SAME per-cell
	// carver the R2b carve uses — so away from rivers they cost one cheap bucket scan and return the
	// neutral value (0 / sentinel), and vanilla-preset worlds never reach here ({@link #current()} null).

	/** No-river sentinel for {@link #waterLevelAt}: so low the JSON Y-gates clamp to 0 (dry) outside rivers. */
	public static final double NO_RIVER = -1.0e9;
	/** Full-strength (mask 1.0) plateau beyond the channel edge, so EVERY aquifer source cell adjacent
	 *  to a narrow channel reads solidly in-band (the aquifer picks one jittered source per 16×16 cell;
	 *  a partial mask under-fills narrow reaches). Safe on flowing reaches: the bank there is ≥ L, so
	 *  there is no void below L to flood; on pooled reaches it simply fills more of the basin. */
	public static final double CORRIDOR_CORE = 16.0;
	/** Corridor-mask fade width beyond the plateau — the floodedness in-band (0.62) region ⊂ this. */
	public static final double CORRIDOR_FADE = 32.0;
	/** Apron reach beyond the channel edge — the prelim-raise region. Covers the aquifer's 13-sample
	 *  preliminary-surface clamp footprint (x −48..+16, z ±16 blocks — verified against 1.21.1
	 *  {@code Aquifer.SURFACE_SAMPLING_OFFSETS_IN_CHUNKS}) of every bed-filling source. Chosen so
	 *  {@code half + APRON_REACH ≤ 76} (the carve influence bound W_MAX/2+VALLEY_HALO), keeping the
	 *  per-cell carver complete out to it under the SAME 3×3 guarantee {@link RiverCarve#carveAt} relies on. */
	public static final double APRON_REACH = 64.0;
	/** Apron outer smoothstep fade — softens the floodedness dry-apron seam at the apron rim. */
	public static final double APRON_FADE = 8.0;
	/** spec §7.3 CARVER_SHIELD: carvers must not cut within this many blocks of a wetted bed/bank/pool. */
	public static final int CARVER_SHIELD = 6;

	/** Reach water surface L (Y) inside corridor+apron, {@link #NO_RIVER} outside — the JSON Y-gates read
	 *  this and clamp to 0/dry when it is the sentinel. 2-D. */
	public double waterLevelAt(double x, double z) {
		RiverCarve.WaterSample s = carverForColumn(x, z).sampleWater(x, z);
		return (s.present() && s.dEdge <= APRON_REACH) ? s.level : NO_RIVER;
	}

	/** Corridor mask ∈ [0,1]: 1 in the bed/basin, smoothstep-fading to 0 over {@link #CORRIDOR_FADE}
	 *  beyond the channel edge, 0 elsewhere. Drives the floodedness in-band (0.62) region + the
	 *  {@code fluid_level_spread} target scope. */
	public double corridorMaskAt(double x, double z) {
		RiverCarve.WaterSample s = carverForColumn(x, z).sampleWater(x, z);
		if (!s.present() || s.dEdge >= CORRIDOR_CORE + CORRIDOR_FADE) {
			return 0.0;
		}
		if (s.dEdge <= CORRIDOR_CORE) {
			return 1.0;
		}
		return 1.0 - smoothstep((s.dEdge - CORRIDOR_CORE) / CORRIDOR_FADE);
	}

	/** Apron mask ∈ [0,1]: 1 out to {@code APRON_REACH − APRON_FADE} beyond the channel edge,
	 *  smoothstep-fading to 0 at {@link #APRON_REACH}, 0 beyond. Drives the prelim raise and the
	 *  dry-apron floodedness push (apron − corridor). */
	public double apronMaskAt(double x, double z) {
		RiverCarve.WaterSample s = carverForColumn(x, z).sampleWater(x, z);
		if (!s.present() || s.dEdge >= APRON_REACH) {
			return 0.0;
		}
		double inner = APRON_REACH - APRON_FADE;
		if (s.dEdge <= inner) {
			return 1.0;
		}
		return 1.0 - smoothstep((s.dEdge - inner) / APRON_FADE);
	}

	/** spec §7.3 carver shield: true when (x,y,z) sits within {@link #CARVER_SHIELD} of a river/lake bed,
	 *  bank, or pool BELOW its water surface — the volume cave/canyon carvers must not cut. Fed by the
	 *  same per-cell carver the water masks use; a pure, thread-safe read (C2ME carves off-thread). */
	public boolean isShielded(int x, int y, int z) {
		RiverCarve.WaterSample s = carverForColumn(x, z).sampleWater(x, z);
		if (!s.present() || s.dEdge > CARVER_SHIELD) {
			return false;
		}
		double bed = s.level - s.depth;
		return y >= bed - CARVER_SHIELD && y <= s.level + CARVER_SHIELD;
	}

	private static double smoothstep(double t) {
		return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
	}

	private RiverCarve.Carver carverForColumn(double x, double z) {
		int cellX = (int) Math.floor(x / RiverConstants.RIVER_CELL);
		int cellZ = (int) Math.floor(z / RiverConstants.RIVER_CELL);
		long key = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
		RiverCarve.Carver c = carverCache.get(key);
		if (c == null) {
			// build OUTSIDE the map lock; a concurrent duplicate build is benign (deterministic).
			c = RiverCarve.buildCarver(graph.realize(cellX, cellZ, cellX, cellZ));
			carverCache.put(key, c);
		}
		return c;
	}

	// ---- R2b-3 waterfalls/rapids: lip geometry from the graph (spec §7.2; the RiverFalls pass) ----

	/**
	 * One realized waterfall/rapids drop — the graph's per-node lip (spec §7.5 {@code LIP_STEP}=4
	 * marks a waterfall; a 2..3 drop is a rapids). {@code x,z} is the DOWNSTREAM (lip) node — the
	 * plunge-pool centre; {@code upperY}=the upstream node's reach water surface (the crest / retained
	 * lip source level), {@code lowerY}=this node's reach water surface (the plunge-pool surface);
	 * {@code (upDx,upDz)} is the unit heading from the lip toward the upstream node (where the cliff /
	 * retained source is built). The falling height is {@code upperY − lowerY}. All fields are pure
	 * functions of the seed-pure graph.
	 */
	public record Lip(double x, double z, double upperY, double lowerY, double upDx, double upDz) {
		/** Vertical drop (blocks) of this step — {@code ≥4} waterfall, {@code 2..3} rapids. */
		public double drop() {
			return upperY - lowerY;
		}

		/**
		 * The terrain spill CREST the R2b-4b carve builds at this lip (spec §7.2): the pool floor
		 * rises to {@code upperY − LIP_SPILL_DROP} across the channel over the last
		 * {@link RiverCarve#LIP_ALONG} blocks before this node, below the {@code upperY + 1} side dams
		 * — the one column the pool spills over. R2b-4d's fall starts here (walk {@code LIP_ALONG}
		 * upstream from {@code (x,z)} along {@link #upDx}/{@link #upDz} to reach the crest, then fall
		 * {@code drop()} to the plunge pool at {@code lowerY}).
		 */
		public double spillY() {
			return upperY - RiverCarve.LIP_SPILL_DROP;
		}

		/** Downstream flow-direction X component at the lip ({@link #upDx} points upstream). */
		public double flowDx() {
			return -upDx;
		}

		/** Downstream flow-direction Z component at the lip. */
		public double flowDz() {
			return -upDz;
		}
	}

	/**
	 * Every waterfall/rapids lip whose lip node lies within {@code [chunkMinX−margin,
	 * chunkMinX+15+margin] × [chunkMinZ−margin, chunkMinZ+15+margin]} — i.e. every drop whose bounded
	 * structure can intersect this chunk. Realizes the chunk's {@link RiverConstants#RIVER_CELL} cell
	 * ONCE (cached, like {@link #carverForColumn}) and filters; a drop straddling a chunk border is
	 * returned to BOTH chunks so each writes its own clamped slice (deterministic union). Pure +
	 * thread-safe (immutable realized graph, no world state).
	 */
	public List<Lip> lipsNear(int chunkMinX, int chunkMinZ, int margin) {
		int cellX = Math.floorDiv(chunkMinX, RiverConstants.RIVER_CELL);
		int cellZ = Math.floorDiv(chunkMinZ, RiverConstants.RIVER_CELL);
		List<Lip> all = lipsForCell(cellX, cellZ);
		if (all.isEmpty()) {
			return List.of();
		}
		double lo0 = chunkMinX - margin, hi0 = chunkMinX + 15 + margin;
		double lo1 = chunkMinZ - margin, hi1 = chunkMinZ + 15 + margin;
		ArrayList<Lip> out = new ArrayList<>();
		for (Lip lip : all) {
			if (lip.x >= lo0 && lip.x <= hi0 && lip.z >= lo1 && lip.z <= hi1) {
				out.add(lip);
			}
		}
		return out;
	}

	/** Per-cell lip list, extracted once from the cell's realized graph and cached (LRU). */
	private List<Lip> lipsForCell(int cellX, int cellZ) {
		long key = ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
		List<Lip> list = lipCache.get(key);
		if (list != null) {
			return list;
		}
		// build OUTSIDE the map lock; a concurrent duplicate build is benign (deterministic).
		ArrayList<Lip> lips = new ArrayList<>();
		for (RiverGraph.RiverPath p : graph.realize(cellX, cellZ, cellX, cellZ).paths()) {
			List<RiverGraph.PathNode> nodes = p.nodes;
			for (int i = 1; i < nodes.size(); i++) {
				RiverGraph.PathNode b = nodes.get(i);
				if (!b.lip) {
					continue;
				}
				RiverGraph.PathNode a = nodes.get(i - 1);
				double dx = a.x - b.x, dz = a.z - b.z;
				double len = Math.sqrt(dx * dx + dz * dz);
				double ux = len > 1e-9 ? dx / len : 0.0;
				double uz = len > 1e-9 ? dz / len : 0.0;
				lips.add(new Lip(b.x, b.z, a.waterY, b.waterY, ux, uz));
			}
		}
		List<Lip> frozen = List.copyOf(lips);
		lipCache.put(key, frozen);
		return frozen;
	}
}
