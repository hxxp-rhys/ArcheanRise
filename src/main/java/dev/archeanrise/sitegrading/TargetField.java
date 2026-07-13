package dev.archeanrise.sitegrading;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Locale;

/**
 * The authoritative per-structure target-height field H(x,z): the block-resolution surface every
 * graded column is cut/filled to (blueprint §3.2, Model B). CUT+FILL (Phases 3-4) grades terrain to
 * it.
 *
 * <p><b>Model B — path-network guarantee (see docs/DECISIONS.md).</b> The blueprint's original
 * "single 1-Lipschitz field over footprints+streets+interior" is unattainable: vanilla jigsaw
 * placement gives village houses an irreducible ±3–4 block template-inherent floor-plane spread (a
 * RIGID child of a RIGID parent inherits the parent plane exactly, but a terrain_matching→RIGID
 * attachment fixes the house at {@code anchor + t_street − k_jigsaw − stepY}). That spread exists
 * even on flat ground and cannot be removed by the field without insetting/floating the placed
 * house blocks (breaks crit 4). So the guaranteed 1-Lipschitz set is the PATH NETWORK (streets +
 * plazas) + APRON; RIGID footprints are pinned fill-support anchors that the field does NOT try to
 * blend between — villagers reach them at ≤1 door thresholds (the auditor's C1 flood-fill is the
 * navigability gate, not this field).
 *
 * <p><b>Cone-apron with adaptive in-band release (2026-07-06 synthesis, see docs/DECISIONS.md).</b>
 * The apron is graded to the PLATFORM's own 1-Lipschitz Chebyshev cones — {@code Uc} (ceiling,
 * min-plus from the PATH planes) and {@code Lc} (floor, max-plus from the PATH planes) — NEVER
 * seeded to raw natural and relaxed. Water-clamped natural {@code M_wc = max(predictorFloor,
 * SEA_LEVEL)} is then classified against the cone band per cell:
 * <ul>
 *   <li>{@code M_wc ≥ Uc}  → APRON, H = Uc  (CUT: natural above the ceiling cone is shaved down)</li>
 *   <li>{@code M_wc ≤ Lc}  → APRON, H = Lc  (FILL: natural below the floor cone is raised)</li>
 *   <li>{@code Lc < M_wc < Uc} → NATURAL, H = M_wc  (ARRIVED: the slope-1 ramp reached natural →
 *       released, left as REAL terrain; graded()=false, so no Φ-vs-M seam)</li>
 *   <li>beyond {@code rampMax} of any piece → NATURAL, H = M_wc  (normal terrain resumes)</li>
 * </ul>
 * The release ring floats to wherever arrival happens (the in-band reclassification IS the adaptive
 * release; clamping M_wc into the band from outside IS the one-sided-natural constraint). The
 * ceiling is additionally capped by the anti-inset limit (never grade above an adjacent house
 * floor, crit 4). Graded cells on the SAME cone (Uc or Lc) differ by ≤1 by construction (a cone is
 * 1-Lipschitz); the counted seams — graded↔released-natural and CUT↔FILL adjacencies — are ≤1
 * whenever M_wc is locally 1-Lipschitz at the arrival contour (the SitePlanner veto's job to
 * guarantee). This is NOT a global by-construction guarantee: {@link #analyze} is the arbiter, the
 * veto the precondition. Residual infeasible&gt;0 is confined to: R1 local natural roughness at the contour and
 * R2 arrival-failure within rampMax (both excluded by the veto), and R3 the terrain-independent
 * house-corner template-spread squeeze (measured, Model-B-inherent).
 *
 * <p><b>Determinism:</b> built purely from (seed → serialized piece boxes/planes/rigid-flag + the
 * predictor-derived {@link NaturalSurface} + the constant SEA_LEVEL); no live-world read, no RNG.
 * The cones are integer min-plus/max-plus chamfers in a fixed raster order (Rosenfeld–Pfaltz
 * chessboard distance transform, exact in 2 passes, no iteration). Every chunk touching the
 * structure computes the SAME field and writes only its own columns (cross-chunk seam-free).
 */
public final class TargetField {
	/** kind codes packed into a byte[] */
	static final byte NATURAL = 0;
	static final byte APRON = 1;
	static final byte PATH = 2;
	static final byte ANCHOR = 3;

	/** Large finite sentinels for the min-plus / max-plus chamfers (never overflow on ±1 steps). */
	private static final int CONE_INF = Integer.MAX_VALUE / 4;

	/** One piece's footprint contribution. {@code plane} = box.minY + groundLevelDelta. */
	public record Piece(BoundingBox box, int plane, boolean rigid) {}

	private final int originX;
	private final int originZ;
	private final int sizeX;
	private final int sizeZ;
	private final int[] h;      // field height, row-major sizeZ x sizeX
	private final byte[] kind;
	private final boolean converged;
	// Phase-2 acceptance certificates (Model B):
	private final int maxPathSlope;     // max |Δh| PATH↔{PATH,APRON,NATURAL} — crit 2, MUST be ≤1
	private final int maxApronSlope;    // max |Δh| APRON↔{PATH,APRON,NATURAL} — crit 3, MUST be ≤1
	private final int pathPinConflict;  // max |Δh| between adjacent PATH cells — street coherence, ≤1
	private final int insetViolations;  // APRON cells sitting ABOVE an adjacent house plane — crit 4, MUST be 0
	private final int infeasibleCells;  // APRON cells with no feasible 1-Lipschitz value — MUST be 0
	private final String diag;
	private String stats = "";          // house/street/natural Y spans — geometry diagnostic

	private TargetField(int originX, int originZ, int sizeX, int sizeZ, int[] h, byte[] kind,
			boolean converged, int maxPathSlope, int maxApronSlope, int pathPinConflict,
			int insetViolations, int infeasibleCells, String diag) {
		this.originX = originX;
		this.originZ = originZ;
		this.sizeX = sizeX;
		this.sizeZ = sizeZ;
		this.h = h;
		this.kind = kind;
		this.converged = converged;
		this.maxPathSlope = maxPathSlope;
		this.maxApronSlope = maxApronSlope;
		this.pathPinConflict = pathPinConflict;
		this.insetViolations = insetViolations;
		this.infeasibleCells = infeasibleCells;
		this.diag = diag;
	}

	public boolean converged() {
		return converged;
	}

	public int maxPathSlope() {
		return maxPathSlope;
	}

	public int maxApronSlope() {
		return maxApronSlope;
	}

	public int pathPinConflict() {
		return pathPinConflict;
	}

	public int insetViolations() {
		return insetViolations;
	}

	public int infeasibleCells() {
		return infeasibleCells;
	}

	/**
	 * The Phase-2 slope-≤1 GUARANTEE for this field (Model B): a converged, feasible field whose
	 * PATH network and APRON are 1-Lipschitz, streets tile coherently, and nothing grades above a
	 * house floor. If false, {@link #diag()} pinpoints the break. (Villager door-reachability is the
	 * auditor's C1 gate, not this field's.)
	 */
	public boolean slopeGuaranteed() {
		return converged
				&& maxPathSlope <= 1
				&& maxApronSlope <= 1
				&& pathPinConflict <= 1
				&& insetViolations == 0
				&& infeasibleCells == 0;
	}

	public String diag() {
		return diag;
	}

	public boolean covers(int x, int z) {
		int lx = x - originX;
		int lz = z - originZ;
		return lx >= 0 && lz >= 0 && lx < sizeX && lz < sizeZ;
	}

	/** Target height at (x,z); Integer.MIN_VALUE if outside the envelope. */
	public int heightAt(int x, int z) {
		if (!covers(x, z)) {
			return Integer.MIN_VALUE;
		}
		return h[(z - originZ) * sizeX + (x - originX)];
	}

	/** True where terrain should be graded (ANCHOR/PATH/APRON); NATURAL cells are left untouched. */
	public boolean graded(int x, int z) {
		if (!covers(x, z)) {
			return false;
		}
		return kind[(z - originZ) * sizeX + (x - originX)] != NATURAL;
	}


	/**
	 * Cell kind at (x,z) for the CUT+FILL grade pass — one of {@link #NATURAL}/{@link #ANCHOR}/
	 * {@link #PATH}/{@link #APRON} (package-visible codes). Out-of-envelope → NATURAL (untouched).
	 */
	byte kindAt(int x, int z) {
		if (!covers(x, z)) {
			return NATURAL;
		}
		return kind[(z - originZ) * sizeX + (x - originX)];
	}

	/** Deterministic FNV-1a hash over (kind, height) — the Phase-2 determinism gate. */
	public long hash() {
		long hsh = 0xcbf29ce484222325L;
		for (int i = 0; i < h.length; i++) {
			hsh = (hsh ^ (kind[i] & 0xff)) * 0x100000001b3L;
			hsh = (hsh ^ (h[i] & 0xffffffffL)) * 0x100000001b3L;
		}
		return hsh;
	}

	/**
	 * Build the field. {@code rampMax} = the max slope-1 blend length beyond the footprints; a
	 * natural border ring of width ≥1 is guaranteed so edge neighbours are always in-bounds.
	 */
	public static TargetField build(List<Piece> pieces, NaturalSurface natural, int rampMax) {
		if (pieces.isEmpty()) {
			throw new IllegalArgumentException("TargetField.build requires at least one piece");
		}
		int pad = rampMax + 2;
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (Piece p : pieces) {
			minX = Math.min(minX, p.box().minX());
			minZ = Math.min(minZ, p.box().minZ());
			maxX = Math.max(maxX, p.box().maxX());
			maxZ = Math.max(maxZ, p.box().maxZ());
		}
		int originX = minX - pad;
		int originZ = minZ - pad;
		int sizeX = (maxX + pad) - originX + 1;
		int sizeZ = (maxZ + pad) - originZ + 1;
		int n = sizeX * sizeZ;
		int[] h = new int[n];
		byte[] kind = new byte[n];
		int[] mwc = new int[n];   // water-clamped natural target = max(predictorFloor, SEA_LEVEL)
		int[] uc = new int[n];    // ceiling cone: min over PATH of (plane + cheb) — min-plus chamfer
		int[] lc = new int[n];    // floor cone:   max over PATH of (plane − cheb) — max-plus chamfer
		int[] dist = new int[n];  // Chebyshev distance to the nearest piece cell (any) — min-plus chamfer

		// Stamp piece planes into per-cell arrays in O(sum of piece footprint areas), NOT
		// O(cells x pieces) — the per-cell piece loop was the field-build hot path (126-piece
		// villages measured ~59ms, dominated by that loop, not the chamfer). Precedence RIGID
		// (ANCHOR) > terrain_matching (PATH); within a kind the last box in piece order wins
		// (deterministic — pieces are a stable serialized list).
		int[] anchorPlane = new int[n];
		int[] pathPlane = new int[n];
		java.util.Arrays.fill(anchorPlane, Integer.MIN_VALUE);
		java.util.Arrays.fill(pathPlane, Integer.MIN_VALUE);
		for (Piece p : pieces) {
			BoundingBox b = p.box();
			int x0 = Math.max(b.minX(), originX);
			int x1 = Math.min(b.maxX(), originX + sizeX - 1);
			int z0 = Math.max(b.minZ(), originZ);
			int z1 = Math.min(b.maxZ(), originZ + sizeZ - 1);
			for (int wz = z0; wz <= z1; wz++) {
				int row = (wz - originZ) * sizeX;
				for (int wx = x0; wx <= x1; wx++) {
					int idx = row + (wx - originX);
					if (p.rigid()) {
						anchorPlane[idx] = p.plane();
					} else {
						pathPlane[idx] = p.plane();
					}
				}
			}
		}
		// Water-clamp the natural target and seed the three chamfers per cell. The cones are seeded
		// wherever a terrain_matching (street) plane exists — including a cell also under a RIGID box
		// (classified ANCHOR): a real street plane exists there, so it correctly radiates the apron
		// cone even though its own H stays the pinned house floor. ANCHOR planes are intentionally NOT
		// cone sources (Model B: houses constrain only via the anti-inset cap, never as slope sources).
		for (int idx = 0; idx < n; idx++) {
			int wx = originX + (idx % sizeX);
			int wz = originZ + (idx / sizeX);
			mwc[idx] = Math.max(natural.heightAt(wx, wz), SiteGrading.SEA_LEVEL);
			int ap = anchorPlane[idx];
			int pp = pathPlane[idx];
			if (ap != Integer.MIN_VALUE) {
				kind[idx] = ANCHOR;
				h[idx] = ap;
			} else if (pp != Integer.MIN_VALUE) {
				kind[idx] = PATH;
				h[idx] = pp;
			}
			// else: classified after the cones are known.
			uc[idx] = pp != Integer.MIN_VALUE ? pp : CONE_INF;
			lc[idx] = pp != Integer.MIN_VALUE ? pp : -CONE_INF;
			dist[idx] = (ap != Integer.MIN_VALUE || pp != Integer.MIN_VALUE) ? 0 : CONE_INF;
		}

		// Chebyshev (8-connected, unit-step) chamfers — Rosenfeld–Pfaltz, exact in one forward +
		// one backward raster pass. uc/dist are min-plus (propagate +1), lc is max-plus (propagate −1).
		chamferMinPlus(uc, sizeX, sizeZ);
		chamferMinPlus(dist, sizeX, sizeZ);
		chamferMaxPlus(lc, sizeX, sizeZ);

		// Anti-inset cap = min plane of any 8-adjacent ANCHOR cell (crit 4: an apron cell may never
		// grade ABOVE a neighbouring house floor). Integer.MAX_VALUE = no adjacent house.
		int[] cap = new int[n];
		for (int idx = 0; idx < n; idx++) {
			if (kind[idx] == PATH || kind[idx] == ANCHOR) {
				cap[idx] = Integer.MAX_VALUE;
				continue;
			}
			int lx = idx % sizeX;
			int lz = idx / sizeX;
			int c = Integer.MAX_VALUE;
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					int nx = lx + dx;
					int nz = lz + dz;
					if (nx < 0 || nz < 0 || nx >= sizeX || nz >= sizeZ) {
						continue;
					}
					int nIdx = nz * sizeX + nx;
					if (kind[nIdx] == ANCHOR) {
						c = Math.min(c, h[nIdx]);
					}
				}
			}
			cap[idx] = c;
		}

		// Classify non-piece cells against the cone band (adaptive in-band release). CUT to the
		// (anti-inset-capped) ceiling, FILL to the floor, else release to real water-clamped natural.
		for (int idx = 0; idx < n; idx++) {
			if (kind[idx] == PATH || kind[idx] == ANCHOR) {
				continue;
			}
			int m = mwc[idx];
			if (dist[idx] > rampMax) {
				kind[idx] = NATURAL; // beyond the ramp budget: normal terrain resumes
				h[idx] = m;
				continue;
			}
			int ceil = uc[idx];
			if (cap[idx] != Integer.MAX_VALUE) {
				ceil = Math.min(ceil, cap[idx]); // anti-inset folded into the ceiling
			}
			// The FILL target never rises above the ceiling (crit 4: never fill above a house floor).
			// When a low house floor collapses the band (floor > ceiling) that is the R3 house-corner
			// squeeze — H is pinned to the ceiling and analyze() surfaces the residual >1 step.
			int floor = Math.min(lc[idx], ceil);
			if (m >= ceil) {
				kind[idx] = APRON; // CUT natural down to the (anti-inset-capped) slope-1 ceiling cone
				h[idx] = ceil;
			} else if (m <= floor) {
				kind[idx] = APRON; // FILL natural up to the slope-1 floor cone
				h[idx] = floor;
			} else {
				kind[idx] = NATURAL; // arrived: natural sits inside the band → leave it untouched
				h[idx] = m;
			}
		}

		// The chamfer is exact (non-iterative); the field is always "converged". analyze() is an
		// INDEPENDENT defensive re-measurement of the Model-B certificates over the final H.
		TargetField field = analyze(h, kind, cap, sizeX, sizeZ, originX, originZ, true);
		field.stats = geometryStats(h, kind);
		return field;
	}

	/** One forward + one backward 8-connected sweep: a[c] = min over sources of (value + chebDist). */
	private static void chamferMinPlus(int[] a, int sizeX, int sizeZ) {
		for (int lz = 0; lz < sizeZ; lz++) {
			for (int lx = 0; lx < sizeX; lx++) {
				int idx = lz * sizeX + lx;
				int v = a[idx];
				v = relaxMin(v, a, sizeX, sizeZ, lx - 1, lz);
				v = relaxMin(v, a, sizeX, sizeZ, lx - 1, lz - 1);
				v = relaxMin(v, a, sizeX, sizeZ, lx, lz - 1);
				v = relaxMin(v, a, sizeX, sizeZ, lx + 1, lz - 1);
				a[idx] = v;
			}
		}
		for (int lz = sizeZ - 1; lz >= 0; lz--) {
			for (int lx = sizeX - 1; lx >= 0; lx--) {
				int idx = lz * sizeX + lx;
				int v = a[idx];
				v = relaxMin(v, a, sizeX, sizeZ, lx + 1, lz);
				v = relaxMin(v, a, sizeX, sizeZ, lx + 1, lz + 1);
				v = relaxMin(v, a, sizeX, sizeZ, lx, lz + 1);
				v = relaxMin(v, a, sizeX, sizeZ, lx - 1, lz + 1);
				a[idx] = v;
			}
		}
	}

	/** One forward + one backward 8-connected sweep: b[c] = max over sources of (value − chebDist). */
	private static void chamferMaxPlus(int[] b, int sizeX, int sizeZ) {
		for (int lz = 0; lz < sizeZ; lz++) {
			for (int lx = 0; lx < sizeX; lx++) {
				int idx = lz * sizeX + lx;
				int v = b[idx];
				v = relaxMax(v, b, sizeX, sizeZ, lx - 1, lz);
				v = relaxMax(v, b, sizeX, sizeZ, lx - 1, lz - 1);
				v = relaxMax(v, b, sizeX, sizeZ, lx, lz - 1);
				v = relaxMax(v, b, sizeX, sizeZ, lx + 1, lz - 1);
				b[idx] = v;
			}
		}
		for (int lz = sizeZ - 1; lz >= 0; lz--) {
			for (int lx = sizeX - 1; lx >= 0; lx--) {
				int idx = lz * sizeX + lx;
				int v = b[idx];
				v = relaxMax(v, b, sizeX, sizeZ, lx + 1, lz);
				v = relaxMax(v, b, sizeX, sizeZ, lx + 1, lz + 1);
				v = relaxMax(v, b, sizeX, sizeZ, lx, lz + 1);
				v = relaxMax(v, b, sizeX, sizeZ, lx - 1, lz + 1);
				b[idx] = v;
			}
		}
	}

	private static int relaxMin(int v, int[] a, int sizeX, int sizeZ, int nx, int nz) {
		if (nx < 0 || nz < 0 || nx >= sizeX || nz >= sizeZ) {
			return v;
		}
		int nb = a[nz * sizeX + nx];
		if (nb >= CONE_INF) {
			return v; // unreached source: do not propagate the sentinel
		}
		return Math.min(v, nb + 1);
	}

	private static int relaxMax(int v, int[] b, int sizeX, int sizeZ, int nx, int nz) {
		if (nx < 0 || nz < 0 || nx >= sizeX || nz >= sizeZ) {
			return v;
		}
		int nb = b[nz * sizeX + nx];
		if (nb <= -CONE_INF) {
			return v; // unreached source
		}
		return Math.max(v, nb - 1);
	}

	/** house/street/natural Y spans + the platform↔natural divergence (drives veto calibration). */
	private static String geometryStats(int[] h, byte[] kind) {
		int aMin = Integer.MAX_VALUE, aMax = Integer.MIN_VALUE;
		int pMin = Integer.MAX_VALUE, pMax = Integer.MIN_VALUE;
		int nMin = Integer.MAX_VALUE, nMax = Integer.MIN_VALUE;
		for (int i = 0; i < h.length; i++) {
			switch (kind[i]) {
				case ANCHOR -> { aMin = Math.min(aMin, h[i]); aMax = Math.max(aMax, h[i]); }
				case PATH -> { pMin = Math.min(pMin, h[i]); pMax = Math.max(pMax, h[i]); }
				case NATURAL -> { nMin = Math.min(nMin, h[i]); nMax = Math.max(nMax, h[i]); }
				default -> { }
			}
		}
		return String.format(Locale.ROOT, "house Y=[%d..%d] street Y=[%d..%d] natural Y=[%d..%d]",
				aMin, aMax, pMin, pMax, nMin, nMax);
	}

	public String stats() {
		return stats;
	}

	/** Compute the Model-B certificates + a pinpoint diagnostic over the final field. */
	private static TargetField analyze(int[] h, byte[] kind, int[] cap, int sizeX, int sizeZ,
			int originX, int originZ, boolean converged) {
		int maxPathSlope = 0;
		int maxApronSlope = 0;
		int pathPinConflict = 0;
		int insetViolations = 0;
		int infeasibleCells = 0;
		int worstWX = 0, worstWZ = 0, worstCellH = 0, worstNbrH = 0;
		byte worstNbrKind = NATURAL;
		String worstMetric = "none";
		int worst = 0;

		for (int idx = 0; idx < sizeX * sizeZ; idx++) {
			byte kc = kind[idx];
			if (kc != PATH && kc != APRON) {
				continue;
			}
			int lx = idx % sizeX;
			int lz = idx / sizeX;
			// infeasibility + anti-inset checks only for APRON
			if (kc == APRON && cap[idx] != Integer.MAX_VALUE && h[idx] > cap[idx]) {
				insetViolations++;
			}
			int lo = Integer.MIN_VALUE, hi = Integer.MAX_VALUE;
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					int nx = lx + dx;
					int nz = lz + dz;
					if (nx < 0 || nz < 0 || nx >= sizeX || nz >= sizeZ) {
						continue;
					}
					int nIdx = nz * sizeX + nx;
					byte nk = kind[nIdx];
					int d = Math.abs(h[idx] - h[nIdx]);
					if (kc == APRON && nk != ANCHOR) {
						lo = Math.max(lo, h[nIdx] - 1);
						hi = Math.min(hi, h[nIdx] + 1);
					}
					// slope metrics: never count ANCHOR neighbours (houses are fill-support, not paths)
					if (nk == ANCHOR) {
						continue;
					}
					if (kc == PATH) {
						if (d > maxPathSlope) {
							maxPathSlope = d;
						}
						if (nk == PATH && d > pathPinConflict) {
							pathPinConflict = d;
						}
					} else { // APRON
						if (d > maxApronSlope) {
							maxApronSlope = d;
						}
					}
					if (d > worst) {
						worst = d;
						worstWX = originX + lx;
						worstWZ = originZ + lz;
						worstCellH = h[idx];
						worstNbrH = h[nIdx];
						worstNbrKind = nk;
						worstMetric = kc == PATH ? (nk == PATH ? "path-pin" : "path") : "apron";
					}
				}
			}
			if (kc == APRON && cap[idx] != Integer.MAX_VALUE) {
				hi = Math.min(hi, cap[idx]);
			}
			if (kc == APRON && lo > hi) {
				infeasibleCells++;
			}
		}

		String diag;
		boolean ok = converged && maxPathSlope <= 1 && maxApronSlope <= 1 && pathPinConflict <= 1
				&& insetViolations == 0 && infeasibleCells == 0;
		if (ok) {
			diag = "ok";
		} else {
			diag = String.format(Locale.ROOT,
					"worst %s Δ=%d @world(%d,%d) cellH=%d vs nbrH=%d[%s]; inset=%d infeasible=%d",
					worstMetric, worst, worstWX, worstWZ, worstCellH, worstNbrH,
					kindName(worstNbrKind), insetViolations, infeasibleCells);
		}
		return new TargetField(originX, originZ, sizeX, sizeZ, h, kind, converged,
				maxPathSlope, maxApronSlope, pathPinConflict, insetViolations, infeasibleCells, diag);
	}

	private static String kindName(byte k) {
		return k == ANCHOR ? "ANCHOR" : k == PATH ? "PATH" : k == APRON ? "APRON" : "NATURAL";
	}
}
